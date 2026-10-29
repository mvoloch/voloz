"""
Tests for the Fish Audio cloud TTS engine.

The HTTP layer is an ``httpx.MockTransport``: no network, no key. They cover
the request Voicebox sends (MessagePack body with the profile's sample as an
inline reference), the missing-key path, the error messages, the key's
storage, and that the engine is listed without ever needing a download.

Usage:
    python -m pytest backend/tests/test_fish_audio_tts.py -v
"""

import ast
import io
import uuid
from pathlib import Path

import httpx
import msgpack
import numpy as np
import pytest
import soundfile as sf
from fastapi import FastAPI, HTTPException
from sqlalchemy import create_engine, inspect, text
from sqlalchemy.orm import sessionmaker
from starlette.testclient import TestClient

from backend import backends, config, models
from backend.backends import (
    TTS_ENGINES,
    check_model_loaded,
    cloud_engine_unavailable_reason,
    ensure_model_cached_or_raise,
    get_model_config,
    get_tts_model_configs,
    reset_backends,
    set_fish_audio_config,
    unload_model_by_config,
)
from backend.backends.fish_audio_backend import (
    FISH_AUDIO_MODEL,
    FISH_AUDIO_TTS_URL,
    FishAudioError,
    FishAudioTTSBackend,
)
from backend.database import Base, Generation, ProfileSample, VoiceProfile, get_db

TEST_KEY = "fa-test-key-not-real"


def _wav_bytes(seconds: float = 0.2, rate: int = 44_100, channels: int = 1) -> bytes:
    frames = int(seconds * rate)
    tone = 0.1 * np.sin(2 * np.pi * 220 * np.arange(frames) / rate).astype(np.float32)
    data = np.stack([tone] * channels, axis=1) if channels > 1 else tone
    buf = io.BytesIO()
    sf.write(buf, data, rate, format="WAV", subtype="PCM_16")
    return buf.getvalue()


class Recorder:
    """MockTransport handler that records requests and replays a response."""

    def __init__(self, status: int = 200, body: bytes | None = None, json_body=None, exc=None):
        self.requests: list[httpx.Request] = []
        self.status = status
        self.body = _wav_bytes() if body is None and json_body is None else body
        self.json_body = json_body
        self.exc = exc

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        if self.exc is not None:
            raise self.exc
        if self.json_body is not None:
            return httpx.Response(self.status, json=self.json_body)
        return httpx.Response(self.status, content=self.body, headers={"content-type": "audio/wav"})


def _backend(recorder: Recorder) -> FishAudioTTSBackend:
    return FishAudioTTSBackend(transport=httpx.MockTransport(recorder))


@pytest.fixture(autouse=True)
def _clean_backends():
    reset_backends()
    yield
    reset_backends()


@pytest.fixture
def sample_wav(tmp_path) -> Path:
    path = tmp_path / "sample.wav"
    path.write_bytes(_wav_bytes(seconds=0.5, rate=24_000))
    return path


# ── Request shape ─────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_request_sends_msgpack_with_inline_reference(sample_wav):
    set_fish_audio_config(TEST_KEY)
    recorder = Recorder(body=_wav_bytes(seconds=0.3, channels=2))
    backend = _backend(recorder)

    prompt, cached = await backend.create_voice_prompt(str(sample_wav), "Olá, esta é a minha voz.")
    assert cached is False
    audio, sample_rate = await backend.generate("Bom dia a todos.", prompt, language="pt", seed=7)

    assert len(recorder.requests) == 1
    request = recorder.requests[0]
    assert request.method == "POST"
    assert str(request.url) == FISH_AUDIO_TTS_URL
    assert request.headers["authorization"] == f"Bearer {TEST_KEY}"
    assert request.headers["content-type"] == "application/msgpack"
    assert request.headers["model"] == FISH_AUDIO_MODEL == "s2.1-pro"

    body = msgpack.unpackb(request.content, raw=False)
    assert body["text"] == "Bom dia a todos."
    assert body["format"] == "wav"
    assert body["sample_rate"] == 44_100
    assert body["language"] == "pt"
    # The sample travels as raw bytes (msgpack bin) with its transcript.
    assert body["references"] == [
        {"audio": sample_wav.read_bytes(), "text": "Olá, esta é a minha voz."}
    ]
    # Fish Audio has no seed field; nothing unknown is sent.
    assert set(body) == {"text", "references", "format", "sample_rate", "language"}

    # Stereo WAV back → mono float32 at the response's rate.
    assert sample_rate == 44_100
    assert audio.dtype == np.float32
    assert audio.ndim == 1
    assert len(audio) == int(0.3 * 44_100)


