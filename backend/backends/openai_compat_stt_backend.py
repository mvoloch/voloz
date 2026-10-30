"""
OpenAI-compatible speech-to-text backend.

Delegates transcription to any HTTP endpoint that speaks the OpenAI
``/v1/audio/transcriptions`` protocol (multipart upload) — OpenRouter,
Groq, OpenAI itself, or a self-hosted Whisper server. Skips the
on-device download / model-load path because the remote server is
expected to serve the model.

The user configures ``custom_stt_endpoint`` / ``custom_stt_model`` /
``custom_stt_api_key`` in capture settings; when the endpoint is set,
``get_stt_backend()`` returns an instance of this class instead of the
built-in Whisper backend.
"""

import asyncio
import io
import logging
from pathlib import Path
from typing import Optional

import httpx
import soundfile as sf

from . import ProviderError, STTRateLimitError, parse_retry_after

logger = logging.getLogger(__name__)

# Transcription of a dictation-length clip returns in well under a second on
# hosted Whisper; the generous ceiling only matters for long file uploads.
DEFAULT_REQUEST_TIMEOUT_SECONDS = 120.0

# Keep the TLS connection to the endpoint open between dictations so each
# capture skips the TCP + TLS handshake (~50-300 ms to a US region from
# elsewhere). httpx's default keep-alive expiry is 5 s, shorter than the
# usual gap between two dictations.
KEEPALIVE_EXPIRY_SECONDS = 120.0

# Whisper works at 16 kHz mono; anything more is just bytes to upload.
WHISPER_SAMPLE_RATE = 16_000


class OpenAICompatSTTBackend:
    """STT backend that delegates to an OpenAI-compatible transcription endpoint."""

    # Read by the routes / services layer to skip the local model-size
    # validation and download gate, and to record the remote model label.
    is_remote = True

    def __init__(
        self,
        endpoint: str,
        model: str,
        api_key: Optional[str] = None,
        timeout: float = DEFAULT_REQUEST_TIMEOUT_SECONDS,
    ):
        """Configure the backend for a specific remote endpoint.

        Args:
            endpoint: Full base URL up to and including the ``/v1``
                segment (trailing slash tolerated), e.g.
                ``https://openrouter.ai/api/v1``.
            model: Model name to send in every request's ``model`` field,
                e.g. ``openai/whisper-large-v3-turbo``.
            api_key: Optional bearer token; empty string treated the same
                as ``None`` so a blank UI field disables auth.
            timeout: Per-request timeout in seconds.
        """
        self.endpoint = endpoint.rstrip("/")
        self.model = model
        self.api_key = api_key or None
        self.timeout = timeout
        # ``model_size`` is read by the capture / transcription plumbing to
        # label results; report the remote model name so stored captures
        # say which model actually produced the transcript.
        self.model_size = model
        # One pooled client per event loop (see ``_get_client``).
        self._client: Optional[httpx.AsyncClient] = None
        self._client_loop: Optional[asyncio.AbstractEventLoop] = None

    def _get_client(self) -> httpx.AsyncClient:
        """Pooled client bound to the running event loop.

        httpx clients can't be shared across event loops, so a new loop
        (tests, a restarted server loop) gets a fresh client.
        """
        loop = asyncio.get_running_loop()
        if self._client is None or self._client_loop is not loop:
            self._client = httpx.AsyncClient(
                timeout=self.timeout,
                follow_redirects=True,
                limits=httpx.Limits(keepalive_expiry=KEEPALIVE_EXPIRY_SECONDS),
            )
            self._client_loop = loop
        return self._client

    def is_loaded(self) -> bool:
        """Return ``True`` unconditionally — the remote server owns model state."""
        return True

    async def warm(self) -> None:
        """Open the pooled connection ahead of an upload with a free request.

        A ``HEAD {endpoint}/models`` is unbilled and carries no body (a full
        ``GET`` on OpenRouter lists every model, ~1 MB), yet it pays the TCP +
        TLS handshake and certificate loading while the user is still
        speaking — any status code leaves a reusable connection in the pool.
        Errors are swallowed: warming is best-effort and must never surface
        to the dictation flow.
        """
        headers = {"Authorization": f"Bearer {self.api_key}"} if self.api_key else {}
        try:
            await self._get_client().head(f"{self.endpoint}/models", headers=headers, timeout=10.0)
        except httpx.HTTPError as exc:
            logger.debug("STT warm-up against %s failed (ignored): %s", self.endpoint, exc)

    def _is_model_cached(self, model_size: Optional[str] = None) -> bool:  # noqa: ARG002
        """Return ``True`` — there is nothing to download locally."""
        return True

    async def load_model(self, model_size: Optional[str] = None) -> None:  # noqa: ARG002
        """No-op — remote endpoint holds the model."""
        return

    async def load_model_async(self, model_size: Optional[str] = None) -> None:  # noqa: ARG002
        """No-op — remote endpoint holds the model."""
        return

    def unload_model(self) -> None:
        """No-op — nothing to unload locally."""
        return

    async def transcribe(
        self,
        audio_path: str,
        language: Optional[str] = None,
        model_size: Optional[str] = None,  # noqa: ARG002 — kept for protocol parity
        prompt: Optional[str] = None,
    ) -> str:
        """Upload one audio file and return the transcript text.

        Mirrors ``STTBackend.transcribe`` so the capture and transcription
        services can swap between local Whisper and a remote endpoint
        transparently. ``model_size`` is ignored: the remote model is
        pinned at construction time. ``prompt`` is sent as Whisper's
        ``prompt`` field — text that biases decoding toward the names and
        spellings it contains (Groq and OpenAI honour it; the caller keeps
        it under Whisper's 224-token limit).

        Raises:
            STTRateLimitError: The remote returned HTTP 429.
            RuntimeError: The remote returned a non-2xx status, could not be
                reached (timeout, DNS, TLS), or sent a non-JSON body. The
                message names the endpoint so the capture UI shows something
                actionable.
            ValueError: The response was well-formed JSON but carried no
                ``text`` field.
        """
        path = Path(audio_path)
        data = {"model": self.model, "response_format": "json"}
        if language:
            data["language"] = language
        if prompt:
            data["prompt"] = prompt
        headers = {}
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"

        url = f"{self.endpoint}/audio/transcriptions"
        client = self._get_client()
        try:
            # Dictations arrive as 48 kHz WAV from the webview: three times the
            # upload Whisper needs, and long ones would pass the 25 MB request
            # limit hosted endpoints set. Compressed formats go as they are.
            if path.suffix.lower() == ".wav":
                upload, name, mime = await asyncio.to_thread(_upload_for_whisper, path)
            else:
                upload, name, mime = path.read_bytes(), path.name, _mime_for(path)
            response = await client.post(
                url,
                headers=headers,
                data=data,
                files={"file": (name, upload, mime)},
            )
            response.raise_for_status()
        except httpx.HTTPStatusError as exc:
            body_preview = exc.response.text[:500]
            logger.error(
                "OpenAI-compat STT endpoint %s returned %d: %s",
                url,
                exc.response.status_code,
                body_preview,
            )
            status = exc.response.status_code
            error_cls = STTRateLimitError if status == 429 else ProviderError
            raise error_cls(
                f"Custom STT endpoint {url} returned HTTP {status}: {body_preview}",
                status_code=status,
                retry_after=parse_retry_after(exc.response.headers),
            ) from exc
        except httpx.HTTPError as exc:
            raise ProviderError(
                f"Custom STT endpoint {url} is unreachable: {exc.__class__.__name__}: {exc}"
            ) from exc

        try:
            payload = response.json()
        except ValueError as exc:
            raise RuntimeError(
                f"Custom STT endpoint {url} returned a non-JSON body: {response.text[:200]}"
            ) from exc

        text = payload.get("text") if isinstance(payload, dict) else None
        if not isinstance(text, str):
            raise ValueError(f"No transcript text in response from {url}: {payload!r}")
        return text.strip()


