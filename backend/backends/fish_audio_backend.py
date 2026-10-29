"""
Fish Audio cloud TTS backend.

Generates speech on Fish Audio's hosted API (``POST /v1/tts``) instead of a
local model, cloning the voice zero-shot from the profile's own reference
sample: the sample audio and its transcript travel inline with every request
as ``references``, so nothing is created or stored on Fish Audio's side.

Request format (https://docs.fish.audio/api-reference/endpoint/openapi-v1/text-to-speech):
inline ``references`` require a MessagePack body; the model is picked with
the ``model`` header; the response body is the audio itself, here WAV.

There is nothing to download or load: the engine is ready as soon as an API
key is stored (Settings → Generation), and it never counts as a local model.
No torch or other ML library is imported here — only httpx, msgpack and
soundfile.
"""

import asyncio
import io
import logging
from pathlib import Path
from typing import List, Optional, Tuple

import httpx
import numpy as np
import soundfile as sf

logger = logging.getLogger(__name__)

FISH_AUDIO_TTS_URL = "https://api.fish.audio/v1/tts"

# Sent in the ``model`` header so the price is pinned: s2.1-pro is billed at
# $15 per million UTF-8 bytes of input text (pay as you go). A missing or
# unknown header also falls back to s2.1-pro today, but saying it keeps a
# future default change from silently changing the bill.
FISH_AUDIO_MODEL = "s2.1-pro"

# WAV decodes with soundfile alone; 44.1 kHz is Fish Audio's WAV default.
FISH_AUDIO_SAMPLE_RATE = 44_100

# A chunk of up to a few hundred characters renders in seconds; the ceiling
# covers the largest chunk size the Generation settings allow (5000 chars).
DEFAULT_REQUEST_TIMEOUT_SECONDS = 180.0

# Reference formats Fish Audio accepts as-is; anything else is re-encoded.
_ACCEPTED_REFERENCE_SUFFIXES = {".wav", ".mp3", ".flac"}

MISSING_KEY_MESSAGE = (
    "Fish Audio needs an API key. Create one at https://fish.audio/app/api-keys/ "
    "and paste it in Settings → Generation → Fish Audio API key."
)


class FishAudioError(RuntimeError):
    """A Fish Audio request failed; the message tells the user what to do."""


class FishAudioTTSBackend:
    """TTS backend that clones the profile's voice on Fish Audio's cloud API."""

    # Read by the routes / services layer: there is no local model to
    # download, load or delete.
    is_remote = True

    def __init__(
        self,
        api_url: str = FISH_AUDIO_TTS_URL,
        model: str = FISH_AUDIO_MODEL,
        timeout: float = DEFAULT_REQUEST_TIMEOUT_SECONDS,
        transport: Optional[httpx.AsyncBaseTransport] = None,
    ):
        """Configure the backend.

        Args:
            api_url: Full URL of the TTS endpoint.
            model: Fish Audio model, sent in the ``model`` header.
            timeout: Per-request timeout in seconds.
            transport: Optional httpx transport (tests pass a mock one).
        """
        self.api_url = api_url
        self.model = model
        self.timeout = timeout
        self._transport = transport
        self._client: Optional[httpx.AsyncClient] = None
        self._client_loop: Optional[asyncio.AbstractEventLoop] = None

    # ── Key and availability ────────────────────────────────────────────

    @staticmethod
    def _api_key() -> Optional[str]:
        """The key stored in Settings → Generation, read at call time."""
        from . import get_fish_audio_api_key

        return get_fish_audio_api_key()

    def unavailable_reason(self) -> Optional[str]:
        """Why the engine can't run right now, or ``None`` when it can."""
        return None if self._api_key() else MISSING_KEY_MESSAGE

    def _get_client(self) -> httpx.AsyncClient:
        """Pooled client bound to the running event loop.

        httpx clients can't be shared across event loops, so a new loop
        (tests, a restarted server loop) gets a fresh client.
        """
        loop = asyncio.get_running_loop()
        if self._client is None or self._client_loop is not loop:
            self._client = httpx.AsyncClient(timeout=self.timeout, transport=self._transport)
            self._client_loop = loop
        return self._client

    # ── TTSBackend protocol ────────────────────────────────────────────

    def is_loaded(self) -> bool:
        """Return ``True`` — Fish Audio holds the model, nothing loads here."""
        return True

    async def load_model(self, model_size: Optional[str] = None) -> None:  # noqa: ARG002
        """Nothing to load; fail early, before any audio work, without a key."""
        reason = self.unavailable_reason()
        if reason:
            raise FishAudioError(reason)

    def unload_model(self) -> None:
        """No-op — nothing is held locally."""
        return

    def _get_model_path(self, model_size: str) -> str:  # noqa: ARG002
        return self.api_url

    async def create_voice_prompt(
        self,
        audio_path: str,
        reference_text: str,
        use_cache: bool = True,  # noqa: ARG002 — nothing to cache, the sample goes as is
    ) -> Tuple[dict, bool]:
        """Point the prompt at the reference sample; it is read at request time."""
        return {"ref_audio": str(audio_path), "ref_text": reference_text}, False

    async def combine_voice_prompts(
        self,
        audio_paths: List[str],
        reference_texts: List[str],
    ) -> Tuple[np.ndarray, str]:
        """Join several samples into one reference, like the local engines."""
        from .base import combine_voice_prompts as _combine_voice_prompts

        return await _combine_voice_prompts(audio_paths, reference_texts)

    async def generate(
        self,
        text: str,
        voice_prompt: dict,
        language: str = "en",
        seed: Optional[int] = None,  # noqa: ARG002 — the API takes no seed
        instruct: Optional[str] = None,  # noqa: ARG002 — the API takes no instruct
    ) -> Tuple[np.ndarray, int]:
        """Synthesise ``text`` in the profile's voice on Fish Audio.

        Returns:
            Tuple of (mono float32 audio, sample rate).

        Raises:
            FishAudioError: No key, or the request failed; the message says
                what the user can do about it.
            ValueError: The voice prompt carries no reference sample.
        """
        api_key = self._api_key()
        if not api_key:
            raise FishAudioError(MISSING_KEY_MESSAGE)

        ref_audio = voice_prompt.get("ref_audio")
        if not ref_audio:
            raise ValueError("Fish Audio clones from a reference sample; this voice profile has none.")
        reference_bytes = await asyncio.to_thread(_reference_audio_bytes, Path(ref_audio))

        payload = {
            "text": text,
            "references": [{"audio": reference_bytes, "text": voice_prompt.get("ref_text") or ""}],
            "format": "wav",
            "sample_rate": FISH_AUDIO_SAMPLE_RATE,
            # Only steers number/date normalisation; Portuguese is detected
            # only when detection is confident, so say it.
            "language": language,
        }
        body = _pack_msgpack(payload)
        headers = {
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/msgpack",
            "model": self.model,
        }

        try:
            response = await self._get_client().post(self.api_url, content=body, headers=headers)
        except httpx.TimeoutException as exc:
            raise FishAudioError(
                f"Fish Audio ({self.api_url}) did not answer within {self.timeout:.0f} s. "
                "Try again, or lower the chunk size in Settings → Generation."
            ) from exc
        except httpx.HTTPError as exc:
            raise FishAudioError(
                f"Could not reach Fish Audio ({self.api_url}): {exc.__class__.__name__}: {exc}. "
                "Check the internet connection and try again."
            ) from exc

        if response.status_code != 200:
            raise FishAudioError(_error_message(self.api_url, response))

        try:
            audio, sample_rate = await asyncio.to_thread(_decode_wav, response.content)
        except Exception as exc:
            content_type = response.headers.get("content-type", "unknown")
            raise FishAudioError(
                f"Fish Audio ({self.api_url}) returned audio that could not be decoded "
                f"(content-type {content_type}, {len(response.content)} bytes): {exc}"
            ) from exc
        return audio, sample_rate


