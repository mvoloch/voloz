"""
Concurrent model loads on one backend run once, not once per caller.
"""

import asyncio
import threading
import time

import pytest

from backend.backends.pytorch_backend import PyTorchSTTBackend


@pytest.mark.asyncio
async def test_concurrent_loads_load_the_model_once(monkeypatch):
    backend = PyTorchSTTBackend()
    loads = []
    lock = threading.Lock()

    def fake_load(model_size):
        with lock:
            loads.append(model_size)
        time.sleep(0.2)
        backend.model = object()
        backend.model_size = model_size

    monkeypatch.setattr(backend, "_load_model_sync", fake_load)

    await asyncio.gather(*(backend.load_model_async("base") for _ in range(3)))

    assert loads == ["base"]
