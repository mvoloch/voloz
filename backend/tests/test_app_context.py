"""
Tests for the target-app context: the app → writing-style table, the style
section of the refinement prompt, the target app stored on captures, and
voice commands (the selected text rewritten by a spoken instruction).

HTTP calls go to the in-process ``FakeServer`` from
``test_cloud_dictation.py`` (no network, no keys).

Usage:
    python -m pytest backend/tests/test_app_context.py -v
"""

import asyncio
import json

import pytest

from backend.backends import reset_backends
from backend.backends.anthropic_backend import AnthropicLLMBackend
from backend.services import llm as llm_service
from backend.services import refinement, voice_commands
from backend.services.app_context import (
    CHAT,
    CODE,
    EMAIL,
    finish_for_style,
    style_for_app,
    style_instruction,
)
from backend.tests.test_cloud_dictation import FakeServer, _wav_bytes
from backend.tests.test_vocabulary import api  # noqa: F401 — fixture


@pytest.fixture(autouse=True)
def _clean_backends():
    reset_backends()
    yield
    reset_backends()


# ── App → style table ─────────────────────────────────────────────────────


@pytest.mark.parametrize(
    ("app", "title", "style"),
    [
        ("OUTLOOK.EXE", None, EMAIL),
        ("olk.exe", "Caixa de Entrada", EMAIL),
        ("com.apple.mail", None, EMAIL),
        ("WhatsApp.exe", None, CHAT),
        ("slack.exe", "general - Log-In", CHAT),
        ("ms-teams.exe", None, CHAT),
        ("Telegram.exe", None, CHAT),
        ("Discord.exe", None, CHAT),
        ("Code.exe", "refinement.py - voloz", CODE),
        ("Cursor.exe", None, CODE),
        ("pycharm64.exe", None, CODE),
        ("WindowsTerminal.exe", None, CODE),
        ("com.jetbrains.intellij", None, CODE),
        # Browsers: the tab title names the web app.
        ("chrome.exe", "Inbox (3) - marcus@gmail.com - Gmail", EMAIL),
        ("msedge.exe", "Mail - Marcus Voloch - Outlook", EMAIL),
        ("chrome.exe", "(2) WhatsApp", CHAT),
        ("firefox.exe", "Chat | Microsoft Teams", CHAT),
        ("chrome.exe", "Google Docs", None),
        ("chrome.exe", None, None),
        # Unknown or missing.
        ("notepad.exe", "Untitled - Notepad", None),
        ("WINWORD.EXE", None, None),
        ("", None, None),
        (None, "Gmail", None),
    ],
)
def test_style_for_app(app, title, style):
    assert style_for_app(app, title) == style


@pytest.mark.parametrize(
    ("text", "style", "expected"),
    [
        ("Beleza, valeu.", CHAT, "Beleza, valeu"),
        ("Chego às oito.", CHAT, "Chego às oito"),
        # More than one sentence, a question, an ellipsis: unchanged.
        ("Oi, tudo bem? Chego às oito.", CHAT, "Oi, tudo bem? Chego às oito."),
        ("Ok. Valeu.", CHAT, "Ok. Valeu."),
        ("Vamos?", CHAT, "Vamos?"),
        ("Sei lá...", CHAT, "Sei lá..."),
        ("Linha um.\nLinha dois.", CHAT, "Linha um.\nLinha dois."),
        ("x" * 130 + ".", CHAT, "x" * 130 + "."),
        # Other styles and no style keep the period.
        ("Beleza, valeu.", EMAIL, "Beleza, valeu."),
        ("Beleza, valeu.", None, "Beleza, valeu."),
    ],
)
def test_finish_for_style_drops_the_period_of_a_short_chat_message(text, style, expected):
    assert finish_for_style(text, style) == expected


def test_every_style_has_an_instruction():
    for style in (EMAIL, CHAT, CODE):
        assert style_instruction(style)
    assert style_instruction(None) is None
    assert style_instruction("spreadsheet") is None


# ── Refinement prompt ─────────────────────────────────────────────────────


