"""
Tests for the custom vocabulary: storage and validation, the Whisper
``prompt``, replacement rules on the raw transcript, the refinement prompt
section, and replacement rules learned from a hand-corrected transcript.

HTTP calls go to the in-process ``FakeServer`` from
``test_cloud_dictation.py`` (no network, no keys).
"""

import json

import pytest

from backend import models
from backend.backends import reset_backends
from backend.backends.anthropic_backend import AnthropicLLMBackend
from backend.backends.openai_compat_stt_backend import OpenAICompatSTTBackend
from backend.services import refinement
from backend.services.transcribe import transcribe_with_vocabulary
from backend.services.vocabulary import (
    MAX_TERM_CHARS,
    MAX_VOCABULARY_TERMS,
    STT_PROMPT_MAX_CHARS,
    apply_replacements,
    build_stt_prompt,
    normalize_replacements,
    normalize_vocabulary,
    suggest_replacements,
)
from backend.tests.test_cloud_dictation import FakeServer, _claude_reply, _wav_bytes


@pytest.fixture(autouse=True)
def _clean_backends():
    reset_backends()
    yield
    reset_backends()


RULE = {"from": "Valois", "to": "Voloz"}


# ── Validation ────────────────────────────────────────────────────────────


def test_vocabulary_is_trimmed_deduplicated_and_capped():
    assert normalize_vocabulary([" Voloz ", "voloz", "", "  ", "Log-In", "Pascoal  Neto", "VOLOZ"]) == [
        "Voloz",
        "Log-In",
        "Pascoal Neto",
    ]
    assert normalize_vocabulary(None) == []
    with pytest.raises(ValueError, match="longer than"):
        normalize_vocabulary(["x" * (MAX_TERM_CHARS + 1)])
    with pytest.raises(ValueError, match="limit"):
        normalize_vocabulary([f"t{i}" for i in range(MAX_VOCABULARY_TERMS + 1)])


def test_replacements_are_trimmed_and_a_later_rule_for_the_same_phrase_wins():
    rules = normalize_replacements(
        [
            {"from": " Valois ", "to": "Valoá"},
            {"from": "", "to": "x"},
            {"from": "same", "to": "same"},
            {"from": "log  in", "to": "Log-In"},
            {"from": "VALOIS", "to": "Voloz"},
        ]
    )
    assert rules == [{"from": "log in", "to": "Log-In"}, {"from": "VALOIS", "to": "Voloz"}]


# ── Replacement rules ─────────────────────────────────────────────────────


def test_replacement_is_case_insensitive_and_whole_word():
    text = "O Valois disse: valois, VALOIS! Valoisa e Valoisé ficam; SuperValois também."
    assert apply_replacements(text, [RULE]) == (
        "O Voloz disse: Voloz, Voloz! Valoisa e Valoisé ficam; SuperValois também."
    )


def test_replacement_handles_portuguese_accents():
    rules = [{"from": "conceicao", "to": "Conceição"}, {"from": "você", "to": "o senhor"}]
    text = "A Conceicao falou com VOCÊ; conceicaozinha e vocês ficam."
    assert apply_replacements(text, rules) == "A Conceição falou com o senhor; conceicaozinha e vocês ficam."


def test_replacement_matches_phrases_across_whitespace_and_runs_once():
    rules = [
        {"from": "log in", "to": "Log-In"},
        {"from": "log", "to": "registro"},
        # Chaining would turn A into C; all rules run in one pass instead.
        {"from": "alfa", "to": "beta"},
        {"from": "beta", "to": "gama"},
    ]
    assert apply_replacements("a log\nin e o log; alfa beta", rules) == "a Log-In e o registro; beta gama"


def test_replacement_without_rules_or_text_is_a_no_op():
    assert apply_replacements("Valois", []) == "Valois"
    assert apply_replacements("Valois", None) == "Valois"
    assert apply_replacements("", [RULE]) == ""
    # A rule left blank in storage must not match everywhere.
    assert apply_replacements("Valois", [{"from": " ", "to": "x"}]) == "Valois"


# ── Whisper prompt ────────────────────────────────────────────────────────


