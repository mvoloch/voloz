"""
Saved API keys, the key/host check, and backup providers for dictation.

Settings tests run on a throwaway SQLite file; provider tests on in-process
fake OpenAI-compatible servers (see ``test_cloud_dictation.FakeServer``).
"""

import json

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from backend import backends as backends_module
from backend import models
from backend.backends import (
    LLMRateLimitError,
    ProviderError,
    get_fallback_backends,
    reset_backends,
    set_llm_config,
    set_stt_config,
)
from backend.database.models import Base
from backend.services import providers
from backend.services import settings as settings_service
from backend.services.refinement import RefinementFlags, refine_transcript
from backend.services.transcribe import transcribe_with_fallback
from backend.tests.test_cloud_dictation import FakeServer, _wav_bytes

GROQ = "https://api.groq.com/openai/v1"
OPENAI = "https://api.openai.com/v1"
ANTHROPIC = "https://api.anthropic.com/v1"


@pytest.fixture(autouse=True)
def _clean():
    reset_backends()
    providers.reset_cooldowns()
    yield
    reset_backends()
    providers.reset_cooldowns()


@pytest.fixture
def db(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'settings.db'}")
    Base.metadata.create_all(engine)
    session = sessionmaker(bind=engine)()
    yield session
    session.close()


update = settings_service.update_capture_settings


def _response(row) -> dict:
    return models.CaptureSettingsResponse.model_validate(row).model_dump()


# ── saved keys ───────────────────────────────────────────────────────────


def test_every_key_entered_is_kept_and_never_returned(db):
    update(db, {"custom_stt_endpoint": GROQ, "custom_stt_model": "whisper-large-v3",
                "custom_stt_api_key": "gsk_first_key_123456"})
    update(db, {"custom_llm_endpoint": OPENAI, "custom_llm_model": "gpt-6-luna",
                "custom_llm_api_key": "sk-proj-openai-key-7890"})
    # Clearing the field keeps the key stored.
    row = update(db, {"custom_llm_api_key": None})

    body = _response(row)
    assert [k["host"] for k in body["saved_api_keys"]] == ["api.groq.com", "api.openai.com"]
    assert body["saved_api_keys"][0]["hint"] == "gsk_fi…3456"
    assert body["custom_llm_api_key_configured"] is False
    assert body["custom_llm_api_key_id"] is None
    assert body["custom_stt_api_key_id"] == body["saved_api_keys"][0]["id"]
    text = json.dumps(body)
    assert "gsk_first_key_123456" not in text and "sk-proj-openai-key-7890" not in text


def test_picking_a_saved_key_uses_it_and_only_on_its_host(db):
    update(db, {"custom_llm_endpoint": OPENAI, "custom_llm_model": "gpt-6-luna",
                "custom_llm_api_key": "sk-proj-openai-key-7890"})
    row = update(db, {"custom_llm_api_key": None})
    key_id = _response(row)["saved_api_keys"][0]["id"]

    row = update(db, {"custom_llm_api_key_id": key_id})
    assert row.custom_llm_api_key == "sk-proj-openai-key-7890"

    update(db, {"custom_stt_endpoint": GROQ, "custom_stt_model": "whisper-large-v3"})
    with pytest.raises(settings_service.SettingsError, match="saved for api.openai.com"):
        update(db, {"custom_stt_api_key_id": key_id})
    with pytest.raises(settings_service.SettingsError, match="no longer exists"):
        update(db, {"custom_llm_api_key_id": "nope"})


def test_moving_an_endpoint_back_brings_its_key_back(db):
    update(db, {"custom_llm_endpoint": ANTHROPIC, "custom_llm_model": "claude-haiku-5-5",
                "custom_llm_api_key": "sk-ant-claude-key-0001"})
    row = update(db, {"custom_llm_endpoint": OPENAI, "custom_llm_model": "gpt-6-luna"})
    assert row.custom_llm_api_key is None  # nothing stored for OpenAI yet
    update(db, {"custom_llm_api_key": "sk-proj-openai-key-7890"})

    row = update(db, {"custom_llm_endpoint": ANTHROPIC})
    assert row.custom_llm_api_key == "sk-ant-claude-key-0001"
    row = update(db, {"custom_llm_endpoint": OPENAI})
    assert row.custom_llm_api_key == "sk-proj-openai-key-7890"


