"""
STT (Speech-to-Text) module - delegates to backend abstraction layer.
"""

from typing import Optional
from ..backends import get_stt_backend, STTBackend


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


def unload_whisper_model():
    """Unload Whisper model to free memory."""
    backend = get_stt_backend()
    backend.unload_model()