def test_stt_prompt_joins_terms_and_drops_whole_terms_past_the_budget():
    assert build_stt_prompt(["Voloz", " Log-In ", "", "Pascoal"]) == "Voloz, Log-In, Pascoal"
    assert build_stt_prompt([]) is None
    assert build_stt_prompt(None) is None

    long_list = [f"Termo{i:03d}" for i in range(200)]
    prompt = build_stt_prompt(long_list)
    assert len(prompt) <= STT_PROMPT_MAX_CHARS
    assert prompt.startswith("Termo000, Termo001")
    assert all(part in long_list for part in prompt.split(", "))


@pytest.mark.asyncio
async def test_remote_stt_sends_the_prompt_field(tmp_path):
    audio = tmp_path / "clip.wav"
    audio.write_bytes(_wav_bytes())
    with FakeServer(lambda *_: (200, {"text": "Voloz"})) as server:
        backend = OpenAICompatSTTBackend(endpoint=server.base_url, model="whisper-large-v3-turbo")
        await transcribe_with_vocabulary(backend, str(audio), "pt", None, ["Voloz", "Log-In"])
        await transcribe_with_vocabulary(backend, str(audio), "pt", None, [])

    with_vocab, without_vocab = (r["raw"] for r in server.requests)
    assert b'name="prompt"\r\n\r\nVoloz, Log-In\r\n' in with_vocab
    assert b'name="prompt"' not in without_vocab


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("vocab", "reply", "expected"),
    [
        (["Voloz"], "Voloz", ""),
        (["Voloz"], "Voloz.", ""),
        (["Voloz", "Log-In"], " voloz, log-in. ", ""),
        (["Voloz"], "O Voloz", "O Voloz"),
        # A part of the prompt can't be told apart from real speech: kept.
        (["Voloz", "Log-In"], "Log-In", "Log-In"),
    ],
)
async def test_a_transcript_that_only_repeats_the_prompt_is_dropped(tmp_path, vocab, reply, expected):
    """Whisper returns its prompt on silence; that must not be pasted."""
    audio = tmp_path / "clip.wav"
    audio.write_bytes(_wav_bytes())
    with FakeServer(lambda *_: (200, {"text": reply})) as server:
        backend = OpenAICompatSTTBackend(endpoint=server.base_url, model="m")
        assert await transcribe_with_vocabulary(backend, str(audio), "pt", None, vocab) == expected


@pytest.mark.asyncio
async def test_local_stt_is_called_without_a_prompt():
    calls = []

    class LocalWhisper:
        model_size = "turbo"

        async def transcribe(self, audio_path, language=None, model_size=None):
            calls.append((audio_path, language, model_size))
            return "texto"

    out = await transcribe_with_vocabulary(LocalWhisper(), "a.wav", "pt", "turbo", ["Voloz"])
    assert out == "texto"
    assert calls == [("a.wav", "pt", "turbo")]


# ── Refinement prompt ─────────────────────────────────────────────────────


def test_refinement_prompt_lists_the_vocabulary():
    flags = refinement.RefinementFlags()
    plain = refinement.build_refinement_prompt(flags)
    assert refinement.build_refinement_prompt(flags, []) == plain
    assert refinement.build_refinement_prompt(flags, None) == plain

    with_vocab = refinement.build_refinement_prompt(flags, ["Voloz", " Log-In "])
    assert with_vocab.startswith(plain)
    assert "exactly as written here, including capitalization and hyphens: Voloz, Log-In" in with_vocab


def test_refinement_pass_through_prompt_ignores_the_vocabulary():
    off = refinement.RefinementFlags(smart_cleanup=False, self_correction=False, preserve_technical=False)
    assert refinement.build_refinement_prompt(off, ["Voloz"]) == refinement.build_refinement_prompt(off)


@pytest.mark.asyncio
async def test_refine_transcript_sends_the_vocabulary_to_claude():
    from backend.backends import _OPENAI_COMPAT_CACHE_KEY, _llm_backends, set_llm_config

    with FakeServer(lambda *_: _claude_reply("O Voloz chegou.")) as server:
        set_llm_config(server.base_url, "claude-haiku-5-5", "k")
        _llm_backends[_OPENAI_COMPAT_CACHE_KEY] = AnthropicLLMBackend(
            endpoint=server.base_url, model="claude-haiku-5-5", api_key="k"
        )
        text, _ = await refinement.refine_transcript(
            "o valois chegou", refinement.RefinementFlags(), vocabulary=["Voloz"]
        )
    assert text == "O Voloz chegou."
    body = json.loads(server.requests[0]["raw"])
    system = body["system"] if isinstance(body["system"], str) else body["system"][0]["text"]
    assert "hyphens: Voloz" in system


