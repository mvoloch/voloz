"""
One-key setup: a free Groq API key configures both transcription and
refinement, so a fresh lite install needs a single paste instead of six
fields (endpoint, model and key for STT and for the LLM). An optional
Anthropic key moves refinement to Claude, which keeps closer to the
speaker's words; transcription stays on Groq.

:func:`configure_quick_setup` checks each key with one cheap call and then
stores the endpoints, models and keys through
``settings.update_capture_settings`` — the same path the settings page
uses, so the running backends switch over without a restart.
"""

import logging
from typing import Optional
from urllib.parse import urlparse

import httpx
from sqlalchemy.orm import Session

from . import settings as settings_service

logger = logging.getLogger(__name__)

GROQ_ENDPOINT = "https://api.groq.com/openai/v1"
GROQ_STT_MODEL = "whisper-large-v3-turbo"
# Refinement model when only the Groq key is given. The one place to change
# it: the default is still being measured on real dictations. Non-Claude
# models get ``refinement._KEEP_LANGUAGE_INSTRUCTION`` in their prompt.
GROQ_LLM_MODEL = "qwen/qwen3.8-27b"

ANTHROPIC_ENDPOINT = "https://api.anthropic.com/v1"
ANTHROPIC_LLM_MODEL = "claude-haiku-5-5"
_ANTHROPIC_VERSION = "2023-06-01"

_VALIDATE_TIMEOUT_SECONDS = 15.0


class QuickSetupError(Exception):
    """A key that could not be validated.

    ``status_code`` is the HTTP status to answer and ``field`` names the
    key at fault (``"groq"`` or ``"anthropic"``) so the screen can mark it.
    """

    def __init__(self, message: str, status_code: int, field: str):
        super().__init__(message)
        self.status_code = status_code
        self.field = field


async def _get(url: str, headers: dict[str, str], provider: str, field: str) -> httpx.Response:
    """GET ``url``; a refused key is a 400, anything else that fails a 502/503."""
    host = urlparse(url).hostname or url
    try:
        async with httpx.AsyncClient(timeout=_VALIDATE_TIMEOUT_SECONDS) as client:
            response = await client.get(url, headers=headers)
    except httpx.RequestError as exc:
        logger.warning("Quick setup: %s unreachable: %s", url, exc)
        raise QuickSetupError(
            f"Could not reach {host} ({exc.__class__.__name__}). Check the internet connection.",
            503,
            field,
        ) from exc
    if response.status_code in (401, 403):
        raise QuickSetupError(f"{provider} rejected this API key.", 400, field)
    return response


async def validate_groq_key(api_key: str, endpoint: Optional[str] = None) -> None:
    """Check ``api_key`` against Groq's model list, which must offer both models.

    Raises:
        QuickSetupError: 400 when Groq rejects the key, 503 when it can't be
            reached, 502 for another error or a missing model.
    """
    url = f"{(endpoint or GROQ_ENDPOINT).rstrip('/')}/models"
    response = await _get(url, {"Authorization": f"Bearer {api_key}"}, "Groq", "groq")
    if response.status_code >= 400:
        raise QuickSetupError(
            f"Groq answered HTTP {response.status_code}: {response.text[:200]}", 502, "groq"
        )
    try:
        listed = {m.get("id") for m in response.json().get("data", [])}
    except (ValueError, AttributeError) as exc:
        raise QuickSetupError("Groq sent an unexpected model list.", 502, "groq") from exc
    missing = [m for m in (GROQ_STT_MODEL, GROQ_LLM_MODEL) if m not in listed]
    if missing:
        raise QuickSetupError(f"Groq does not offer {', '.join(missing)} to this key.", 502, "groq")


async def validate_anthropic_key(api_key: str, endpoint: Optional[str] = None) -> None:
    """Check ``api_key`` by looking up the Claude refinement model (free, no tokens).

    Raises:
        QuickSetupError: 400 when Anthropic rejects the key, 503 when it
            can't be reached, 502 for another error or a missing model.
    """
    url = f"{(endpoint or ANTHROPIC_ENDPOINT).rstrip('/')}/models/{ANTHROPIC_LLM_MODEL}"
    headers = {"x-api-key": api_key, "anthropic-version": _ANTHROPIC_VERSION}
    response = await _get(url, headers, "Anthropic", "anthropic")
    if response.status_code == 404:
        raise QuickSetupError(
            f"Anthropic does not offer {ANTHROPIC_LLM_MODEL} to this key.", 502, "anthropic"
        )
    if response.status_code >= 400:
        raise QuickSetupError(
            f"Anthropic answered HTTP {response.status_code}: {response.text[:200]}",
            502,
            "anthropic",
        )


async def configure_quick_setup(
    db: Session,
    groq_api_key: str,
    anthropic_api_key: Optional[str] = None,
):
    """Validate the keys, then store transcription and refinement settings.

    Transcription always goes to Groq. Refinement goes to Claude when an
    Anthropic key is given, otherwise to Groq with the same key. Returns
    the updated capture-settings row; nothing is stored when a key fails.
    """
    groq_key = (groq_api_key or "").strip()
    anthropic_key = (anthropic_api_key or "").strip()
    if not groq_key:
        raise QuickSetupError("Paste a Groq API key.", 400, "groq")

    await validate_groq_key(groq_key)
    if anthropic_key:
        await validate_anthropic_key(anthropic_key)
        llm = (ANTHROPIC_ENDPOINT, ANTHROPIC_LLM_MODEL, anthropic_key)
    else:
        llm = (GROQ_ENDPOINT, GROQ_LLM_MODEL, groq_key)

    return settings_service.update_capture_settings(
        db,
        {
            "custom_stt_endpoint": GROQ_ENDPOINT,
            "custom_stt_model": GROQ_STT_MODEL,
            "custom_stt_api_key": groq_key,
            "custom_llm_endpoint": llm[0],
            "custom_llm_model": llm[1],
            "custom_llm_api_key": llm[2],
        },
    )