_MIME_BY_SUFFIX = {
    ".wav": "audio/wav",
    ".mp3": "audio/mpeg",
    ".m4a": "audio/mp4",
    ".flac": "audio/flac",
    ".ogg": "audio/ogg",
    ".webm": "audio/webm",
}


def _upload_for_whisper(path: Path) -> tuple[bytes, str, str]:
    """A WAV dictation as 16 kHz mono 16-bit FLAC: ``(bytes, filename, mime)``.

    FLAC is lossless and about half the size of the WAV for speech (12 of
    the owner's dictations: 45% smaller, 1-4 ms to encode), and upload time
    grows with size (~10 ms per second of audio on the Dell). Falls back to
    WAV when this libsndfile build has no FLAC encoder.
    """
    from ..utils.audio import load_audio

    info = sf.info(str(path))
    if info.samplerate <= WHISPER_SAMPLE_RATE and info.channels == 1:
        audio, sr = sf.read(str(path), dtype="int16")
    else:
        audio, sr = load_audio(str(path), sample_rate=WHISPER_SAMPLE_RATE)
    buf = io.BytesIO()
    if "FLAC" in sf.available_formats():
        sf.write(buf, audio, sr, format="FLAC", subtype="PCM_16")
        return buf.getvalue(), f"{path.stem}.flac", "audio/flac"
    sf.write(buf, audio, sr, format="WAV", subtype="PCM_16")
    return buf.getvalue(), path.name, "audio/wav"


def _mime_for(path: Path) -> str:
    """Content type for the multipart file part; servers sniff on it."""
    return _MIME_BY_SUFFIX.get(path.suffix.lower(), "application/octet-stream")