@pytest.mark.asyncio
async def test_non_wav_reference_is_reencoded_as_wav(tmp_path):
    set_fish_audio_config(TEST_KEY)
    ogg = tmp_path / "sample.ogg"
    sf.write(str(ogg), np.zeros(4800, dtype=np.float32), 24_000, format="OGG", subtype="VORBIS")
    recorder = Recorder()

    await _backend(recorder).generate("Hi.", {"ref_audio": str(ogg), "ref_text": "hi"})

    reference = msgpack.unpackb(recorder.requests[0].content, raw=False)["references"][0]["audio"]
    assert reference[:4] == b"RIFF"


# ── Missing key ───────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_missing_key_fails_before_any_request(sample_wav):
    recorder = Recorder()
    backend = _backend(recorder)
    prompt, _ = await backend.create_voice_prompt(str(sample_wav), "ref")

    reason = backend.unavailable_reason()
    assert "API key" in reason
    assert "Settings → Generation" in reason
    with pytest.raises(FishAudioError, match="Settings → Generation"):
        await backend.load_model()
    with pytest.raises(FishAudioError, match="needs an API key"):
        await backend.generate("Olá.", prompt, language="pt")
    assert recorder.requests == []

    # The registry and the stream/speak gate report the same reason.
    assert cloud_engine_unavailable_reason("fish_audio") == reason
    with pytest.raises(HTTPException) as exc_info:
        await ensure_model_cached_or_raise("fish_audio")
    assert exc_info.value.status_code == 400
    assert exc_info.value.detail == reason

    set_fish_audio_config(TEST_KEY)
    assert cloud_engine_unavailable_reason("fish_audio") is None
    await ensure_model_cached_or_raise("fish_audio")
    # Local engines never have a cloud reason.
    assert cloud_engine_unavailable_reason("qwen") is None


# ── Error mapping ─────────────────────────────────────────────────────────


@pytest.mark.parametrize(
    ("recorder", "expected"),
    [
        (Recorder(401, json_body={"status": 401, "message": "Invalid token"}), "rejected the API key"),
        (Recorder(402, json_body={"status": 402, "message": "No payment"}), "no API credit left"),
        (Recorder(429, json_body={"status": 429, "message": "Too many requests"}), "limiting requests"),
        (Recorder(413, body=b"Request Entity Too Large"), "too long for one Fish Audio request"),
        (
            Recorder(400, json_body={"status": 400, "message": "Text length exceeds the maximum"}),
            "too long for one Fish Audio request",
        ),
        (Recorder(400, json_body={"status": 400, "message": "Invalid language"}), "rejected the request"),
        (Recorder(503, json_body={"status": 503, "message": "High load"}), "having trouble"),
        (Recorder(exc=httpx.ConnectError("Name or service not known")), "Could not reach Fish Audio"),
        (Recorder(exc=httpx.ReadTimeout("timed out")), "did not answer within"),
        (Recorder(200, body=b"<html>not audio</html>"), "could not be decoded"),
    ],
    ids=["401", "402", "429", "413", "400-length", "400", "503", "network", "timeout", "bad-audio"],
)
@pytest.mark.asyncio
async def test_errors_become_actionable_messages(sample_wav, recorder, expected):
    set_fish_audio_config(TEST_KEY)
    with pytest.raises(FishAudioError) as exc_info:
        await _backend(recorder).generate("Olá.", {"ref_audio": str(sample_wav), "ref_text": "x"})
    message = str(exc_info.value)
    assert expected in message
    # The key never leaks into a message that ends up in the history.
    assert TEST_KEY not in message
    if recorder.json_body:
        assert recorder.json_body["message"] in message