def test_forgetting_a_key_removes_it_and_unsets_it_where_used(db):
    row = update(db, {"custom_stt_endpoint": GROQ, "custom_stt_model": "whisper-large-v3",
                      "custom_stt_api_key": "gsk_first_key_123456"})
    key_id = _response(row)["saved_api_keys"][0]["id"]
    row = update(db, {"forget_api_key_id": key_id})
    assert row.custom_stt_api_key is None
    assert row.saved_api_keys == []


@pytest.mark.parametrize(
    ("endpoint", "key", "provider"),
    [
        (OPENAI, "sk-ant-claude-key-0001", "Anthropic"),
        (OPENAI, "gsk_first_key_123456", "Groq"),
        (GROQ, "sk-or-v1-openrouter-key", "OpenRouter"),
    ],
)
def test_a_key_of_another_provider_is_refused(db, endpoint, key, provider):
    update(db, {"custom_llm_endpoint": endpoint, "custom_llm_model": "m"})
    with pytest.raises(settings_service.SettingsError, match=f"looks like a {provider} key"):
        update(db, {"custom_llm_api_key": key})
    assert settings_service.get_capture_settings(db).custom_llm_api_key is None


def test_keys_from_before_saved_keys_join_the_list_on_start(db):
    row = settings_service.get_capture_settings(db)
    row.custom_stt_endpoint, row.custom_stt_model, row.custom_stt_api_key = GROQ, "w", "gsk_first_key_123456"
    db.commit()
    settings_service.bootstrap_llm_backend_config(db)
    row = settings_service.get_capture_settings(db)
    assert [k["key"] for k in row.saved_api_keys] == ["gsk_first_key_123456"]


def test_backups_get_the_stored_key_for_their_host(db):
    update(db, {"custom_llm_endpoint": OPENAI, "custom_llm_model": "gpt-6-luna",
                "custom_llm_api_key": "sk-proj-openai-key-7890"})
    update(db, {"custom_llm_endpoint": GROQ, "custom_llm_model": "qwen/qwen3.8-27b",
                "custom_llm_api_key": "gsk_first_key_123456"})
    row = update(db, {"llm_fallbacks": [{"endpoint": OPENAI, "model": "gpt-6-luna"},
                                        {"endpoint": GROQ, "model": "llama-x"}]})
    assert _response(row)["llm_fallbacks"][0] == {"endpoint": OPENAI, "model": "gpt-6-luna"}
    keys = [b.api_key for b in get_fallback_backends("llm")]
    assert keys == ["sk-proj-openai-key-7890", "gsk_first_key_123456"]


def test_a_backup_key_can_be_saved_on_its_own(db):
    update(db, {"custom_llm_endpoint": GROQ, "custom_llm_model": "qwen/qwen3.8-27b",
                "custom_llm_api_key": "gsk_first_key_123456"})
    update(db, {"llm_fallbacks": [{"endpoint": ANTHROPIC, "model": "claude-haiku-5-5"}]})
    # Claude backends keep the key inside the SDK client; check the config.
    assert [key for *_, key in backends_module._fallback_config["llm"]] == [None]

    row = update(db, {"save_api_key": {"endpoint": ANTHROPIC, "key": "sk-ant-claude-key-0001"}})
    assert [k["host"] for k in _response(row)["saved_api_keys"]] == ["api.groq.com", "api.anthropic.com"]
    assert row.custom_llm_api_key == "gsk_first_key_123456"
    assert [key for *_, key in backends_module._fallback_config["llm"]] == ["sk-ant-claude-key-0001"]
    with pytest.raises(settings_service.SettingsError, match="looks like a Groq key"):
        update(db, {"save_api_key": {"endpoint": ANTHROPIC, "key": "gsk_wrong_place_1234"}})


