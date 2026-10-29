"""
Tests for the one-key Groq setup (``POST /settings/captures/groq``) and for
refinement hitting a provider rate limit (HTTP 429).

Groq is stood in for by the in-process ``FakeServer`` from
``test_cloud_dictation.py`` (no network, no keys).
"""

import pytest

from backend import backends
from backend.backends import LLMRateLimitError, reset_backends
from backend.backends.anthropic_backend import AnthropicLLMBackend
from backend.backends.openai_compat_backend import OpenAICompatLLMBackend
from backend.services import quick_setup
from backend.tests.test_cloud_dictation import FakeServer, _wav_bytes
from backend.tests.test_vocabulary import api  # noqa: F401 — fixture


@pytest.fixture(autouse=True)
def _clean_backends():
    reset_backends()
    yield
    reset_backends()


def _models(*ids):
    return 200, {"object": "list", "data": [{"id": i, "object": "model"} for i in ids]}


BOTH = (quick_setup.GROQ_STT_MODEL, quick_setup.GROQ_LLM_MODEL, "llama-x")


def _not_configured(api):
    stored = api.get("/settings/captures").json()
    assert stored["custom_stt_endpoint"] is None and stored["custom_llm_endpoint"] is None
    assert not stored["custom_stt_api_key_configured"]
    assert not stored["custom_llm_api_key_configured"]


def _setup(api, groq_key, anthropic_key=None):
    body = {"groq_api_key": groq_key}
    if anthropic_key is not None:
        body["anthropic_api_key"] = anthropic_key
    return api.post("/settings/captures/quick-setup", json=body)


def test_a_groq_key_alone_configures_transcription_and_refinement(api, monkeypatch):
    with FakeServer(lambda *_: _models(*BOTH)) as groq:
        monkeypatch.setattr(quick_setup, "GROQ_ENDPOINT", groq.base_url)
        res = _setup(api, "  groq-test  ", anthropic_key="  ")
        assert res.status_code == 200, res.text
        request = groq.requests[-1]

    assert request["method"] == "GET" and request["path"] == "/v1/models"
    assert request["headers"]["authorization"] == "Bearer groq-test"

    stored = api.get("/settings/captures").json()
    assert stored["custom_stt_endpoint"] == stored["custom_llm_endpoint"] == groq.base_url
    assert stored["custom_stt_model"] == "whisper-large-v3-turbo"
    assert stored["custom_llm_model"] == quick_setup.GROQ_LLM_MODEL
    assert stored["custom_stt_api_key_configured"] and stored["custom_llm_api_key_configured"]
    assert "groq-test" not in str(stored)

    # The running backends switched over, with the same key for both.
    llm = backends.get_llm_backend()
    assert isinstance(llm, OpenAICompatLLMBackend)
    assert (llm.model, llm.api_key) == (quick_setup.GROQ_LLM_MODEL, "groq-test")
    assert backends.get_stt_backend().api_key == "groq-test"

    readiness = api.get("/capture/readiness").json()
    assert readiness["stt"]["ready"] and readiness["llm"]["ready"]


def test_an_anthropic_key_moves_refinement_to_claude(api, monkeypatch):
    with (
        FakeServer(lambda *_: _models(*BOTH)) as groq,
        FakeServer(lambda *_: (200, {"type": "model", "id": quick_setup.ANTHROPIC_LLM_MODEL})) as claude,
    ):
        monkeypatch.setattr(quick_setup, "GROQ_ENDPOINT", groq.base_url)
        monkeypatch.setattr(quick_setup, "ANTHROPIC_ENDPOINT", claude.base_url)
        res = _setup(api, "groq-test", "sk-test-claude")
        assert res.status_code == 200, res.text
        request = claude.requests[-1]

    assert request["path"] == f"/v1/models/{quick_setup.ANTHROPIC_LLM_MODEL}"
    assert request["headers"]["x-api-key"] == "sk-test-claude"
    assert request["headers"]["anthropic-version"]

    stored = api.get("/settings/captures").json()
    assert stored["custom_stt_endpoint"] == groq.base_url
    assert stored["custom_llm_endpoint"] == claude.base_url
    assert stored["custom_llm_model"] == "claude-haiku-5-5"
    assert backends.get_stt_backend().api_key == "groq-test"
    assert backends.get_llm_backend().api_key == "sk-test-claude"


