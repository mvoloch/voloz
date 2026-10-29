"""
Tests for text shortcuts (snippets): validation, whole-dictation and
inline matching, and the capture flow — a dictation that is only the
trigger skips refinement, a trigger inside a longer one is expanded after
refinement so the LLM never sees the saved text.

HTTP calls go to the in-process ``FakeServer`` from
``test_cloud_dictation.py`` (no network, no keys).
"""

import json

import pytest

from backend.backends import reset_backends
from backend.services.snippets import (
    MAX_SNIPPET_TEXT_CHARS,
    MAX_SNIPPETS,
    MAX_TRIGGER_CHARS,
    SNIPPET_LABEL,
    expand_snippets,
    match_whole_snippet,
    normalize_snippets,
)
from backend.tests.test_cloud_dictation import FakeServer, _wav_bytes
from backend.tests.test_vocabulary import api  # noqa: F401 — fixture


@pytest.fixture(autouse=True)
def _clean_backends():
    reset_backends()
    yield
    reset_backends()


ADDRESS = "Rua das Flores, 123\nApto 45\n01234-567 São Paulo SP"
SIGNATURE = "Abraço,\nMarcus"
SNIPPETS = [
    {"trigger": "meu endereço", "text": ADDRESS},
    {"trigger": "assinatura", "text": SIGNATURE},
]


# ── Validation ────────────────────────────────────────────────────────────


def test_snippets_are_trimmed_deduplicated_and_keep_line_breaks():
    cleaned = normalize_snippets(
        [
            {"trigger": "  meu   endereço ", "text": "old"},
            {"trigger": "", "text": "x"},
            {"trigger": "vazio", "text": "   "},
            {"trigger": "?!", "text": "no words in the trigger"},
            {"trigger": "assinatura", "text": "\r\nAbraço,\r\nMarcus\r\n"},
            {"trigger": "Meu Endereco", "text": ADDRESS},
        ]
    )
    assert cleaned == [
        {"trigger": "assinatura", "text": SIGNATURE},
        {"trigger": "Meu Endereco", "text": ADDRESS},
    ]
    assert normalize_snippets(None) == []


def test_snippet_caps_raise():
    with pytest.raises(ValueError, match="longer than"):
        normalize_snippets([{"trigger": "x" * (MAX_TRIGGER_CHARS + 1), "text": "y"}])
    with pytest.raises(ValueError, match="longer than"):
        normalize_snippets([{"trigger": "x", "text": "y" * (MAX_SNIPPET_TEXT_CHARS + 1)}])
    with pytest.raises(ValueError, match="limit"):
        normalize_snippets([{"trigger": f"t{i}", "text": "y"} for i in range(MAX_SNIPPETS + 1)])


# ── Matching ──────────────────────────────────────────────────────────────


@pytest.mark.parametrize(
    "transcript",
    [
        "meu endereço",
        "Meu endereço.",
        "MEU ENDERECO!",
        "  meu,   endereço?  ",
        "É... meu endereço.",
        "Hum, meu endereço, tipo.",
    ],
)
def test_a_transcript_that_is_only_the_trigger_becomes_the_text(transcript):
    assert match_whole_snippet(transcript, SNIPPETS) == ADDRESS


@pytest.mark.parametrize(
    "transcript",
    ["", "   ", "meu endereço novo", "o meu endereço", "endereço", "meu endereço 2"],
)
def test_other_transcripts_are_not_a_whole_shortcut(transcript):
    assert match_whole_snippet(transcript, SNIPPETS) is None


def test_the_longer_trigger_wins_a_whole_match():
    snippets = SNIPPETS + [{"trigger": "meu endereço comercial", "text": "Av. Paulista, 1000"}]
    assert match_whole_snippet("Meu endereço comercial.", snippets) == "Av. Paulista, 1000"
    assert match_whole_snippet("Meu endereço.", snippets) == ADDRESS


