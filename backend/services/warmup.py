"""
Warm-up for the dictation path.

The first capture after the server starts paid one-off costs later captures
don't: loading the audio-decoding stack (it was librosa / soundfile and their
native libraries — measured ~1.4 s on Linux and ~3.9 s on Windows from source,
more in the frozen Windows app; now soundfile and soxr only) and, with remote
endpoints, the first TLS handshake to the STT and LLM providers (~1.5–2 s on
the first call). In the installed app the first dictation took ~15 s against
~2 s for the next ones.

- ``warm_audio_decoder`` decodes and resamples a short silent WAV once, at
  startup.
- ``warm_remote_backends`` opens the pooled connections of remote STT / LLM
  backends. It runs at startup and again whenever recording starts
  (``POST /capture/warmup``), so the handshake overlaps the user's speech
  instead of following it.
"""

import asyncio
import logging
import tempfile
import wave
from pathlib import Path

logger = logging.getLogger(__name__)

WARMUP_CLIP_SECONDS = 0.25
WARMUP_SAMPLE_RATE = 16_000


def warm_audio_decoder() -> None:
    """Decode a short silent WAV so the first real capture skips library loading."""
    from ..utils.audio import load_audio

    try:
        with tempfile.TemporaryDirectory(prefix="capture-warmup-") as tmp:
            path = Path(tmp) / "silence.wav"
            with wave.open(str(path), "wb") as w:
                w.setnchannels(1)
                w.setsampwidth(2)
                w.setframerate(WARMUP_SAMPLE_RATE)
                w.writeframes(b"\x00\x00" * int(WARMUP_CLIP_SECONDS * WARMUP_SAMPLE_RATE))
            load_audio(str(path))
    except Exception as exc:  # noqa: BLE001 — best-effort, never block startup
        logger.debug("Audio decoder warm-up failed (ignored): %s", exc)


async def warm_remote_backends() -> None:
    """Open the pooled connections of whichever STT / LLM backends are remote.

    Local backends have no ``warm`` method and are skipped; their models are
    loaded on demand as before.
    """
    from ..backends import get_llm_backend, get_stt_backend

    warmers = []
    for get_backend in (get_stt_backend, get_llm_backend):
        try:
            warm = getattr(get_backend(), "warm", None)
        except Exception as exc:  # noqa: BLE001 — best-effort
            logger.debug("Could not resolve backend for warm-up: %s", exc)
            continue
        if warm is not None:
            warmers.append(warm())
    if warmers:
        await asyncio.gather(*warmers, return_exceptions=True)


async def warm_on_startup() -> None:
    """Startup hook: decoder first (off the event loop), then remote connections."""
    await asyncio.to_thread(warm_audio_decoder)
    await warm_remote_backends()