@pytest.mark.asyncio
async def test_failed_generation_keeps_the_real_cause(tmp_path, monkeypatch, sample_wav):
    """Same promise as 721b564 for local engines: the stored error says why."""
    from backend.services import generation as generation_service

    db, profile_id = _db_with_cloned_profile(tmp_path, monkeypatch, sample_wav)
    set_fish_audio_config(TEST_KEY)
    backends._tts_backends["fish_audio"] = _backend(
        Recorder(402, json_body={"status": 402, "message": "No payment"})
    )

    for gen_id, recorder_status in (("gen-fails", 402), ("gen-works", 200)):
        if recorder_status == 200:
            backends._tts_backends["fish_audio"] = _backend(Recorder())
        db.add(Generation(id=gen_id, profile_id=profile_id, text="Olá.", engine="fish_audio", status="generating"))
        db.commit()
        await generation_service.run_generation(
            generation_id=gen_id,
            profile_id=profile_id,
            text="Olá.",
            language="pt",
            engine="fish_audio",
            model_size=None,
            seed=None,
            mode="generate",
        )

    db.expire_all()
    failed = db.get(Generation, "gen-fails")
    assert failed.status == "failed"
    assert "HTTP 402" in failed.error
    assert "No payment" in failed.error

    done = db.get(Generation, "gen-works")
    assert done.status == "completed", done.error
    assert done.duration == pytest.approx(0.2, abs=0.01)
    db.close()


# ── Route gate and storage ────────────────────────────────────────────────


def _db_with_cloned_profile(tmp_path, monkeypatch, sample_wav):
    monkeypatch.setattr(config, "_data_dir", tmp_path)
    engine = create_engine(f"sqlite:///{tmp_path / 'fish.db'}", connect_args={"check_same_thread": False})
    Base.metadata.create_all(engine)
    session_factory = sessionmaker(autocommit=False, autoflush=False, bind=engine)

    def _get_db():
        session = session_factory()
        try:
            yield session
        finally:
            session.close()

    from backend.services import generation as generation_service

    monkeypatch.setattr(generation_service, "get_db", _get_db)
    db = session_factory()
    profile_id = str(uuid.uuid4())
    db.add(VoiceProfile(id=profile_id, name="Marcus", language="pt", voice_type="cloned"))
    db.add(
        ProfileSample(
            id=str(uuid.uuid4()),
            profile_id=profile_id,
            audio_path=config.to_storage_path(sample_wav),
            reference_text="Olá, esta é a minha voz.",
        )
    )
    db.commit()
    db._get_db = _get_db  # for route tests
    return db, profile_id


def test_generate_route_rejects_fish_audio_without_a_key(tmp_path, monkeypatch, sample_wav):
    from backend.routes import generations as generation_routes

    db, profile_id = _db_with_cloned_profile(tmp_path, monkeypatch, sample_wav)
    enqueued = []
    monkeypatch.setattr(generation_routes, "enqueue_generation", lambda gid, coro: (enqueued.append(gid), coro.close()))

    app = FastAPI()
    app.include_router(generation_routes.router)
    app.dependency_overrides[get_db] = db._get_db
    client = TestClient(app)
    body = {"profile_id": profile_id, "text": "Olá.", "language": "pt", "engine": "fish_audio"}

    response = client.post("/generate", json=body)
    assert response.status_code == 400
    assert "Fish Audio needs an API key" in response.json()["detail"]
    assert db.query(Generation).count() == 0
    assert enqueued == []

    set_fish_audio_config(TEST_KEY)
    response = client.post("/generate", json=body)
    assert response.status_code == 200, response.text
    assert response.json()["engine"] == "fish_audio"
    assert response.json()["model_size"] is None
    assert len(enqueued) == 1
    db.close()


def test_key_is_stored_write_only_and_synced(tmp_path):
    from backend.services import settings as settings_service

    engine = create_engine(f"sqlite:///{tmp_path / 'settings.db'}")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()
    try:
        row = settings_service.update_generation_settings(db, {"fish_audio_api_key": f"  {TEST_KEY}\n"})
        assert row.fish_audio_api_key == TEST_KEY
        assert backends.get_fish_audio_api_key() == TEST_KEY

        response = models.GenerationSettingsResponse.model_validate(row)
        assert response.fish_audio_api_key_configured is True
        assert TEST_KEY not in response.model_dump_json()

        # Startup seeds the key from the row.
        reset_backends()
        settings_service.bootstrap_tts_backend_config(db)
        assert backends.get_fish_audio_api_key() == TEST_KEY

        # Other fields leave the key alone; a blank key clears it.
        row = settings_service.update_generation_settings(db, {"crossfade_ms": 0})
        assert row.fish_audio_api_key == TEST_KEY
        row = settings_service.update_generation_settings(db, {"fish_audio_api_key": "  "})
        assert row.fish_audio_api_key is None
        assert backends.get_fish_audio_api_key() is None
        assert models.GenerationSettingsResponse.model_validate(row).fish_audio_api_key_configured is False
    finally:
        db.close()