def test_triggers_inside_a_dictation_are_expanded_in_place():
    text = "Pode mandar para Meu Endereco, e no fim a assinatura."
    assert expand_snippets(text, SNIPPETS) == f"Pode mandar para {ADDRESS}, e no fim a {SIGNATURE}."


def test_inline_expansion_is_whole_word_and_runs_once():
    snippets = [{"trigger": "email", "text": "marcus@example.com email"}]
    # "emails" is another word; the inserted text is not searched again.
    assert expand_snippets("emails: email.", snippets) == "emails: marcus@example.com email."
    assert expand_snippets("nothing here", snippets) == "nothing here"
    assert expand_snippets("", snippets) == ""
    assert expand_snippets("email", []) == "email"


def test_inline_expansion_maps_folded_characters_back_to_the_original():
    # "ß" folds to two characters; the span after it must still line up.
    snippets = [{"trigger": "strasse", "text": "Street"}]
    assert expand_snippets("Die Straße, bitte.", snippets) == "Die Street, bitte."


# ── Storage and the capture flow ──────────────────────────────────────────


def _chat_reply(text: str):
    return 200, {"choices": [{"message": {"role": "assistant", "content": text}}]}


def _configure(api, stt_url, llm_url=None, auto_refine=True):
    patch = {
        "custom_stt_endpoint": stt_url,
        "custom_stt_model": "whisper-large-v3-turbo",
        "auto_refine": auto_refine,
        "snippets": SNIPPETS,
    }
    if llm_url:
        patch.update({"custom_llm_endpoint": llm_url, "custom_llm_model": "qwen/test"})
    res = api.put("/settings/captures", json=patch)
    assert res.status_code == 200, res.text


def _dictate(api):
    res = api.post(
        "/captures",
        files={"file": ("clip.wav", _wav_bytes(), "audio/wav")},
        data={"source": "dictation", "language": "pt"},
    )
    assert res.status_code == 200, res.text
    return res.json()


def test_settings_round_trip_and_limits(api):
    assert api.get("/settings/captures").json()["snippets"] == []
    res = api.put(
        "/settings/captures",
        json={"snippets": [{"trigger": " assinatura ", "text": "Abraço,\r\nMarcus\n"}]},
    )
    assert res.status_code == 200, res.text
    assert api.get("/settings/captures").json()["snippets"] == [
        {"trigger": "assinatura", "text": SIGNATURE}
    ]
    assert api.put("/settings/captures", json={"snippets": [{"trigger": "a"}]}).status_code == 422
    too_long = [{"trigger": "a", "text": "y" * (MAX_SNIPPET_TEXT_CHARS + 1)}]
    assert api.put("/settings/captures", json={"snippets": too_long}).status_code == 422
    assert len(api.get("/settings/captures").json()["snippets"]) == 1


def test_a_dictation_that_is_only_the_trigger_pastes_the_text_without_refining(api):
    with (
        FakeServer(lambda *_: (200, {"text": "Meu endereço."})) as stt,
        FakeServer(lambda *_: _chat_reply("should not be called")) as llm,
    ):
        _configure(api, stt.base_url, llm.base_url)
        capture = _dictate(api)
        assert capture["transcript_raw"] == "Meu endereço."
        assert capture["transcript_refined"] == ADDRESS
        assert capture["llm_model"] == SNIPPET_LABEL
        assert capture["auto_refine"] is False

        # A manual refine gives the same text and still skips the LLM.
        res = api.post(f"/captures/{capture['id']}/refine", json={})
        assert res.status_code == 200, res.text
        assert res.json()["transcript_refined"] == ADDRESS
        assert llm.requests == []


