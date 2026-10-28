"""
Tests for cloud dictation: the custom OpenAI-compatible transcription
endpoint and the native Claude refinement backend.

Both run against tiny in-process HTTP servers (no network, no keys) that
record what Voicebox sends, in the same spirit as
``test_openai_compat_backend.py``.
"""

import io
import json
import threading
import wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from backend import models
from backend.backends import (
    get_llm_backend,
    get_stt_backend,
    reset_backends,
    set_llm_config,
    set_stt_config,
)
from backend.backends.anthropic_backend import AnthropicLLMBackend, is_anthropic_endpoint
from backend.backends.openai_compat_backend import OpenAICompatLLMBackend
from backend.backends.openai_compat_stt_backend import OpenAICompatSTTBackend
from backend.services.transcribe import is_remote, resolve_stt_model


class FakeServer:
    """In-process HTTP server that records raw requests.

    ``script`` is ``(path, headers, raw_body) -> (status, payload)``; dict
    payloads are JSON-encoded, ``bytes`` are sent verbatim.
    """

    def __init__(self, script):
        self.script = script
        self.requests: list[dict] = []
        server = self

        class Handler(BaseHTTPRequestHandler):
            def _handle(self, send_body: bool = True):
                length = int(self.headers.get("Content-Length") or 0)
                raw = self.rfile.read(length)
                # HTTP header names are case-insensitive; store them lower-cased.
                headers = {k.lower(): v for k, v in self.headers.items()}
                server.requests.append(
                    {"method": self.command, "path": self.path, "headers": headers, "raw": raw}
                )
                status, payload = server.script(self.path, self.headers, raw)
                data = payload if isinstance(payload, bytes) else json.dumps(payload).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                if send_body:
                    self.wfile.write(data)

            def do_POST(self):
                self._handle()

            def do_GET(self):
                self._handle()

            def do_HEAD(self):
                self._handle(send_body=False)

            def log_message(self, *args):
                pass

        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)

    @property
    def base_url(self) -> str:
        host, port = self.httpd.server_address[:2]
        return f"http://{host}:{port}/v1"

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *exc):
        self.httpd.shutdown()
        self.httpd.server_close()


def _wav_bytes(seconds: float = 0.5, rate: int = 16_000) -> bytes:
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(b"\x00\x00" * int(seconds * rate))
    return buf.getvalue()


def _claude_reply(text: str, stop_reason: str = "end_turn"):
    return 200, {
        "id": "msg_test",
        "type": "message",
        "role": "assistant",
        "model": "claude-haiku-5-5",
        "content": [{"type": "text", "text": text}],
        "stop_reason": stop_reason,
        "stop_sequence": None,
        "usage": {"input_tokens": 10, "output_tokens": 5},
    }


@pytest.fixture(autouse=True)
def _clean_backends():
    reset_backends()
    yield
    reset_backends()


# ── Remote STT backend ────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_transcribe_sends_multipart_with_bearer_model_and_language(tmp_path):
    audio = tmp_path / "clip.wav"
    audio.write_bytes(_wav_bytes())
    with FakeServer(lambda *_: (200, {"text": "  Olá, Pascoal.  "})) as server:
        backend = OpenAICompatSTTBackend(
            endpoint=server.base_url + "/", model="openai/whisper-large-v3-turbo", api_key="sk-or"
        )
        out = await backend.transcribe(str(audio), language="pt", model_size="turbo")

    assert out == "Olá, Pascoal."
    (req,) = server.requests
    assert req["path"] == "/v1/audio/transcriptions"
    assert req["headers"]["authorization"] == "Bearer sk-or"
    assert req["headers"]["content-type"].startswith("multipart/form-data")
    raw = req["raw"]
    assert b'name="model"\r\n\r\nopenai/whisper-large-v3-turbo' in raw
    assert b'name="language"\r\n\r\npt' in raw
    assert b'name="response_format"\r\n\r\njson' in raw
    assert b'filename="clip.wav"' in raw
    assert b"Content-Type: audio/wav" in raw