def test_unknown_app_keeps_the_prompt_byte_identical():
    flags = refinement.RefinementFlags()
    base = refinement.build_refinement_prompt(flags, ["Voloz"])
    assert refinement.build_refinement_prompt(flags, ["Voloz"], None) == base
    assert refinement.build_refinement_prompt(flags, ["Voloz"], style_for_app("notepad.exe")) == base
    assert refinement.build_refinement_prompt(flags) == refinement.build_refinement_prompt(flags, None, None)


def test_style_section_is_appended_last():
    flags = refinement.RefinementFlags()
    base = refinement.build_refinement_prompt(flags, ["Voloz"])
    for style in (EMAIL, CHAT, CODE):
        prompt = refinement.build_refinement_prompt(flags, ["Voloz"], style)
        assert prompt == f"{base}\n\n{style_instruction(style)}"


def test_pass_through_prompt_ignores_the_app():
    off = refinement.RefinementFlags(smart_cleanup=False, self_correction=False, preserve_technical=False)
    base = refinement.build_refinement_prompt(off)
    assert refinement.build_refinement_prompt(off, ["Voloz"], CHAT) == base
    assert "unchanged" in base


def _fake_backend(reply, claude=False):
    calls = {}

    async def generate(**kwargs):
        calls.update(kwargs)
        return reply

    if claude:
        backend = object.__new__(AnthropicLLMBackend)
    else:
        class Local:
            pass

        backend = Local()
    backend.generate = generate
    return backend, calls


def _patch_llm(monkeypatch, backend):
    monkeypatch.setattr(llm_service, "get_llm_model", lambda: backend)
    monkeypatch.setattr(llm_service, "resolve_model_size", lambda b, size: "remote-model")


def test_refine_transcript_sends_the_style(monkeypatch):
    backend, calls = _fake_backend("Oi, tudo bem.")
    _patch_llm(monkeypatch, backend)

    text, _ = asyncio.run(
        refinement.refine_transcript("oi tudo bem", refinement.RefinementFlags(), app_style=CHAT)
    )
    assert style_instruction(CHAT) in calls["system"]
    assert text == "Oi, tudo bem"

    text, _ = asyncio.run(refinement.refine_transcript("oi tudo bem", refinement.RefinementFlags()))
    assert style_instruction(CHAT) not in calls["system"]
    assert text == "Oi, tudo bem."


# ── Command prompt ────────────────────────────────────────────────────────


def test_command_message_with_and_without_selection():
    with_sel = voice_commands.build_command_message(" make it formal ", "hey send me the file")
    assert with_sel == (
        "<instruction>make it formal</instruction>\n\n"
        "<selection>\nhey send me the file\n</selection>"
    )
    draft = voice_commands.build_command_message("responde dizendo que aceito", "  ")
    assert draft == "<instruction>responde dizendo que aceito</instruction>\n\nNo text is selected."


def test_command_prompt_adds_style_and_tag_instruction():
    plain = voice_commands.build_command_prompt()
    assert "<result>" not in plain
    tagged = voice_commands.build_command_prompt(EMAIL, tagged=True)
    assert tagged.startswith(plain)
    assert style_instruction(EMAIL) in tagged
    assert tagged.endswith("<result></result> tags and nothing outside them.")


def test_run_command_extracts_claude_tagged_result(monkeypatch):
    backend, calls = _fake_backend(
        "<result>Could you please send me the file?</result>\n\nNote: made it formal.", claude=True
    )
    _patch_llm(monkeypatch, backend)

    text, model = asyncio.run(voice_commands.run_command("make it formal", "send me the file"))
    assert (text, model) == ("Could you please send me the file?", "remote-model")
    assert "<result></result>" in calls["system"]
    assert "<selection>\nsend me the file\n</selection>" in calls["prompt"]


def test_run_command_local_backend_keeps_plain_output(monkeypatch):
    backend, calls = _fake_backend("  Aceito, obrigado!  ")
    _patch_llm(monkeypatch, backend)

    text, _ = asyncio.run(voice_commands.run_command("responde dizendo que aceito"))
    assert text == "Aceito, obrigado!"
    assert "<result>" not in calls["system"]
    assert calls["prompt"].endswith("No text is selected.")