def test_a_trigger_inside_a_dictation_is_expanded_after_refinement(api):
    with (
        FakeServer(lambda *_: (200, {"text": "manda pra meu endereço por favor"})) as stt,
        FakeServer(lambda *_: _chat_reply("Manda pra meu endereço, por favor.")) as llm,
    ):
        _configure(api, stt.base_url, llm.base_url)
        capture = _dictate(api)
        # Refinement follows, so nothing is expanded yet.
        assert capture["auto_refine"] is True
        assert capture["transcript_refined"] is None

        res = api.post(f"/captures/{capture['id']}/refine", json={})
        assert res.status_code == 200, res.text
        assert res.json()["transcript_refined"] == f"Manda pra {ADDRESS}, por favor."
        assert res.json()["llm_model"] == "qwen/test"
        # The LLM saw the trigger, never the saved text.
        sent = json.loads(llm.requests[-1]["raw"])
        assert "Rua das Flores" not in json.dumps(sent)


def test_a_failed_refinement_has_a_fallback_with_the_shortcut_expanded(api):
    rate_limited = (429, {"error": {"message": "Rate limit reached", "code": "rate_limit_exceeded"}})
    with (
        FakeServer(lambda *_: (200, {"text": "manda pra meu endereço por favor"})) as stt,
        FakeServer(lambda *_: rate_limited) as llm,
    ):
        _configure(api, stt.base_url, llm.base_url)
        capture = _dictate(api)
        assert capture["auto_refine"] is True
        assert capture["transcript_raw"] == "manda pra meu endereço por favor"
        # The app pastes this when the refine below fails, not transcript_raw.
        assert capture["fallback_text"] == f"manda pra {ADDRESS} por favor"
        assert api.post(f"/captures/{capture['id']}/refine", json={}).status_code == 429

    # Not stored: the capture itself still says it was not refined.
    stored = api.get(f"/captures/{capture['id']}").json()
    assert "fallback_text" not in stored
    assert stored["transcript_refined"] is None


def test_with_refinement_off_a_trigger_is_expanded_in_the_delivered_text(api):
    with FakeServer(lambda *_: (200, {"text": "Até logo. Assinatura"})) as stt:
        _configure(api, stt.base_url, auto_refine=False)
        capture = _dictate(api)
        assert capture["transcript_raw"] == "Até logo. Assinatura"
        assert capture["transcript_refined"] == f"Até logo. {SIGNATURE}"
        assert capture["llm_model"] == SNIPPET_LABEL
        assert capture["auto_refine"] is False


def test_a_dictation_without_triggers_is_unchanged(api):
    with FakeServer(lambda *_: (200, {"text": "Bom dia a todos."})) as stt:
        _configure(api, stt.base_url, auto_refine=False)
        capture = _dictate(api)
        assert capture["transcript_refined"] is None
        assert capture["llm_model"] is None


def test_migration_adds_the_snippets_column_to_an_old_database(tmp_path):
    from sqlalchemy import create_engine, text
    from sqlalchemy.orm import sessionmaker

    from backend.database.migrations import run_migrations
    from backend.database.models import Base
    from backend.services import settings as settings_service

    engine = create_engine(f"sqlite:///{tmp_path / 'old.db'}")
    Base.metadata.create_all(engine)
    with engine.connect() as conn:
        conn.execute(text("ALTER TABLE capture_settings DROP COLUMN snippets"))
        conn.execute(
            text(
                "INSERT INTO capture_settings (id, stt_model, language, auto_refine, llm_model, "
                "smart_cleanup, self_correction, preserve_technical, allow_auto_paste, hotkey_enabled, "
                "chord_push_to_talk_keys, chord_toggle_to_talk_keys, vocabulary, replacements) "
                "VALUES (1, 'turbo', 'pt', 1, '0.6B', 1, 1, 1, 1, 0, '[\"AltGr\"]', '[\"AltGr\",\"Space\"]', "
                "'[]', '[]')"
            )
        )
        conn.commit()

    run_migrations(engine)
    run_migrations(engine)  # idempotent

    db = sessionmaker(bind=engine)()
    try:
        row = settings_service.get_capture_settings(db)
        assert row.snippets == []
        row = settings_service.update_capture_settings(db, {"snippets": SNIPPETS})
        assert row.snippets == SNIPPETS
    finally:
        db.close()
