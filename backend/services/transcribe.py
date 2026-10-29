"""
STT (Speech-to-Text) module - delegates to backend abstraction layer.
"""

import re
from typing import Iterable, Optional
from ..backends import get_stt_backend, STTBackend
from .vocabulary import build_stt_prompt


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
    returned as empty.
    """
    prompt = build_stt_prompt(vocabulary) if is_remote(backend) else None
    if not prompt:
        return await backend.transcribe(audio_path, language, model_size)
    text = await backend.transcribe(audio_path, language, model_size, prompt=prompt)
    if is_prompt_echo(text, prompt):
        return ""
    return text


def is_prompt_echo(text: str, prompt: str) -> bool:
    """Whether ``text`` is ``prompt`` again, ignoring case and punctuation."""

    def words(value: str) -> list[str]:
        return re.findall(r"\w+", value.casefold())

    return bool(words(text)) and words(text) == words(prompt)


def unload_whisper_model():
    """Unload Whisper model to free memory."""
    backend = get_stt_backend()
    backend.unload_model()
