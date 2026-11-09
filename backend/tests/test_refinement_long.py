"""Long transcripts must come back whole.

A refinement is capped at ``REFINE_MAX_TOKENS`` of output. A 38-minute file
(29,962 characters) came back with 7,984: the model stopped at the cap and the
cut text was stored as the refinement. Long transcripts are now refined in
pieces, and a piece whose reply lost most of its words stays as transcribed.
"""

import asyncio
import re

from backend.services import llm as llm_service, refinement

WORD = re.compile(r"\w+")


def sentences(count: int) -> str:
    return " ".join(f"Esta é a frase número {i} do arquivo longo." for i in range(count))


class _Backend:
    endpoint = "https://api.groq.com/openai/v1"
    model = "qwen/qwen3.8-27b"

    def __init__(self, reply=lambda prompt: prompt):
        self.reply = reply
        self.calls = []

    async def generate(self, **kwargs):
        self.calls.append(kwargs)
        return self.reply(kwargs["prompt"])


def _patch_llm(monkeypatch, backend):
    monkeypatch.setattr(llm_service, "get_llm_model", lambda: backend)
    monkeypatch.setattr(llm_service, "resolve_model_size", lambda b, size: "remote")


def _refine(text, **kwargs):
    return asyncio.run(refinement.refine_transcript(text, refinement.RefinementFlags(), **kwargs))


def test_short_text_is_one_piece():
    assert refinement.split_for_refinement("Uma frase curta.") == ["Uma frase curta."]


def test_pieces_keep_every_word_and_end_on_sentences():
    text = sentences(900)
    pieces = refinement.split_for_refinement(text)
    assert len(pieces) > 1
    assert all(len(p) <= refinement.REFINE_PIECE_CHARS for p in pieces)
    assert all(p.endswith(".") for p in pieces)
    assert " ".join(pieces) == text


def test_a_sentence_longer_than_a_piece_is_cut_between_words():
    text = " ".join(f"palavra{i}" for i in range(3000))
    pieces = refinement.split_for_refinement(text)
    assert all(len(p) <= refinement.REFINE_PIECE_CHARS for p in pieces)
    assert " ".join(pieces) == text


def test_text_without_spaces_is_still_split():
    text = "字" * (refinement.REFINE_PIECE_CHARS * 2 + 10)
    pieces = refinement.split_for_refinement(text)
    assert all(len(p) <= refinement.REFINE_PIECE_CHARS for p in pieces)
    assert "".join(pieces) == text


def test_long_transcript_comes_back_whole(monkeypatch):
    backend = _Backend()
    _patch_llm(monkeypatch, backend)
    text = sentences(900)
    refined, _size = _refine(text)
    assert refined == text
    assert len(backend.calls) > 1


def test_dictation_of_normal_length_is_a_single_call(monkeypatch):
    backend = _Backend()
    _patch_llm(monkeypatch, backend)
    _refine(sentences(50))
    assert len(backend.calls) == 1


def test_a_reply_cut_short_keeps_that_piece_as_transcribed(monkeypatch):
    def cut_the_first_piece(prompt):
        return prompt[: len(prompt) // 4] if "número 0 " in prompt else prompt

    _patch_llm(monkeypatch, _Backend(cut_the_first_piece))
    text = sentences(900)
    refined, _size = _refine(text)
    assert refined == text


def test_a_normal_cleanup_is_not_taken_for_a_cut():
    transcript = " ".join(f"palavra{i}" for i in range(200))
    cleaned = " ".join(f"palavra{i}" for i in range(180))
    assert not refinement.lost_most_of_transcript(transcript, cleaned)


def test_a_reply_with_a_quarter_of_the_words_is_a_cut():
    transcript = " ".join(f"palavra{i}" for i in range(200))
    cut = " ".join(f"palavra{i}" for i in range(50))
    assert refinement.lost_most_of_transcript(transcript, cut)


def test_short_dictation_is_not_judged_for_length():
    assert not refinement.lost_most_of_transcript("Não, espera, esquece isso, manda amanhã.", "Manda amanhã.")


def test_each_piece_is_told_only_its_own_placeholders(monkeypatch):
    backend = _Backend()
    _patch_llm(monkeypatch, backend)
    text = f"Começo [[S1]]. {sentences(900)} Fim [[S2]]."
    _refine(text, placeholders=["[[S1]]", "[[S2]]"])
    first, last = backend.calls[0]["system"], backend.calls[-1]["system"]
    assert "[[S1]]" in first
    assert "[[S2]]" not in first
    assert "[[S2]]" in last
    assert "[[S1]]" not in last


def test_portuguese_crutch_words_are_removed_but_the_verb_stays():
    prompt = refinement.build_refinement_prompt(refinement.RefinementFlags())
    assert '"tá" stays when it is the verb' in prompt
    assert '"tá?", "tá bom?", "né?"' in prompt
    assert 'such as "tá", "né"' not in prompt


def test_the_crutch_rule_needs_smart_cleanup():
    flags = refinement.RefinementFlags(smart_cleanup=False)
    assert "crutch words" not in refinement.build_refinement_prompt(flags)


def test_the_crutch_rule_does_not_say_where_the_message_ends():
    # "only when it closes the whole message" made qwen cut everything after a
    # mid-message "tá bom?".
    prompt = refinement.build_refinement_prompt(refinement.RefinementFlags())
    assert "closes the whole message" not in prompt