def test_backup_list_is_validated():
    with pytest.raises(ValueError):
        models.CaptureSettingsUpdate(llm_fallbacks=[{"endpoint": "ftp://x", "model": "m"}])
    with pytest.raises(ValueError):
        models.CaptureSettingsUpdate(llm_fallbacks=[{"endpoint": OPENAI, "model": "m"}] * 5)


# ── the fallback runner ──────────────────────────────────────────────────


class Remote:
    is_remote = True

    def __init__(self, model, outcome):
        self.endpoint, self.model, self.outcome, self.calls = f"https://{model}.test/v1", model, outcome, 0


async def _call(backend):
    backend.calls += 1
    if isinstance(backend.outcome, Exception):
        raise backend.outcome
    return backend.outcome


@pytest.mark.asyncio
async def test_rate_limit_moves_on_and_cools_the_provider_down(monkeypatch):
    primary = Remote("groq", LLMRateLimitError("429", status_code=429, retry_after=30))
    backup = Remote("openai", "ok")
    monkeypatch.setattr(providers, "get_fallback_backends", lambda kind: [backup])

    assert await providers.run_with_fallback("llm", primary, _call) == (backup, "ok")
    # Cooling down: the next dictation goes straight to the backup.
    assert await providers.run_with_fallback("llm", primary, _call) == (backup, "ok")
    assert (primary.calls, backup.calls) == (1, 2)


@pytest.mark.asyncio
async def test_all_failing_raises_the_last_error_and_other_errors_are_not_retried(monkeypatch):
    primary = Remote("groq", ProviderError("down", status_code=503))
    backup = Remote("openai", LLMRateLimitError("429", status_code=429))
    monkeypatch.setattr(providers, "get_fallback_backends", lambda kind: [backup])
    with pytest.raises(LLMRateLimitError):
        await providers.run_with_fallback("llm", primary, _call)

    primary = Remote("groq2", ValueError("empty answer"))
    with pytest.raises(ValueError):
        await providers.run_with_fallback("llm", primary, _call)
    assert backup.calls == 1


@pytest.mark.asyncio
async def test_local_backends_get_no_backups(monkeypatch):
    local = Remote("qwen", "ok")
    local.is_remote = False
    monkeypatch.setattr(providers, "get_fallback_backends", lambda kind: pytest.fail("asked"))
    assert await providers.run_with_fallback("llm", local, _call) == (local, "ok")


# ── end to end on fake servers ───────────────────────────────────────────


@pytest.mark.asyncio
async def test_refine_falls_back_and_records_the_backup_model():
    def chat(text):
        return lambda *_: (200, {"choices": [{"message": {"content": text}}]})

    with FakeServer(lambda *_: (503, {"error": "busy"})) as down, FakeServer(chat("Olá, mundo.")) as up:
        set_llm_config(down.base_url, "primary-model", "k1")
        from backend.backends import set_fallback_config

        set_fallback_config("llm", [(up.base_url, "backup-model", "k2")])
        text, label = await refine_transcript("olá mundo", RefinementFlags())
    assert (text, label) == ("Olá, mundo.", "backup-model")
    assert len(down.requests) == 1 and len(up.requests) == 1


@pytest.mark.asyncio
async def test_transcription_falls_back_on_a_rate_limit(tmp_path):
    audio = tmp_path / "clip.wav"
    audio.write_bytes(_wav_bytes())
    with FakeServer(lambda *_: (429, {"error": "slow down"})) as limited, FakeServer(
        lambda *_: (200, {"text": "oi"})
    ) as ok:
        set_stt_config(limited.base_url, "whisper-large-v3", "k1")
        from backend.backends import set_fallback_config

        set_fallback_config("stt", [(ok.base_url, "gpt-transcribe", "k2")])
        text, label = await transcribe_with_fallback(str(audio), "pt", None, [])
    assert (text, label) == ("oi", "gpt-transcribe")
