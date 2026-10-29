"""
Tests for tagged refinement output with remote Claude models.

Claude sometimes follows the cleaned transcript with a note about its
edits; refine_transcript() asks Claude for <transcript> tags and keeps only
the tagged text. Local models keep the plain prompt and output.

Usage:
    python -m pytest backend/tests/test_refinement_tagged.py -v
"""

import asyncio

from backend.backends.anthropic_backend import AnthropicLLMBackend
from backend.services import llm as llm_service
from backend.services import refinement


def test_extract_keeps_only_the_tagged_text():
    reply = (
        "<transcript>Olá, o ditado deve funcionar só com a nuvem.</transcript>\n\n"
        'Cleaning up: "quer dizer" was removed.'
    )
    assert refinement.extract_tagged_transcript(reply) == "Olá, o ditado deve funcionar só com a nuvem."


def test_extract_without_tags_returns_the_reply():
    assert refinement.extract_tagged_transcript("Plain reply.") == "Plain reply."


def _fake_claude(reply):
    backend = object.__new__(AnthropicLLMBackend)
    calls = {}

    async def generate(**kwargs):
        calls.update(kwargs)
        return reply

    backend.generate = generate
    return backend, calls


def _patch_llm(monkeypatch, backend):
    monkeypatch.setattr(llm_service, "get_llm_model", lambda: backend)
    monkeypatch.setattr(llm_service, "resolve_model_size", lambda b, size: "remote")


def test_claude_gets_tagged_prompt_and_examples(monkeypatch):
    backend, calls = _fake_claude("<transcript>Hello there.</transcript>\n\nNote: removed um.")
    _patch_llm(monkeypatch, backend)

    text, _size = asyncio.run(refinement.refine_transcript("um hello there", refinement.RefinementFlags()))

    assert text == "Hello there."
    assert "<transcript></transcript>" in calls["system"]
    assert all(
        assistant.startswith("<transcript>") and assistant.endswith("</transcript>")
        for _user, assistant in calls["examples"]
    )


def test_local_backend_keeps_plain_format(monkeypatch):
    calls = {}

    class LocalBackend:
        async def generate(self, **kwargs):
            calls.update(kwargs)
            return "Hello there."

    _patch_llm(monkeypatch, LocalBackend())

    text, _size = asyncio.run(refinement.refine_transcript("um hello there", refinement.RefinementFlags()))

    assert text == "Hello there."
    assert "<transcript>" not in calls["system"]
    assert calls["examples"] == refinement.REFINEMENT_EXAMPLES
