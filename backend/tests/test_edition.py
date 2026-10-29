"""
Tests for the lite edition (backend/edition.py): dictation only, no torch.

The lite server is built from requirements-lite.txt, so the strongest check
is that the app imports and serves dictation with torch and the other ML
packages unavailable. The subprocess test blocks them in a fresh
interpreter, which works in the full test venv too.

Usage:
    python -m pytest backend/tests/test_edition.py -v
"""

import asyncio
import os
import subprocess
import sys
from pathlib import Path

import pytest
from fastapi import FastAPI

from backend import edition

REPO_ROOT = Path(__file__).resolve().parents[2]


@pytest.fixture
def lite(monkeypatch):
    monkeypatch.setenv("VOICEBOX_EDITION", "lite")
    edition.get_edition.cache_clear()
    yield
    edition.get_edition.cache_clear()


@pytest.fixture(autouse=True)
def _reset_edition_cache():
    edition.get_edition.cache_clear()
    yield
    edition.get_edition.cache_clear()


def test_edition_follows_torch_when_not_forced(monkeypatch):
    monkeypatch.delenv("VOICEBOX_EDITION", raising=False)
    monkeypatch.setattr(edition.importlib.util, "find_spec", lambda name: None)
    assert edition.get_edition() == "lite"

    edition.get_edition.cache_clear()
    monkeypatch.setattr(edition.importlib.util, "find_spec", lambda name: object())
    assert edition.get_edition() == "studio"


def test_env_var_overrides_detection(monkeypatch):
    monkeypatch.setattr(edition.importlib.util, "find_spec", lambda name: object())
    monkeypatch.setenv("VOICEBOX_EDITION", "Lite")
    assert edition.is_lite()

    edition.get_edition.cache_clear()
    monkeypatch.setenv("VOICEBOX_EDITION", "nonsense")
    assert edition.get_edition() == "studio"


def test_lite_serves_dictation_routes_only(lite):
    from backend.routes import register_routers

    app = FastAPI()
    register_routers(app)
    paths = set(app.openapi()["paths"])

    for served in ("/health", "/captures", "/captures/{capture_id}/refine",
                   "/capture/warmup", "/settings/captures", "/tasks/active"):
        assert served in paths, served
    for studio_only in ("/profiles", "/generate", "/stories", "/models/status",
                        "/v1/audio/speech", "/speak"):
        assert studio_only not in paths, studio_only


def test_lite_health_skips_local_models(lite, monkeypatch):
    from backend.routes import health as health_route

    def _no_tts():
        raise AssertionError("lite health must not touch the TTS model")

    monkeypatch.setattr(health_route.tts, "get_tts_model", _no_tts)
    result = asyncio.run(health_route.health())
    assert result.status == "healthy"
    assert result.gpu_available is False
    assert result.model_loaded is False


def test_app_imports_and_starts_without_ml_packages():
    """Block torch, librosa & co. in a fresh interpreter, build the app,
    hit /health and run a dictation through a fake STT endpoint."""
    script = """
import sys
BLOCKED = ("torch", "torchaudio", "transformers", "qwen_tts", "pedalboard", "fastmcp",
           "librosa", "numba", "llvmlite", "scipy", "audioread", "resampy")
for name in BLOCKED:
    sys.modules[name] = None  # any import of them raises ImportError
import io, tempfile, time, wave
import numpy as np
import soundfile as sf
from backend import config
config.set_data_dir(tempfile.mkdtemp())
from starlette.testclient import TestClient
from backend.app import create_app
from backend.tests.test_cloud_dictation import FakeServer

# What the webview records: 48 kHz stereo 16-bit WAV, 1 s.
t = np.arange(48_000) / 48_000
tone = 0.3 * np.sin(2 * np.pi * 440 * t)
buf = io.BytesIO()
sf.write(buf, np.stack([tone, tone], axis=1), 48_000, format="WAV", subtype="PCM_16")

with FakeServer(lambda *_: (200, {"text": "hello"})) as stt:
    with TestClient(create_app()) as client:
        assert client.get("/health").json()["gpu_available"] is False
        assert client.get("/captures?limit=1").status_code == 200
        assert client.get("/profiles").status_code == 404
        assert client.put("/settings/captures", json={
            "custom_stt_endpoint": stt.base_url, "custom_stt_model": "m",
            "custom_stt_api_key": "k", "auto_refine": False,
        }).status_code == 200
        res = client.post("/captures", data={"source": "dictation"},
                          files={"file": ("d.wav", buf.getvalue(), "audio/wav")})
        assert res.status_code == 200, res.text
        body = res.json()
        assert body["transcript_raw"] == "hello" and body["duration_ms"] == 1000, body
        # Lite decodes with soundfile only: a format it can't read is a 415.
        res = client.post("/captures", data={"source": "file"},
                          files={"file": ("a.m4a", b"\\x00\\x00\\x00\\x18ftypM4A " + bytes(64), "audio/mp4")})
        assert res.status_code == 415, res.text
        assert "WAV" in res.json()["detail"]
        assert client.get("/captures").json()["total"] == 1

    uploads = [r for r in stt.requests if r["path"].endswith("/audio/transcriptions")]
    assert len(uploads) == 1
    raw = uploads[0]["raw"]
    start = raw.index(b"RIFF")
    size = int.from_bytes(raw[start + 4 : start + 8], "little") + 8
    with wave.open(io.BytesIO(raw[start : start + size])) as w:
        assert (w.getframerate(), w.getnchannels(), w.getsampwidth()) == (16_000, 1, 2)
        assert w.getnframes() == 16_000
print("ok")
"""
    env = {**os.environ, "VOICEBOX_EDITION": "lite"}
    proc = subprocess.run(
        [sys.executable, "-c", script],
        cwd=REPO_ROOT,
        env=env,
        capture_output=True,
        text=True,
        timeout=120,
    )
    assert proc.returncode == 0, proc.stderr[-3000:]
    assert proc.stdout.strip().endswith("ok")