@pytest.mark.asyncio
async def test_transcribe_uploads_wav_dictations_at_16k_mono(tmp_path):
    """The webview records 48 kHz WAV: three times the bytes Whisper needs,
    and long dictations would pass hosted endpoints' 25 MB request limit."""
    import numpy as np
    import soundfile as sf

    audio = tmp_path / "dictation.wav"
    t = np.linspace(0, 1.0, 48_000, endpoint=False)
    tone = 0.3 * np.sin(2 * np.pi * 440 * t)
    sf.write(audio, np.stack([tone, tone], axis=1), 48_000, subtype="PCM_16")

    with FakeServer(lambda *_: (200, {"text": "ok"})) as server:
        backend = OpenAICompatSTTBackend(endpoint=server.base_url, model="m", api_key="k")
        assert await backend.transcribe(str(audio)) == "ok"

    raw = server.requests[0]["raw"]
    start = raw.index(b"RIFF")
    size = int.from_bytes(raw[start + 4 : start + 8], "little") + 8
    with wave.open(io.BytesIO(raw[start : start + size])) as w:
        assert (w.getframerate(), w.getnchannels(), w.getsampwidth()) == (16_000, 1, 2)
        assert abs(w.getnframes() / 16_000 - 1.0) < 0.01
    assert b'filename="dictation.wav"' in raw


@pytest.mark.asyncio
async def test_transcribe_reuses_one_pooled_client_across_dictations(tmp_path):
    audio = tmp_path / "clip.wav"
    audio.write_bytes(_wav_bytes())
    with FakeServer(lambda *_: (200, {"text": "ok"})) as server:
        backend = OpenAICompatSTTBackend(endpoint=server.base_url, model="m")
        await backend.transcribe(str(audio))
        first_client = backend._client
        await backend.transcribe(str(audio))
    assert first_client is not None
    assert backend._client is first_client, "each dictation must not pay a new TLS handshake"
    assert len(server.requests) == 2


@pytest.mark.asyncio
async def test_transcribe_without_language_or_key_omits_both(tmp_path):
    audio = tmp_path / "clip.wav"
    audio.write_bytes(_wav_bytes())
    with FakeServer(lambda *_: (200, {"text": "hi"})) as server:
        backend = OpenAICompatSTTBackend(endpoint=server.base_url, model="m", api_key="")
        assert await backend.transcribe(str(audio)) == "hi"
    req = server.requests[0]
    assert "authorization" not in req["headers"]
    assert b'name="language"' not in req["raw"]


@pytest.mark.asyncio
async def test_stt_http_error_names_the_endpoint(tmp_path):
    audio = tmp_path / "clip.wav"
    audio.write_bytes(_wav_bytes())
    with FakeServer(lambda *_: (402, {"error": {"message": "insufficient credits"}})) as server:
        backend = OpenAICompatSTTBackend(endpoint=server.base_url, model="m")
        with pytest.raises(RuntimeError) as excinfo:
            await backend.transcribe(str(audio))
    message = str(excinfo.value)
    assert "HTTP 402" in message
    assert server.base_url in message
    assert "insufficient credits" in message


@pytest.mark.asyncio
async def test_stt_unreachable_and_bad_bodies(tmp_path):
    audio = tmp_path / "clip.wav"
    audio.write_bytes(_wav_bytes())

    with FakeServer(lambda *_: (200, {"text": "x"})) as server:
        dead_url = server.base_url
    with pytest.raises(RuntimeError, match="unreachable"):
        await OpenAICompatSTTBackend(endpoint=dead_url, model="m", timeout=2.0).transcribe(str(audio))

    with FakeServer(lambda *_: (200, b"<html>proxy</html>")) as server:
        with pytest.raises(RuntimeError, match="non-JSON"):
            await OpenAICompatSTTBackend(endpoint=server.base_url, model="m").transcribe(str(audio))

    with FakeServer(lambda *_: (200, {"segments": []})) as server:
        with pytest.raises(ValueError, match="No transcript text"):
            await OpenAICompatSTTBackend(endpoint=server.base_url, model="m").transcribe(str(audio))


