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
    """Block torch & co. in a fresh interpreter, build the app, hit /health."""
    script = """
import sys
for name in ("torch", "torchaudio", "transformers", "qwen_tts", "pedalboard", "fastmcp"):
    sys.modules[name] = None  # any import of them raises ImportError
import tempfile
from backend import config
config.set_data_dir(tempfile.mkdtemp())
from starlette.testclient import TestClient
from backend.app import create_app
with TestClient(create_app()) as client:
    assert client.get("/health").json()["gpu_available"] is False
    assert client.get("/captures?limit=1").status_code == 200
    assert client.get("/profiles").status_code == 404
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