def test_lite_readiness_needs_endpoints_even_with_cached_models(lite, monkeypatch):
    from types import SimpleNamespace

    from backend.routes import captures as captures_route

    monkeypatch.setattr(captures_route, "is_model_cached", lambda repo_id: True)
    saved = SimpleNamespace(
        custom_stt_endpoint=None,
        custom_stt_model=None,
        custom_llm_endpoint=None,
        custom_llm_model=None,
    )
    cfg = SimpleNamespace(
        model_name="m", display_name="M", model_size="s", size_mb=1, hf_repo_id="r"
    )
    assert captures_route._stt_readiness(saved, cfg).ready is False
    assert captures_route._llm_readiness(saved, cfg).ready is False

    saved.custom_stt_endpoint = "https://api.groq.com/openai/v1"
    saved.custom_stt_model = "whisper-large-v3-turbo"
    assert captures_route._stt_readiness(saved, cfg).ready is True


# ── Audio without librosa ─────────────────────────────────────────────────


def _stereo_wav(path, rate=48_000, seconds=1.0):
    import numpy as np
    import soundfile as sf

    t = np.arange(int(rate * seconds)) / rate
    left = 0.3 * np.sin(2 * np.pi * 440 * t)
    sf.write(path, np.stack([left, 0.5 * left], axis=1), rate, subtype="PCM_16")


@pytest.mark.parametrize("target", [16_000, 24_000, None])
@pytest.mark.parametrize("mono", [True, False])
def test_load_audio_matches_librosa(tmp_path, target, mono):
    """soundfile + soxr_hq gives librosa.load's samples, bit for bit."""
    librosa = pytest.importorskip("librosa")
    import numpy as np

    from backend.utils.audio import load_audio

    path = tmp_path / "d.wav"
    _stereo_wav(path)
    expected, expected_sr = librosa.load(path, sr=target, mono=mono)
    audio, sr = load_audio(str(path), sample_rate=target, mono=mono)
    assert sr == expected_sr
    assert audio.dtype == expected.dtype and audio.shape == expected.shape
    assert np.array_equal(audio, expected)


def test_load_audio_without_librosa_rejects_what_soundfile_cannot_read(tmp_path, monkeypatch):
    import sys

    from backend.utils.audio import UnsupportedAudioError, audio_duration, load_audio

    monkeypatch.setitem(sys.modules, "librosa", None)
    wav = tmp_path / "d.wav"
    _stereo_wav(wav, seconds=0.5)
    audio, sr = load_audio(str(wav), sample_rate=16_000)
    assert (sr, audio.shape) == (16_000, (8_000,))
    assert audio_duration(str(wav)) == 0.5

    m4a = tmp_path / "a.m4a"
    m4a.write_bytes(b"\x00\x00\x00\x18ftypM4A " + bytes(64))
    with pytest.raises(UnsupportedAudioError, match=r"\.m4a"):
        load_audio(str(m4a))
    with pytest.raises(UnsupportedAudioError):
        audio_duration(str(m4a))