def test_stt_dispatch_switches_between_remote_and_local():
    set_stt_config("http://localhost:1/v1", "remote-stt", None)
    remote = get_stt_backend()
    assert isinstance(remote, OpenAICompatSTTBackend)
    assert get_stt_backend() is remote, "same config must reuse the cached instance"
    assert is_remote(remote)
    assert resolve_stt_model(remote, "turbo") == "remote-stt"

    set_stt_config("http://localhost:2/v1", "remote-stt", "key")
    rotated = get_stt_backend()
    assert rotated is not remote
    assert (rotated.endpoint, rotated.api_key) == ("http://localhost:2/v1", "key")

    set_stt_config("", "", "")
    local = get_stt_backend()
    assert not isinstance(local, OpenAICompatSTTBackend)
    assert not is_remote(local)
    assert resolve_stt_model(local, "small") == "small"


def test_stt_dispatch_requires_both_endpoint_and_model():
    set_stt_config("http://localhost:1/v1", None, None)
    assert not isinstance(get_stt_backend(), OpenAICompatSTTBackend)
    set_stt_config(None, "m", None)
    assert not isinstance(get_stt_backend(), OpenAICompatSTTBackend)


def test_capture_settings_response_masks_both_api_keys():
    stored = models.CaptureSettingsResponse(
        custom_llm_api_key="sk-llm-secret",
        custom_stt_endpoint="https://openrouter.ai/api/v1",
        custom_stt_model="openai/whisper-large-v3-turbo",
        custom_stt_api_key="sk-stt-secret",
    )
    dumped = stored.model_dump_json()
    assert stored.custom_llm_api_key_configured is True
    assert stored.custom_stt_api_key_configured is True
    assert "sk-llm-secret" not in dumped
    assert "sk-stt-secret" not in dumped

    empty = models.CaptureSettingsResponse()
    assert empty.custom_stt_api_key_configured is False


def test_settings_write_syncs_stt_dispatch_and_readiness(tmp_path):
    from sqlalchemy import create_engine
    from sqlalchemy.orm import sessionmaker

    from backend.backends import get_stt_model_configs
    from backend.database.models import Base
    from backend.routes.captures import _stt_readiness
    from backend.services import settings as settings_service

    engine = create_engine(f"sqlite:///{tmp_path / 'settings.db'}")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()
    try:
        row = settings_service.update_capture_settings(
            db,
            {
                "custom_stt_endpoint": "http://localhost:1/v1",
                "custom_stt_model": "remote-stt",
                "custom_stt_api_key": "k",
            },
        )
        assert isinstance(get_stt_backend(), OpenAICompatSTTBackend)

        stt_cfg = next(c for c in get_stt_model_configs() if c.model_size == row.stt_model)
        readiness = _stt_readiness(row, stt_cfg)
        assert readiness.ready is True
        assert readiness.display_name == "remote-stt"

        reset_backends()
        settings_service.bootstrap_llm_backend_config(db)
        assert isinstance(get_stt_backend(), OpenAICompatSTTBackend)

        row = settings_service.update_capture_settings(db, {"custom_stt_endpoint": None})
        assert not isinstance(get_stt_backend(), OpenAICompatSTTBackend)
        assert _stt_readiness(row, stt_cfg).display_name == stt_cfg.display_name
    finally:
        db.close()


