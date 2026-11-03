"""
Tests for dropping a refinement that is not a cleanup of the transcript.

Qwen on Groq translated Portuguese dictations to English despite the
keep-language instruction; refine_transcript() keeps the transcript when
too few of the refined words are in it.

Usage:
    python -m pytest backend/tests/test_refinement_strayed.py -v
"""

import asyncio

from backend.services import llm as llm_service
from backend.services import refinement

PORTUGUESE = (
    "Ok, eu quero que você faça o seguinte: na apresentação, você fazer um apêndice no final "
    "explicando exatamente os cálculos, sempre em inglês."
)
TRANSLATED = (
    "Ok, I want you to do the following: in the presentation, create an appendix at the end "
    "explaining exactly the calculations, always in English."
)


def test_translation_strayed():
    assert refinement.strayed_from_transcript(PORTUGUESE, TRANSLATED)


def test_cleanup_and_spelling_fixes_did_not_stray():
    raw = "então um a gente vai desminuir o custo agora a pouco né"
    cleaned = "Então a gente vai diminuir o custo agora há pouco, né?"
    assert not refinement.strayed_from_transcript(raw, cleaned)


def test_self_correction_did_not_stray():
    raw = "it has three hundred k no no no actually four hundred k stars on github today"
    assert not refinement.strayed_from_transcript(raw, "It has four hundred k stars on GitHub today.")


def test_short_text_is_not_judged():
    assert not refinement.strayed_from_transcript("um yeah", "Yeah.")


class _Backend:
    endpoint = "https://api.groq.com/openai/v1"
    model = "qwen/qwen3.8-27b"

    def __init__(self, reply):
        self.reply = reply

    async def generate(self, **kwargs):
        return self.reply


def _patch_llm(monkeypatch, backend):
    monkeypatch.setattr(llm_service, "get_llm_model", lambda: backend)
    monkeypatch.setattr(llm_service, "resolve_model_size", lambda b, size: "remote")


def test_translated_refinement_keeps_the_transcript(monkeypatch):
    _patch_llm(monkeypatch, _Backend(TRANSLATED))
    text, _size = asyncio.run(refinement.refine_transcript(PORTUGUESE, refinement.RefinementFlags()))
    assert text == PORTUGUESE


def test_cleanup_is_kept(monkeypatch):
    cleaned = PORTUGUESE.replace("você fazer", "faça")
    _patch_llm(monkeypatch, _Backend(cleaned))
    text, _size = asyncio.run(refinement.refine_transcript(PORTUGUESE, refinement.RefinementFlags()))
    assert text == cleaned
