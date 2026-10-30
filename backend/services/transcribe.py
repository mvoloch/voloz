"""
STT (Speech-to-Text) module - delegates to backend abstraction layer.
"""

import logging
import re
from typing import Iterable, Optional
from ..backends import get_stt_backend, STTBackend
from ..utils.audio import has_speech, load_audio
from . import providers
from .vocabulary import build_stt_prompt

logger = logging.getLogger(__name__)


def get_whisper_model() -> STTBackend:
    """
    Get STT backend instance (remote, MLX or PyTorch based on settings/platform).

    Returns:
        STT backend instance
    """
    return get_stt_backend()


def is_remote(backend: STTBackend) -> bool:
    """Whether ``backend`` is a remote endpoint with no local model sizes."""
    return bool(getattr(backend, "is_remote", False))


def resolve_stt_model(backend: STTBackend, requested: Optional[str]) -> str:
    """Model label to transcribe with and record for a call on ``backend``.

    A remote endpoint ignores Whisper size requests (base / small / turbo…)
    and reports its own model name, so the label persisted on captures must
    come from the backend, not the request.
    """
    if is_remote(backend):
        return backend.model_size
    return requested or backend.model_size


async def transcribe_with_vocabulary(
    backend: STTBackend,
    audio_path: str,
    language: Optional[str],
    model_size: Optional[str],
    vocabulary: Optional[Iterable[str]] = None,
) -> str:
    """``backend.transcribe`` with the user's vocabulary as the Whisper prompt.

    Only a remote endpoint receives the prompt; the local Whisper backends
    (PyTorch, MLX) take none yet and are called exactly as before.

    On silence Whisper tends to return its prompt verbatim (Groq, 3 of 3
    runs on a silent clip with the prompt "Voloz"), which would be pasted
    as if dictated. A transcript that is nothing but the prompt is
    returned as empty when the audio is (near) silent; with speech in it,
    the user said the word ("Voloz" alone) and it is kept.
    """
    prompt = build_stt_prompt(vocabulary) if is_remote(backend) else None
    if not prompt:
        return await backend.transcribe(audio_path, language, model_size)
    text = await backend.transcribe(audio_path, language, model_size, prompt=prompt)
    if is_prompt_echo(text, prompt) and not _audio_has_speech(audio_path):
        return ""
    return text


async def transcribe_with_fallback(
    audio_path: str,
    language: Optional[str],
    requested_model: Optional[str],
    vocabulary: Optional[Iterable[str]] = None,
) -> tuple[str, str]:
    """:func:`transcribe_with_vocabulary` on the configured endpoint, then its backups.

    Returns ``(transcript, model label)`` of the provider that answered.
    """

    async def transcribe_on(backend) -> str:
        model = resolve_stt_model(backend, requested_model)
        return await transcribe_with_vocabulary(backend, audio_path, language, model, vocabulary)

    backend, text = await providers.run_with_fallback("stt", get_whisper_model(), transcribe_on)
    return text, resolve_stt_model(backend, requested_model)


def _audio_has_speech(audio_path: str) -> bool:
    """:func:`has_speech` for a file; ``False`` when it can't be decoded."""
    try:
        audio, sr = load_audio(audio_path, sample_rate=16000)
    except Exception as exc:
        logger.warning("Could not read %s to check for speech: %r", audio_path, exc)
        return False
    return has_speech(audio, sr)


def is_prompt_echo(text: str, prompt: str) -> bool:
    """Whether ``text`` is ``prompt`` again, ignoring case and punctuation."""

    def words(value: str) -> list[str]:
        return re.findall(r"\w+", value.casefold())

    return bool(words(text)) and words(text) == words(prompt)


def unload_whisper_model():
    """Unload Whisper model to free memory."""
    backend = get_stt_backend()
    backend.unload_model()