def _pack_msgpack(payload: dict) -> bytes:
    """MessagePack-encode the request; ``bytes`` values go as msgpack bin."""
    import msgpack

    return msgpack.packb(payload, use_bin_type=True)


def _reference_audio_bytes(path: Path) -> bytes:
    """The sample as WAV/MP3/FLAC bytes, re-encoding other formats to WAV."""
    if path.suffix.lower() in _ACCEPTED_REFERENCE_SUFFIXES:
        return path.read_bytes()
    from ..utils.audio import load_audio

    audio, sr = load_audio(str(path))
    buf = io.BytesIO()
    sf.write(buf, audio, sr, format="WAV", subtype="PCM_16")
    return buf.getvalue()


def _decode_wav(data: bytes) -> Tuple[np.ndarray, int]:
    """Decode the response body into mono float32 samples."""
    audio, sample_rate = sf.read(io.BytesIO(data), dtype="float32")
    if audio.ndim > 1:
        audio = audio.mean(axis=1)
    if audio.size == 0:
        raise ValueError("no audio samples")
    return audio, int(sample_rate)


def _provider_detail(response: httpx.Response) -> str:
    """Fish Audio's own error text (JSON ``message`` / ``reason``), trimmed."""
    try:
        payload = response.json()
    except ValueError:
        return response.text[:300].strip()
    if isinstance(payload, dict):
        parts = [str(payload[k]) for k in ("message", "reason", "detail") if payload.get(k)]
        if parts:
            return " — ".join(parts)[:300]
    return str(payload)[:300]


def _error_message(url: str, response: httpx.Response) -> str:
    """Turn a non-200 answer into a message the user can act on."""
    status = response.status_code
    detail = _provider_detail(response)
    suffix = f" Fish Audio said: {detail}" if detail else ""
    logger.error("Fish Audio %s returned %d: %s", url, status, detail)

    if status in (401, 403):
        return (
            f"Fish Audio rejected the API key (HTTP {status}). Check or replace the key in "
            f"Settings → Generation → Fish Audio API key.{suffix}"
        )
    if status == 402:
        return (
            "Fish Audio has no API credit left (HTTP 402). Top up at "
            f"https://fish.audio/app/billing, then retry.{suffix}"
        )
    if status == 429:
        return (
            "Fish Audio is limiting requests (HTTP 429): too many at once for this account. "
            f"Wait a few seconds and retry.{suffix}"
        )
    if status == 413 or (status in (400, 422) and _mentions_length(detail)):
        return (
            f"The text or the voice sample is too long for one Fish Audio request (HTTP {status}). "
            "Lower the chunk size in Settings → Generation, or use a shorter sample "
            f"(10-30 s is recommended).{suffix}"
        )
    if status in (400, 422):
        return f"Fish Audio rejected the request (HTTP {status}).{suffix}"
    if status >= 500:
        return (
            f"Fish Audio is having trouble (HTTP {status}). Try again in a few minutes.{suffix}"
        )
    return f"Fish Audio ({url}) returned HTTP {status}.{suffix}"


def _mentions_length(detail: str) -> bool:
    lowered = detail.lower()
    return any(word in lowered for word in ("too long", "length", "too large", "exceed"))