def test_run_command_rejects_an_empty_instruction(monkeypatch):
    backend, _ = _fake_backend("anything")
    _patch_llm(monkeypatch, backend)
    with pytest.raises(ValueError):
        asyncio.run(voice_commands.run_command("   ", "selected"))


# ── Migration ─────────────────────────────────────────────────────────────


def test_migration_adds_app_context_columns_to_an_old_database(tmp_path):
    from sqlalchemy import create_engine, text
    from sqlalchemy.orm import sessionmaker

    from backend import models
    from backend.database import Capture
    from backend.database.migrations import run_migrations
    from backend.database.models import Base
    from backend.services import settings as settings_service

    engine = create_engine(f"sqlite:///{tmp_path / 'old.db'}")
    Base.metadata.create_all(engine)
    with engine.connect() as conn:
        # A database from before this feature: settings row and a capture,
        # none of the new columns.
        for table, column in (
            ("capture_settings", "chord_command_keys"),
            ("capture_settings", "adapt_to_app"),
            ("captures", "target_app"),
            ("captures", "target_title"),
        ):
            conn.execute(text(f"ALTER TABLE {table} DROP COLUMN {column}"))
        conn.execute(
            text(
                "INSERT INTO capture_settings (id, stt_model, language, auto_refine, llm_model, "
                "smart_cleanup, self_correction, preserve_technical, allow_auto_paste, hotkey_enabled, "
                "chord_push_to_talk_keys, chord_toggle_to_talk_keys, vocabulary, replacements) "
                "VALUES (1, 'turbo', 'pt', 1, '0.6B', 1, 1, 1, 1, 0, '[\"AltGr\"]', "
                "'[\"AltGr\",\"Space\"]', '[]', '[]')"
            )
        )
        conn.execute(
            text(
                "INSERT INTO captures (id, audio_path, source, transcript_raw) "
                "VALUES ('old', 'captures/old.wav', 'dictation', 'oi')"
            )
        )
        conn.commit()

    run_migrations(engine)
    run_migrations(engine)  # idempotent

    db = sessionmaker(bind=engine)()
    try:
        row = settings_service.get_capture_settings(db)
        assert row.language == "pt"
        assert row.adapt_to_app is True
        assert row.chord_command_keys == ["ControlLeft", "AltLeft", "MetaLeft"]
        response = models.CaptureSettingsResponse.model_validate(row)
        assert response.adapt_to_app is True

        old = db.query(Capture).filter(Capture.id == "old").one()
        assert (old.target_app, old.target_title) == (None, None)
    finally:
        db.close()


# ── Routes ────────────────────────────────────────────────────────────────


def _chat_reply(text):
    return 200, {
        "id": "chatcmpl-test",
        "object": "chat.completion",
        "choices": [{"index": 0, "message": {"role": "assistant", "content": text}, "finish_reason": "stop"}],
    }


class _Script:
    """STT returns ``heard``; chat completions return ``reply``."""

    def __init__(self, heard, reply):
        self.heard = heard
        self.reply = reply

    def __call__(self, path, _headers, _raw):
        if path.endswith("/audio/transcriptions"):
            return 200, {"text": self.heard}
        return _chat_reply(self.reply)


def _configure(api, server, **extra):
    res = api.put(
        "/settings/captures",
        json={
            "custom_stt_endpoint": server.base_url,
            "custom_stt_model": "whisper-large-v3-turbo",
            "custom_llm_endpoint": server.base_url,
            "custom_llm_model": "qwen/qwen3.8-27b",
            **extra,
        },
    )
    assert res.status_code == 200, res.text


def _post_capture(api, source="dictation", **target):
    res = api.post(
        "/captures",
        files={"file": ("clip.wav", _wav_bytes(), "audio/wav")},
        data={"source": source, "language": "pt", **target},
    )
    assert res.status_code == 200, res.text
    return res.json()


def _system_prompt(server):
    body = json.loads(server.requests[-1]["raw"])
    return next(m["content"] for m in body["messages"] if m["role"] == "system")