def test_migration_adds_the_key_column_to_an_existing_table(tmp_path):
    from backend.database.migrations import run_migrations

    engine = create_engine(f"sqlite:///{tmp_path / 'old.db'}")
    with engine.begin() as conn:
        conn.execute(
            text(
                "CREATE TABLE generation_settings (id INTEGER PRIMARY KEY, max_chunk_chars INTEGER NOT NULL, "
                "crossfade_ms INTEGER NOT NULL, normalize_audio BOOLEAN NOT NULL, "
                "autoplay_on_generate BOOLEAN NOT NULL, updated_at DATETIME)"
            )
        )
    run_migrations(engine)
    columns = {c["name"] for c in inspect(engine).get_columns("generation_settings")}
    assert "fish_audio_api_key" in columns


# ── Listed, never downloaded ──────────────────────────────────────────────


@pytest.mark.asyncio
async def test_engine_is_listed_but_never_needs_a_download():
    assert TTS_ENGINES["fish_audio"] == "Fish Audio (cloud)"
    configs = [c for c in get_tts_model_configs() if c.engine == "fish_audio"]
    assert len(configs) == 1
    cfg = configs[0]
    assert cfg.cloud is True
    assert cfg.hf_repo_id == ""
    assert "pt" in cfg.languages
    assert get_model_config("fish-audio") is cfg or get_model_config("fish-audio") == cfg

    # Never counts as loaded or local, so nothing offers to unload it.
    assert check_model_loaded(cfg) is False
    assert unload_model_by_config(cfg) is False

    from backend.routes import models as model_routes

    status = await model_routes.get_model_status()
    fish = next(m for m in status.models if m.model_name == "fish-audio")
    assert fish.cloud is True
    assert fish.downloaded is False
    assert fish.downloading is False
    assert fish.loaded is False
    assert "API key" in fish.unavailable_reason

    set_fish_audio_config(TEST_KEY)
    status = await model_routes.get_model_status()
    fish = next(m for m in status.models if m.model_name == "fish-audio")
    assert fish.unavailable_reason is None
    assert fish.downloaded is False

    # Download and delete are refused instead of touching the HF cache.
    app = FastAPI()
    app.include_router(model_routes.router)
    client = TestClient(app)
    response = client.post("/models/download", json={"model_name": "fish-audio"})
    assert response.status_code == 400
    assert "nothing to download" in response.json()["detail"]
    response = client.delete("/models/fish-audio")
    assert response.status_code == 400


def test_engine_is_accepted_by_requests_and_profiles():
    from backend.services.profiles import CLONING_ENGINES

    assert "fish_audio" in CLONING_ENGINES
    request = models.GenerationRequest(profile_id="p", text="Olá", language="pt", engine="fish_audio")
    assert request.engine == "fish_audio"
    assert models.SpeakRequest(text="Olá", engine="fish_audio").engine == "fish_audio"


def test_backend_module_imports_no_ml_library():
    """The cloud engine must stay usable in a build without torch."""
    source = Path(__file__).parent.parent / "backends" / "fish_audio_backend.py"
    imported = set()
    for node in ast.walk(ast.parse(source.read_text(encoding="utf-8"))):
        if isinstance(node, ast.Import):
            imported.update(alias.name.split(".")[0] for alias in node.names)
        elif isinstance(node, ast.ImportFrom) and node.level == 0 and node.module:
            imported.add(node.module.split(".")[0])
    banned = {"torch", "torchaudio", "transformers", "librosa", "numba", "mlx", "qwen_tts", "huggingface_hub"}
    assert not imported & banned, imported & banned
    assert {"httpx", "msgpack", "soundfile"} <= imported