def test_transcribe_route_uses_remote_endpoint_without_local_model(tmp_path):
    """PUT /settings/captures → GET masks the key → /capture/readiness is
    green without a Whisper download → /transcribe hits the remote server."""
    from fastapi import FastAPI
    from sqlalchemy import create_engine
    from sqlalchemy.orm import sessionmaker
    from starlette.testclient import TestClient

    from backend.database import get_db
    from backend.database.models import Base
    from backend.routes import (
        captures as captures_routes,
        settings as settings_routes,
        transcription as transcription_routes,
    )

    engine = create_engine(
        f"sqlite:///{tmp_path / 'routes.db'}", connect_args={"check_same_thread": False}
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
    app.include_router(transcription_routes.router)
    app.dependency_overrides[get_db] = _db
    client = TestClient(app)

    with FakeServer(lambda *_: (200, {"text": "Ditado na nuvem."})) as server:
        res = client.put(
            "/settings/captures",
            json={
                "custom_stt_endpoint": server.base_url,
                "custom_stt_model": "openai/whisper-large-v3-turbo",
                "custom_stt_api_key": "sk-stt-secret",
            },
        )
        assert res.status_code == 200, res.text
        assert res.json()["custom_stt_api_key_configured"] is True
        assert "sk-stt-secret" not in res.text
        assert "sk-stt-secret" not in client.get("/settings/captures").text

        readiness = client.get("/capture/readiness").json()
        assert readiness["stt"]["ready"] is True
        assert readiness["stt"]["display_name"] == "openai/whisper-large-v3-turbo"

        res = client.post(
            "/transcribe",
            files={"file": ("clip.wav", _wav_bytes(), "audio/wav")},
            data={"language": "pt"},
        )
        assert res.status_code == 200, res.text
        assert res.json()["text"] == "Ditado na nuvem."
        req = server.requests[-1]
        assert req["path"] == "/v1/audio/transcriptions"
        assert req["headers"]["authorization"] == "Bearer sk-stt-secret"

    client.put("/settings/captures", json={"custom_stt_endpoint": None, "custom_stt_model": None})
    assert not isinstance(get_stt_backend(), OpenAICompatSTTBackend)


# ── Claude refinement backend ─────────────────────────────────────────────


def test_is_anthropic_endpoint():
    assert is_anthropic_endpoint("https://api.anthropic.com/v1")
    assert is_anthropic_endpoint("https://api.anthropic.com")
    assert not is_anthropic_endpoint("https://openrouter.ai/api/v1")
    assert not is_anthropic_endpoint("https://anthropic.com.evil.example/v1")
    assert not is_anthropic_endpoint("")
    assert not is_anthropic_endpoint(None)


def test_llm_dispatch_routes_claude_api_to_native_backend():
    set_llm_config("https://api.anthropic.com/v1", "claude-haiku-5-5", "sk-ant")
    backend = get_llm_backend()
    assert isinstance(backend, AnthropicLLMBackend)
    assert backend.endpoint == "https://api.anthropic.com"
    assert backend.model_size == "claude-haiku-5-5"

    set_llm_config("https://openrouter.ai/api/v1", "anthropic/claude-haiku-5-5", "sk-or")
    assert isinstance(get_llm_backend(), OpenAICompatLLMBackend)


@pytest.mark.asyncio
async def test_claude_generate_sends_messages_shape_without_sampling_params():
    with FakeServer(lambda *_: _claude_reply("  Texto polido.  ")) as server:
        backend = AnthropicLLMBackend(endpoint=server.base_url, model="claude-haiku-5-5", api_key="sk-ant")
        out = await backend.generate(
            prompt="é, oi tudo bem",
            system="Limpe o ditado.",
            max_tokens=256,
            temperature=0.7,
            examples=[("um oi", "Oi.")],
        )

    assert out == "Texto polido."
    (req,) = server.requests
    assert req["path"] == "/v1/messages"
    # anthropic SDK 2.x authenticates API keys with a bearer token.
    assert req["headers"]["authorization"] == "Bearer sk-ant"
    body = json.loads(req["raw"])
    assert body["model"] == "claude-haiku-5-5"
    assert body["system"] == "Limpe o ditado."
    assert body["output_config"] == {"effort": "low"}
    assert body["max_tokens"] > 256, "thinking headroom on top of the reply budget"
    assert "temperature" not in body
    assert body["messages"] == [
        {"role": "user", "content": "um oi"},
        {"role": "assistant", "content": "Oi."},
        {"role": "user", "content": "é, oi tudo bem"},
    ]


@pytest.mark.asyncio
async def test_claude_refusal_and_http_error_surface_as_runtime_errors():
    with FakeServer(lambda *_: _claude_reply("", stop_reason="refusal")) as server:
        backend = AnthropicLLMBackend(endpoint=server.base_url, model="claude-haiku-5-5", api_key="k")
        with pytest.raises(RuntimeError, match="declined"):
            await backend.generate(prompt="x")

    error = {"type": "error", "error": {"type": "invalid_request_error", "message": "bad model"}}
    with FakeServer(lambda *_: (400, error)) as server:
        backend = AnthropicLLMBackend(endpoint=server.base_url, model="nope", api_key="k")
        with pytest.raises(RuntimeError) as excinfo:
            await backend.generate(prompt="x")
    assert "HTTP 400" in str(excinfo.value)
    assert "bad model" in str(excinfo.value)


@pytest.mark.asyncio
async def test_refinement_records_claude_model_label():
    from backend.services import refinement

    with FakeServer(lambda *_: _claude_reply("Refinado.")) as server:
        set_llm_config(server.base_url, "claude-haiku-5-5", "k")
        # A local fake URL isn't api.anthropic.com, so wire the native
        # backend in directly to exercise the label path end to end.
        from backend.backends import _OPENAI_COMPAT_CACHE_KEY, _llm_backends

        _llm_backends[_OPENAI_COMPAT_CACHE_KEY] = AnthropicLLMBackend(
            endpoint=server.base_url, model="claude-haiku-5-5", api_key="k"
        )
        text, size = await refinement.refine_transcript(
            "é, oi", refinement.RefinementFlags(), model_size="0.6B"
        )
    assert (text, size) == ("Refinado.", "claude-haiku-5-5")


@pytest.mark.asyncio
@pytest.mark.parametrize("transcript", ["", "   ", "Obrigado. " * 8])
async def test_refinement_skips_the_llm_when_nothing_is_left_to_refine(transcript):
    """Silence (or Whisper's silence loop) must not reach the LLM: Claude
    rejects an empty message and a local model would invent text."""
    from backend.backends import _OPENAI_COMPAT_CACHE_KEY, _llm_backends
    from backend.services import refinement

    with FakeServer(lambda *_: _claude_reply("Inventado.")) as server:
        set_llm_config(server.base_url, "claude-haiku-5-5", "k")
        _llm_backends[_OPENAI_COMPAT_CACHE_KEY] = AnthropicLLMBackend(
            endpoint=server.base_url, model="claude-haiku-5-5", api_key="k"
        )
        text, _size = await refinement.refine_transcript(transcript, refinement.RefinementFlags())
    assert text == ""
    assert server.requests == []


@pytest.mark.asyncio
async def test_claude_reply_cut_off_at_max_tokens_is_an_error():
    """A truncated refinement must not be pasted as if it were complete."""
    with FakeServer(lambda *_: _claude_reply("Texto cortado pela met", stop_reason="max_tokens")) as server:
        backend = AnthropicLLMBackend(endpoint=server.base_url, model="claude-haiku-5-5", api_key="k")
        with pytest.raises(RuntimeError, match="max_tokens"):
            await backend.generate(prompt="x")


def test_moving_an_endpoint_to_another_host_drops_its_stored_key(tmp_path):
    """A key belongs to the host it was entered for; switching OpenRouter to
    Groq without a new key must not send the OpenRouter key to Groq."""
    from sqlalchemy import create_engine
    from sqlalchemy.orm import sessionmaker

    from backend.database.models import Base
    from backend.services import settings as settings_service

    engine = create_engine(f"sqlite:///{tmp_path / 'settings.db'}")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()
    update = settings_service.update_capture_settings
    try:
        update(
            db,
            {
                "custom_stt_endpoint": "https://openrouter.ai/api/v1",
                "custom_stt_model": "openai/whisper-large-v3-turbo",
                "custom_stt_api_key": "sk-or-old",
                "custom_llm_endpoint": "https://api.anthropic.com/v1",
                "custom_llm_model": "claude-haiku-5-5",
                "custom_llm_api_key": "sk-ant-old",
            },
        )

        # Same host, different path: the key stays.
        row = update(db, {"custom_stt_endpoint": "https://openrouter.ai/api/v1/"})
        assert row.custom_stt_api_key == "sk-or-old"

        # Another host, no key in the same request: the key is dropped, and
        # the live backend has none either.
        row = update(db, {"custom_stt_endpoint": "https://api.groq.com/openai/v1"})
        assert row.custom_stt_api_key is None
        assert not get_stt_backend().api_key

        # Endpoint and key together: the new key is kept.
        row = update(
            db, {"custom_stt_endpoint": "https://openrouter.ai/api/v1", "custom_stt_api_key": "sk-or-new"}
        )
        assert row.custom_stt_api_key == "sk-or-new"

        # Same rule for the refinement endpoint; the other side is untouched.
        row = update(db, {"custom_llm_endpoint": "https://openrouter.ai/api/v1"})
        assert row.custom_llm_api_key is None
        assert row.custom_stt_api_key == "sk-or-new"

        # Clearing an endpoint (back to local) keeps the key.
        update(db, {"custom_llm_api_key": "sk-or-llm"})
        row = update(db, {"custom_llm_endpoint": None})
        assert row.custom_llm_api_key == "sk-or-llm"
    finally:
        db.close()


# ── Warm-up of the dictation path ─────────────────────────────────────────


@pytest.mark.asyncio
async def test_stt_warm_sends_head_and_upload_reuses_the_connection(tmp_path):
    audio = tmp_path / "clip.wav"
    audio.write_bytes(_wav_bytes())
    with FakeServer(lambda *_: (200, {"text": "ok"})) as server:
        backend = OpenAICompatSTTBackend(endpoint=server.base_url, model="m", api_key="sk-or")
        await backend.warm()
        warmed_client = backend._client
        await backend.transcribe(str(audio))
    warm_req, upload_req = server.requests
    assert (warm_req["method"], warm_req["path"]) == ("HEAD", "/v1/models")
    assert warm_req["headers"]["authorization"] == "Bearer sk-or"
    assert upload_req["method"] == "POST"
    assert backend._client is warmed_client, "the upload must reuse the warmed pool"


@pytest.mark.asyncio
async def test_warm_never_raises_when_endpoints_are_down():
    with FakeServer(lambda *_: (200, {})) as server:
        dead_url = server.base_url
    await OpenAICompatSTTBackend(endpoint=dead_url, model="m", timeout=2.0).warm()
    await AnthropicLLMBackend(endpoint=dead_url, model="claude-haiku-5-5", api_key="k").warm()


@pytest.mark.asyncio
async def test_claude_warm_lists_one_model_without_messages():
    listing = {"data": [], "has_more": False, "first_id": None, "last_id": None}
    with FakeServer(lambda *_: (200, listing)) as server:
        backend = AnthropicLLMBackend(endpoint=server.base_url, model="claude-haiku-5-5", api_key="k")
        await backend.warm()
    (req,) = server.requests
    assert req["method"] == "GET"
    assert req["path"].startswith("/v1/models")
    assert "limit=1" in req["path"]


def test_warm_audio_decoder_decodes_once_and_cleans_up(monkeypatch):
    from backend.services import warmup
    from backend.utils import audio as audio_utils

    seen = []

    def fake_load_audio(path, *args, **kwargs):
        seen.append(path)
        assert __import__("os").path.exists(path)
        return None, 16_000

    monkeypatch.setattr(audio_utils, "load_audio", fake_load_audio)
    warmup.warm_audio_decoder()
    assert len(seen) == 1
    assert not __import__("os").path.exists(seen[0]), "temporary clip must be removed"


@pytest.mark.asyncio
async def test_warm_remote_backends_calls_only_remote_backends(monkeypatch):
    from backend.services import warmup

    calls = []

    class Remote:
        async def warm(self):
            calls.append("remote")

    class Local:  # no warm(): local backends are skipped
        pass

    monkeypatch.setattr("backend.backends.get_stt_backend", lambda: Remote())
    monkeypatch.setattr("backend.backends.get_llm_backend", lambda: Local())
    await warmup.warm_remote_backends()
    assert calls == ["remote"]


def test_capture_warmup_route_returns_202_immediately(monkeypatch):
    from fastapi import FastAPI
    from starlette.testclient import TestClient

    from backend.routes import captures as captures_routes
    from backend.services import warmup

    started = []

    async def fake_warm():
        started.append(True)

    monkeypatch.setattr(warmup, "warm_remote_backends", fake_warm)
    app = FastAPI()
    app.include_router(captures_routes.router)
    res = TestClient(app).post("/capture/warmup")
    assert res.status_code == 202
    assert res.json() == {"status": "warming"}