@pytest.mark.parametrize(
    "reply, status, message",
    [
        (
            (401, {"error": {"message": "Invalid API Key", "code": "invalid_api_key"}}),
            400,
            "Groq rejected",
        ),
        (_models(quick_setup.GROQ_STT_MODEL), 502, quick_setup.GROQ_LLM_MODEL),
        ((500, {"error": "boom"}), 502, "HTTP 500"),
    ],
)
def test_a_failed_groq_validation_stores_nothing(api, monkeypatch, reply, status, message):
    with FakeServer(lambda *_: reply) as groq:
        monkeypatch.setattr(quick_setup, "GROQ_ENDPOINT", groq.base_url)
        res = _setup(api, "groq-bad")
    assert res.status_code == status, res.text
    assert message in res.json()["detail"]["message"]
    assert res.json()["detail"]["field"] == "groq"
    _not_configured(api)


@pytest.mark.parametrize(
    "reply, status, message",
    [
        ((401, {"type": "error", "error": {"type": "authentication_error"}}), 400, "Anthropic rejected"),
        ((404, {"type": "error", "error": {"type": "not_found_error"}}), 502, "claude-haiku-5-5"),
    ],
)
def test_a_bad_anthropic_key_stores_nothing_not_even_groq(api, monkeypatch, reply, status, message):
    with (
        FakeServer(lambda *_: _models(*BOTH)) as groq,
        FakeServer(lambda *_: reply) as claude,
    ):
        monkeypatch.setattr(quick_setup, "GROQ_ENDPOINT", groq.base_url)
        monkeypatch.setattr(quick_setup, "ANTHROPIC_ENDPOINT", claude.base_url)
        res = _setup(api, "groq-test", "sk-bad")
    assert res.status_code == status, res.text
    assert message in res.json()["detail"]["message"]
    assert res.json()["detail"]["field"] == "anthropic"
    _not_configured(api)


def test_no_network_is_a_clear_error(api, monkeypatch):
    # Nothing listens on port 9 of localhost.
    monkeypatch.setattr(quick_setup, "GROQ_ENDPOINT", "http://127.0.0.1:9/openai/v1")
    res = _setup(api, "groq-x")
    assert res.status_code == 503, res.text
    assert "Could not reach 127.0.0.1" in res.json()["detail"]["message"]
    _not_configured(api)


def test_a_blank_key_is_rejected_before_any_call(api):
    assert _setup(api, "").status_code == 422
    res = _setup(api, "   ")
    assert res.status_code == 400
    _not_configured(api)


# ── Rate limits ───────────────────────────────────────────────────────────

RATE_LIMITED = (
    429,
    {"error": {"message": "Rate limit reached for model qwen/qwen3.8-27b", "code": "rate_limit_exceeded"}},
)


async def test_a_429_from_an_openai_compatible_endpoint_is_a_rate_limit_error():
    with FakeServer(lambda *_: RATE_LIMITED) as server:
        backend = OpenAICompatLLMBackend(endpoint=server.base_url, model="m", api_key="k")
        with pytest.raises(LLMRateLimitError, match="HTTP 429"):
            await backend.generate(prompt="x")


async def test_a_429_from_claude_is_a_rate_limit_error():
    error = {"type": "error", "error": {"type": "rate_limit_error", "message": "slow down"}}
    with FakeServer(lambda *_: (429, error)) as server:
        backend = AnthropicLLMBackend(endpoint=server.base_url, model="claude-haiku-5-5", api_key="k")
        backend._client = backend._client.with_options(max_retries=0)
        with pytest.raises(LLMRateLimitError, match="HTTP 429"):
            await backend.generate(prompt="x")


def test_a_rate_limited_refine_answers_429_and_keeps_the_raw_transcript(api):
    with (
        FakeServer(lambda *_: (200, {"text": "Bom dia a todos."})) as stt,
        FakeServer(lambda *_: RATE_LIMITED) as llm,
    ):
        api.put(
            "/settings/captures",
            json={
                "custom_stt_endpoint": stt.base_url,
                "custom_stt_model": "whisper-large-v3-turbo",
                "custom_llm_endpoint": llm.base_url,
                "custom_llm_model": "qwen/qwen3.8-27b",
            },
        )
        capture = api.post(
            "/captures",
            files={"file": ("clip.wav", _wav_bytes(), "audio/wav")},
            data={"source": "dictation"},
        ).json()
        res = api.post(f"/captures/{capture['id']}/refine", json={})

    assert res.status_code == 429
    assert res.json()["detail"] == "Refinement skipped: the LLM provider's rate limit was reached"
    stored = api.get(f"/captures/{capture['id']}").json()
    assert stored["transcript_raw"] == "Bom dia a todos."
    assert stored["transcript_refined"] is None
