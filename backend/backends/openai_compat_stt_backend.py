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

import logging
from pathlib import Path
from typing import Optional

import httpx

logger = logging.getLogger(__name__)

# Transcription of a dictation-length clip returns in well under a second on
# hosted Whisper; the generous ceiling only matters for long file uploads.
DEFAULT_REQUEST_TIMEOUT_SECONDS = 120.0


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

    def is_loaded(self) -> bool:
        """Return ``True`` unconditionally — the remote server owns model state."""
        return True

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
    ) -> str:
        """Upload one audio file and return the transcript text.

        Mirrors ``STTBackend.transcribe`` so the capture and transcription
        services can swap between local Whisper and a remote endpoint
        transparently. ``model_size`` is ignored: the remote model is
        pinned at construction time.

        Raises:
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
        headers = {}
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"

        url = f"{self.endpoint}/audio/transcriptions"
        async with httpx.AsyncClient(timeout=self.timeout, follow_redirects=True) as client:
            try:
                with path.open("rb") as fh:
                    response = await client.post(
                        url,
                        headers=headers,
                        data=data,
                        files={"file": (path.name, fh, _mime_for(path))},
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
                raise RuntimeError(
                    f"Custom STT endpoint {url} returned HTTP {exc.response.status_code}: "
                    f"{body_preview}"
                ) from exc
            except httpx.HTTPError as exc:
                raise RuntimeError(
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


def _mime_for(path: Path) -> str:
    """Content type for the multipart file part; servers sniff on it."""
    return _MIME_BY_SUFFIX.get(path.suffix.lower(), "application/octet-stream")