# ── Suggestions from a corrected transcript ───────────────────────────────


def test_suggest_replacements_finds_a_misheard_name():
    before = "Hoje o Valois abriu o app e falou com o Pascoal."
    after = "Hoje o Voloz abriu o app e falou com o Pascoal."
    assert suggest_replacements(before, after) == [RULE]


def test_suggest_replacements_handles_multi_word_sides():
    before = "faça o log in no sistema da log in"
    after = "faça o Log-In no sistema da Log-In"
    assert suggest_replacements(before, after) == [{"from": "log in", "to": "Log-In"}]

    assert suggest_replacements("fale com João da Silva hoje", "fale com Jon Silva hoje") == [
        {"from": "João da", "to": "Jon"}
    ]


def test_suggest_replacements_ignores_case_punctuation_and_insertions():
    before = "o voloz chegou, e o pascoal saiu"
    assert suggest_replacements(before, "O Voloz chegou. E o Pascoal saiu!") == []
    assert suggest_replacements(before, "o voloz chegou cedo, e o pascoal saiu") == []
    assert suggest_replacements(before, "o voloz, e o pascoal saiu") == []


def test_suggest_replacements_ignores_long_rewrites():
    before = "um dois três quatro cinco seis sete oito nove dez"
    # Four words replaced in one block: a rewrite, not a mishearing.
    assert suggest_replacements(before, "um dois A B C D sete oito nove dez") == []
    # Most of the text changed.
    assert suggest_replacements(before, "completamente outra frase sobre outro assunto qualquer") == []
    assert suggest_replacements("", "Voloz") == []


# ── Storage, migration and routes ─────────────────────────────────────────


def test_migration_adds_vocabulary_columns_to_an_old_database(tmp_path):
    from sqlalchemy import create_engine, text
    from sqlalchemy.orm import sessionmaker

    from backend.database.migrations import run_migrations
    from backend.database.models import Base
    from backend.services import settings as settings_service

    engine = create_engine(f"sqlite:///{tmp_path / 'old.db'}")
    Base.metadata.create_all(engine)
    with engine.connect() as conn:
        # A database from before this feature: settings row, no vocabulary columns.
        conn.execute(text("ALTER TABLE capture_settings DROP COLUMN vocabulary"))
        conn.execute(text("ALTER TABLE capture_settings DROP COLUMN replacements"))
        conn.execute(
            text(
                "INSERT INTO capture_settings (id, stt_model, language, auto_refine, llm_model, "
                "smart_cleanup, self_correction, preserve_technical, allow_auto_paste, hotkey_enabled, "
                "chord_push_to_talk_keys, chord_toggle_to_talk_keys) "
                "VALUES (1, 'turbo', 'pt', 1, '0.6B', 1, 1, 1, 1, 0, '[\"AltGr\"]', '[\"AltGr\",\"Space\"]')"
            )
        )
        conn.commit()

    run_migrations(engine)
    run_migrations(engine)  # idempotent

    db = sessionmaker(bind=engine)()
    try:
        row = settings_service.get_capture_settings(db)
        assert row.language == "pt"
        assert row.vocabulary == []
        assert row.replacements == []
        response = models.CaptureSettingsResponse.model_validate(row)
        assert response.vocabulary == [] and response.replacements == []

        row = settings_service.update_capture_settings(db, {"vocabulary": ["Voloz"], "replacements": [RULE]})
        assert (row.vocabulary, row.replacements) == (["Voloz"], [RULE])
    finally:
        db.close()


