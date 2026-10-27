"""
Anthropic (Claude) LLM backend.

Selected by ``get_llm_backend()`` when the custom LLM endpoint points at
the Claude API (``https://api.anthropic.com``). Uses the official
``anthropic`` SDK and the native Messages API instead of the
OpenAI-compatibility layer: that layer is documented by Anthropic as a
test surface, and current Claude models reject the ``temperature`` field
the generic OpenAI-compat backend always sends.

Refinement and personality rewrites are short, latency-sensitive calls,
so requests run at ``effort: "low"`` with adaptive thinking — the model
skips thinking on simple inputs — and never send sampling parameters.
"""

import logging
from typing import Optional
from urllib.parse import urlparse

import anthropic

from . import DEFAULT_LLM_MAX_TOKENS, DEFAULT_LLM_TEMPERATURE

logger = logging.getLogger(__name__)

DEFAULT_REQUEST_TIMEOUT_SECONDS = 60.0

# Refinement is a cleanup pass, not a reasoning task — low effort keeps
# latency down. Thinking tokens count toward ``max_tokens``, so the cap
# gets headroom on top of what the caller asked for the reply itself.
REFINEMENT_EFFORT = "low"
THINKING_HEADROOM_TOKENS = 1024


def is_anthropic_endpoint(endpoint: Optional[str]) -> bool:
    """Whether a custom LLM endpoint URL targets the Claude API."""
    if not endpoint:
        return False
    host = (urlparse(endpoint).hostname or "").lower()
    return host == "anthropic.com" or host.endswith(".anthropic.com")


class AnthropicLLMBackend:
    """LLM backend that calls the Claude Messages API."""

    # Read by ``llm.resolve_model_size`` to record the remote model label.
    is_remote = True

    def __init__(
        self,
        endpoint: str,
        model: str,
        api_key: Optional[str] = None,
        timeout: float = DEFAULT_REQUEST_TIMEOUT_SECONDS,
    ):
        """Configure the backend.

        Args:
            endpoint: Claude API base URL. Users paste the OpenAI-style
                ``https://api.anthropic.com/v1``; the SDK appends
                ``/v1/messages`` itself, so a trailing ``/v1`` is dropped.
            model: Claude model ID, e.g. ``claude-haiku-5-5``.
            api_key: Console API key. ``None`` lets the SDK fall back to
                ``ANTHROPIC_API_KEY`` and its other credential sources.
            timeout: Per-request timeout in seconds.
        """
        base_url = endpoint.rstrip("/")
        if base_url.endswith("/v1"):
            base_url = base_url[: -len("/v1")]
        self.endpoint = base_url
        self.model = model
        self.model_size = model
        self._current_model_size = model
        self._client = anthropic.AsyncAnthropic(
            api_key=api_key or None,
            base_url=base_url,
            timeout=timeout,
        )

    def is_loaded(self) -> bool:
        """Return ``True`` unconditionally — the remote API owns model state."""
        return True

    async def load_model(self, model_size: Optional[str] = None) -> None:  # noqa: ARG002
        """No-op — remote API holds the model."""
        return

    def unload_model(self) -> None:
        """No-op — nothing to unload locally."""
        return

    async def generate(
        self,
        prompt: str,
        system: Optional[str] = None,
        max_tokens: int = DEFAULT_LLM_MAX_TOKENS,
        temperature: float = DEFAULT_LLM_TEMPERATURE,  # noqa: ARG002 — rejected by current Claude models
        model_size: Optional[str] = None,  # noqa: ARG002 — kept for protocol parity
        examples: Optional[list[tuple[str, str]]] = None,
    ) -> str:
        """Run one Messages API call and return the assistant text.

        Mirrors ``LLMBackend.generate`` so refinement and personality
        services can swap between local Qwen and Claude transparently.
        ``temperature`` is accepted for protocol parity and not sent.

        Raises:
            RuntimeError: The API returned an error status, could not be
                reached, or declined the request (``stop_reason: refusal``).
            ValueError: The response carried no text block.
        """
        messages: list[dict] = []
        for user_text, assistant_text in examples or []:
            messages.append({"role": "user", "content": user_text})
            messages.append({"role": "assistant", "content": assistant_text})
        messages.append({"role": "user", "content": prompt})

        kwargs = {}
        if system:
            kwargs["system"] = system
        try:
            response = await self._client.messages.create(
                model=self.model,
                max_tokens=max_tokens + THINKING_HEADROOM_TOKENS,
                messages=messages,
                output_config={"effort": REFINEMENT_EFFORT},
                **kwargs,
            )
        except anthropic.APIStatusError as exc:
            logger.error(
                "Claude API %s returned %d: %s", self.endpoint, exc.status_code, exc.message
            )
            raise RuntimeError(
                f"Claude API {self.endpoint} returned HTTP {exc.status_code}: {exc.message}"
            ) from exc
        except anthropic.APIConnectionError as exc:
            raise RuntimeError(f"Claude API {self.endpoint} is unreachable: {exc}") from exc

        if response.stop_reason == "refusal":
            raise RuntimeError(f"Claude ({self.model}) declined to process this text")
        text = "".join(block.text for block in response.content if block.type == "text").strip()
        if not text:
            raise ValueError(f"No text in Claude response (stop_reason={response.stop_reason})")
        return text
