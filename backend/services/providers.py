"""
Backup providers for dictation: when the configured transcription or
refinement endpoint fails (rate limit, server error, timeout, bad key), the
next configured provider gets the same request.

The user lists backups per kind in capture settings (``stt_fallbacks`` /
``llm_fallbacks``, see ``services/settings.py``); keys come from the saved
keys for each provider's host. A provider that answered HTTP 429 is skipped
until the wait it asked for (or ``DEFAULT_COOLDOWN_SECONDS``) has passed, so
a spent free tier doesn't cost a failed round trip on every dictation. When
every provider is cooling down they are all tried anyway, in order.
"""

import logging
import time
from logging.handlers import RotatingFileHandler
from pathlib import Path
from typing import Awaitable, Callable, TypeVar

from ..backends import ProviderError, get_fallback_backends

logger = logging.getLogger(__name__)

T = TypeVar("T")

DEFAULT_COOLDOWN_SECONDS = 60.0
MAX_COOLDOWN_SECONDS = 600.0

# (kind, endpoint, model) -> time.monotonic() until which it is skipped.
_cooldown_until: dict[tuple[str, str, str], float] = {}


def enable_file_log(log_dir: Path) -> None:
    """Also write this module's records to ``<log_dir>/providers.log``.

    The server's own log only reaches stderr, which a packaged app on Windows
    throws away; this file keeps why a provider failed (Groq's rate-limit
    message, say) and when a backup answered. Calling it again is a no-op.
    """
    path = Path(log_dir) / "providers.log"
    if any(getattr(h, "baseFilename", None) == str(path) for h in logger.handlers):
        return
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        handler = RotatingFileHandler(path, maxBytes=256_000, backupCount=1, encoding="utf-8")
    except OSError as exc:
        logger.warning("providers.log unavailable: %s", exc)
        return
    handler.setFormatter(logging.Formatter("%(asctime)s %(message)s"))
    logger.addHandler(handler)
    logger.setLevel(logging.INFO)


def _describe(exc: ProviderError) -> str:
    """Why a provider failed, for the log: status and the provider's reason,
    never the raw body (some servers echo the prompt into it)."""
    if exc.status_code is None:
        return str(exc)
    return f"HTTP {exc.status_code}: {exc.reason}" if exc.reason else f"HTTP {exc.status_code}"


def _identity(kind: str, backend) -> tuple[str, str, str]:
    return (kind, getattr(backend, "endpoint", "") or "", getattr(backend, "model", "") or "")


def label(backend) -> str:
    """Short name for logs: the model and the host it runs on."""
    endpoint = getattr(backend, "endpoint", "") or "local"
    return f"{getattr(backend, 'model', '?')} @ {endpoint}"


def _order(kind: str, primary) -> list:
    backups = get_fallback_backends(kind) if getattr(primary, "is_remote", False) else []
    seen, candidates = set(), []
    for backend in [primary, *backups]:
        ident = _identity(kind, backend)
        if ident not in seen:
            seen.add(ident)
            candidates.append(backend)
    now = time.monotonic()
    ready = [b for b in candidates if _cooldown_until.get(_identity(kind, b), 0.0) <= now]
    return ready or candidates


async def run_with_fallback(kind: str, primary, call: Callable[[object], Awaitable[T]]) -> tuple[object, T]:
    """Run ``call(backend)`` on ``primary``, then on each backup after a ``ProviderError``.

    Returns ``(backend that answered, result)``. Other exceptions (an empty
    or malformed answer) are raised at once: another provider would get the
    same input. When every provider fails, the last error is raised, so a
    429 still reads as a rate limit to the caller.
    """
    last_error: ProviderError | None = None
    for backend in _order(kind, primary):
        try:
            result = await call(backend)
        except ProviderError as exc:
            last_error = exc
            skipped = ""
            if exc.status_code == 429:
                wait = min(exc.retry_after or DEFAULT_COOLDOWN_SECONDS, MAX_COOLDOWN_SECONDS)
                _cooldown_until[_identity(kind, backend)] = time.monotonic() + wait
                skipped = f"; skipped for {wait:.0f} s"
            logger.warning("%s provider %s failed (%s)%s", kind, label(backend), _describe(exc), skipped)
            continue
        _cooldown_until.pop(_identity(kind, backend), None)
        if backend is not primary:
            logger.warning("%s served by backup provider %s", kind, label(backend))
        return backend, result
    assert last_error is not None
    raise last_error


def reset_cooldowns() -> None:
    """Forget every cooldown (tests, and after the user changes providers)."""
    _cooldown_until.clear()