def test_capture_stores_target_app_and_refines_in_its_style(api):  # noqa: F811
    script = _Script("oi tudo bem", "Oi, tudo bem")
    with FakeServer(script) as server:
        _configure(api, server)
        capture = _post_capture(api, target_app="WhatsApp.exe", target_title="  WhatsApp  ")
        assert (capture["target_app"], capture["target_title"]) == ("WhatsApp.exe", "WhatsApp")
        assert api.get(f"/captures/{capture['id']}").json()["target_app"] == "WhatsApp.exe"

        assert api.post(f"/captures/{capture['id']}/refine", json={}).status_code == 200
        assert style_instruction(CHAT) in _system_prompt(server)

        # Toggle off: the same capture refines with the plain prompt.
        api.put("/settings/captures", json={"adapt_to_app": False})
        assert api.post(f"/captures/{capture['id']}/refine", json={}).status_code == 200
        off_prompt = _system_prompt(server)
        assert style_instruction(CHAT) not in off_prompt

        # No target app: identical to the toggle-off prompt.
        api.put("/settings/captures", json={"adapt_to_app": True})
        plain = _post_capture(api)
        assert plain["target_app"] is None
        assert api.post(f"/captures/{plain['id']}/refine", json={}).status_code == 200
        assert _system_prompt(server) == off_prompt


def test_command_rewrites_the_selection_and_drafts_without_one(api):  # noqa: F811
    script = _Script("deixa mais formal", "Prezado, segue o arquivo.")
    with FakeServer(script) as server:
        _configure(api, server)
        capture = _post_capture(api, source="command", target_app="OUTLOOK.EXE")
        assert capture["source"] == "command"

        res = api.post(f"/captures/{capture['id']}/command", json={"selection": "oi, segue o arquivo"})
        assert res.status_code == 200, res.text
        body = res.json()
        assert body["transcript_raw"] == "deixa mais formal"
        assert body["transcript_refined"] == "Prezado, segue o arquivo."
        assert body["llm_model"] == "qwen/qwen3.8-27b"
        sent = json.loads(server.requests[-1]["raw"])
        user = sent["messages"][-1]["content"]
        assert user == (
            "<instruction>deixa mais formal</instruction>\n\n"
            "<selection>\noi, segue o arquivo\n</selection>"
        )
        assert style_instruction(EMAIL) in _system_prompt(server)

        # Empty selection: a draft from the instruction alone.
        script.reply = "Aceito, obrigado!"
        res = api.post(
            f"/captures/{capture['id']}/command",
            json={"selection": "", "instruction": "responde dizendo que aceito"},
        )
        assert res.status_code == 200, res.text
        assert res.json()["transcript_refined"] == "Aceito, obrigado!"
        assert json.loads(server.requests[-1]["raw"])["messages"][-1]["content"].endswith(
            "No text is selected."
        )

        # Nothing heard.
        script.heard = ""
        silent = _post_capture(api, source="command")
        assert api.post(f"/captures/{silent['id']}/command", json={}).status_code == 400

    assert api.post("/captures/nope/command", json={}).status_code == 404


def test_command_without_a_model_is_a_clear_error(api, monkeypatch):  # noqa: F811
    from backend.routes import captures as captures_routes

    # No custom endpoint and no local model downloaded.
    monkeypatch.setattr(captures_routes, "is_model_cached", lambda *_: False)
    with FakeServer(_Script("deixa mais formal", "unused")) as server:
        api.put(
            "/settings/captures",
            json={"custom_stt_endpoint": server.base_url, "custom_stt_model": "whisper-large-v3-turbo"},
        )
        capture = _post_capture(api, source="command")
        res = api.post(f"/captures/{capture['id']}/command", json={"selection": "x"})
    assert res.status_code == 409
    assert res.json()["detail"] == captures_routes.NO_LLM_FOR_COMMAND


def test_settings_round_trip_app_context(api):  # noqa: F811
    stored = api.get("/settings/captures").json()
    assert stored["adapt_to_app"] is True
    assert stored["chord_command_keys"] == ["ControlLeft", "AltLeft", "MetaLeft"]

    res = api.put("/settings/captures", json={"adapt_to_app": False, "chord_command_keys": ["F9"]})
    assert res.status_code == 200, res.text
    stored = api.get("/settings/captures").json()
    assert (stored["adapt_to_app"], stored["chord_command_keys"]) == (False, ["F9"])
    assert api.put("/settings/captures", json={"chord_command_keys": []}).status_code == 422