@pytest.fixture
def api(tmp_path, monkeypatch):
    """Settings + captures routers on a fresh SQLite file and data dir."""
    from fastapi import FastAPI
    from sqlalchemy import create_engine
    from sqlalchemy.orm import sessionmaker
    from starlette.testclient import TestClient

    from backend import config
    from backend.database import get_db
    from backend.database.models import Base
    from backend.routes import captures as captures_routes, settings as settings_routes

    monkeypatch.setattr(config, "_data_dir", tmp_path)
    engine = create_engine(
        f"sqlite:///{tmp_path / 'api.db'}", connect_args={"check_same_thread": False}
    )
    Base.metadata.create_all(engine)
    session_factory = sessionmaker(bind=engine)

    def _db():
        db = session_factory()
        try:
            yield db
        finally:
            db.close()

    app = FastAPI()
    app.include_router(settings_routes.router)
    app.include_router(captures_routes.router)
    app.dependency_overrides[get_db] = _db
    return TestClient(app)


def test_settings_round_trip_normalizes_vocabulary_and_rules(api):
    assert api.get("/settings/captures").json()["vocabulary"] == []

    res = api.put(
        "/settings/captures",
        json={
            "vocabulary": [" Voloz ", "voloz", "", "Log-In"],
            "replacements": [{"from": " Valois ", "to": "Voloz"}, {"from": "x", "to": "x"}],
        },
    )
    assert res.status_code == 200, res.text
    stored = api.get("/settings/captures").json()
    assert stored["vocabulary"] == ["Voloz", "Log-In"]
    assert stored["replacements"] == [RULE]

    # Other settings writes leave both lists alone.
    api.put("/settings/captures", json={"language": "pt"})
    assert api.get("/settings/captures").json()["vocabulary"] == ["Voloz", "Log-In"]

    assert api.put("/settings/captures", json={"replacements": [{"from": "a"}]}).status_code == 422
    too_many = [f"t{i}" for i in range(MAX_VOCABULARY_TERMS + 1)]
    assert api.put("/settings/captures", json={"vocabulary": too_many}).status_code == 422
    assert api.get("/settings/captures").json()["vocabulary"] == ["Voloz", "Log-In"]


def test_capture_applies_prompt_and_rules_then_learns_from_a_correction(api):
    with FakeServer(lambda *_: (200, {"text": "Falei com o Valois e o pascoal hoje."})) as server:
        api.put(
            "/settings/captures",
            json={
                "custom_stt_endpoint": server.base_url,
                "custom_stt_model": "whisper-large-v3-turbo",
                "vocabulary": ["Voloz", "Pascoal"],
                "replacements": [RULE],
            },
        )
        res = api.post(
            "/captures",
            files={"file": ("clip.wav", _wav_bytes(), "audio/wav")},
            data={"source": "dictation", "language": "pt"},
        )
        assert res.status_code == 200, res.text
        capture = res.json()
        assert b'name="prompt"\r\n\r\nVoloz, Pascoal\r\n' in server.requests[-1]["raw"]
        # The rule ran before the transcript was stored.
        assert capture["transcript_raw"] == "Falei com o Voloz e o pascoal hoje."

        res = api.post(f"/captures/{capture['id']}/retranscribe", json={})
        assert res.status_code == 200, res.text
        assert res.json()["transcript_raw"] == "Falei com o Voloz e o pascoal hoje."

    # The user corrects the raw text: "pascoal" → "Pascoal" is case-only,
    # "hoje" → "ontem" is a real substitution, Valois is already a rule.
    res = api.put(
        f"/captures/{capture['id']}/transcript",
        json={"text": "Falei com o Voloz e o Pascoal ontem.", "base": "raw"},
    )
    assert res.status_code == 200, res.text
    body = res.json()
    assert body["transcript_refined"] == "Falei com o Voloz e o Pascoal ontem."
    assert body["transcript_raw"] == "Falei com o Voloz e o pascoal hoje."
    assert body["llm_model"] == "manual"
    assert body["suggestions"] == [{"from": "hoje", "to": "ontem"}]

    # Learning the suggestion; a second identical correction suggests nothing.
    api.put("/settings/captures", json={"replacements": [RULE, {"from": "hoje", "to": "ontem"}]})
    res = api.put(
        f"/captures/{capture['id']}/transcript",
        json={"text": "Falei com o Voloz e o Pascoal ontem.", "base": "raw"},
    )
    assert res.json()["suggestions"] == []

    assert api.put("/captures/nope/transcript", json={"text": "x"}).status_code == 404
