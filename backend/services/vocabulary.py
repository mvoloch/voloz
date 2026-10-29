"""
Custom vocabulary — names and jargon the speech-to-text model keeps
mishearing ("Voloz" heard as "Valois").

Three mechanisms, applied in order on every capture:

1. The vocabulary is sent to a remote Whisper endpoint as its ``prompt``,
   which biases decoding toward those spellings (:func:`build_stt_prompt`).
2. Replacement rules rewrite the raw transcript right after STT, so a
   mishearing that survives the prompt is still fixed, with or without
   refinement (:func:`apply_replacements`).
3. The refinement prompt tells the LLM to spell the vocabulary exactly
   (``refinement.build_refinement_prompt``).

:func:`suggest_replacements` closes the loop: when the user corrects a
transcript by hand, it proposes the short substitutions as new rules.

Everything here is pure (no I/O, no heavy imports) so the lite edition
and the tests can use it directly.
"""

import difflib
import re
from typing import Any, Iterable, Optional

# Caps keep the settings row, the Whisper prompt and the refinement prompt
# small. A dictation vocabulary is a few dozen names; 200 is generous.
MAX_VOCABULARY_TERMS = 200
MAX_REPLACEMENT_RULES = 200
MAX_TERM_CHARS = 100

# Whisper reads at most 224 prompt tokens and keeps the last ones when the
# prompt is longer. Names and Portuguese words tokenise at roughly 2.5-4
# characters per token, so 400 characters stays under the limit with room
# to spare.
STT_PROMPT_MAX_CHARS = 400

# suggest_replacements: an edit that changed more than half the words is a
# rewrite, not a correction of misheard words.
_MIN_EDIT_SIMILARITY = 0.5
_MAX_SUGGESTION_WORDS = 3
_MAX_SUGGESTIONS = 5

_EDGE_PUNCTUATION_RE = re.compile(r"^[^\w]+|[^\w]+$")


def _clean_term(value: Any) -> str:
    """Trim and collapse inner whitespace; non-strings become ``""``."""
    if not isinstance(value, str):
        return ""
    return " ".join(value.split())


def _key(term: str) -> str:
    return term.casefold()


def normalize_vocabulary(terms: Optional[Iterable[Any]]) -> list[str]:
    """Trim, drop empties and dedupe case-insensitively (first spelling wins).

    Raises:
        ValueError: A term is longer than ``MAX_TERM_CHARS`` or there are
            more than ``MAX_VOCABULARY_TERMS`` distinct terms.
    """
    out: list[str] = []
    seen: set[str] = set()
    for raw in terms or []:
        term = _clean_term(raw)
        if not term:
            continue
        if len(term) > MAX_TERM_CHARS:
            raise ValueError(f"Vocabulary term is longer than {MAX_TERM_CHARS} characters: {term[:40]}…")
        if _key(term) in seen:
            continue
        seen.add(_key(term))
        out.append(term)
    if len(out) > MAX_VOCABULARY_TERMS:
        raise ValueError(f"Vocabulary has {len(out)} terms; the limit is {MAX_VOCABULARY_TERMS}")
    return out


def _clean_rules(rules: Optional[Iterable[Any]]) -> list[dict[str, str]]:
    """Trimmed, deduplicated rules with both sides non-empty and different."""
    by_key: dict[str, dict[str, str]] = {}
    for raw in rules or []:
        if isinstance(raw, dict):
            src, dst = raw.get("from"), raw.get("to")
        else:
            # Pydantic rule objects (``ReplacementRule``) expose ``from_``.
            src, dst = getattr(raw, "from_", None), getattr(raw, "to", None)
        src, dst = _clean_term(src), _clean_term(dst)
        if not src or not dst or src == dst:
            continue
        # Pop first so the later rule also takes the later position.
        by_key.pop(_key(src), None)
        by_key[_key(src)] = {"from": src, "to": dst}
    return list(by_key.values())


def normalize_replacements(rules: Optional[Iterable[Any]]) -> list[dict[str, str]]:
    """Clean a list of ``{"from": ..., "to": ...}`` rules.

    Both sides are trimmed; a rule with an empty side, or whose sides are
    identical, is dropped. Two rules for the same ``from`` (compared
    case-insensitively) keep the later one, so re-learning a correction
    replaces the old target.

    Raises:
        ValueError: A side is longer than ``MAX_TERM_CHARS`` or there are
            more than ``MAX_REPLACEMENT_RULES`` rules.
    """
    out = _clean_rules(rules)
    for rule in out:
        if len(rule["from"]) > MAX_TERM_CHARS or len(rule["to"]) > MAX_TERM_CHARS:
            raise ValueError(f"Replacement rule side is longer than {MAX_TERM_CHARS} characters")
    if len(out) > MAX_REPLACEMENT_RULES:
        raise ValueError(f"There are {len(out)} replacement rules; the limit is {MAX_REPLACEMENT_RULES}")
    return out


def build_stt_prompt(vocabulary: Optional[Iterable[str]]) -> Optional[str]:
    """Whisper ``prompt`` for a vocabulary: the terms, comma-separated.

    Whole terms are kept in list order until ``STT_PROMPT_MAX_CHARS``; the
    rest are left out rather than cut mid-word. Returns ``None`` when there
    is nothing to send.
    """
    parts: list[str] = []
    length = 0
    for term in vocabulary or []:
        term = _clean_term(term)
        if not term:
            continue
        added = len(term) + (2 if parts else 0)
        if length + added > STT_PROMPT_MAX_CHARS:
            break
        parts.append(term)
        length += added
    return ", ".join(parts) if parts else None


def _phrase_pattern(phrase: str) -> str:
    """Regex for ``phrase`` as a whole word or phrase, any inner whitespace.

    ``\\w`` is Unicode-aware, so "Valois" does not match inside "Valoisa"
    or "Valoisé". The boundary is only asserted on sides that end in a
    word character, so a rule for "C++" still matches.
    """
    body = r"\s+".join(re.escape(word) for word in phrase.split())
    prefix = r"(?<!\w)" if re.match(r"\w", phrase) else ""
    suffix = r"(?!\w)" if re.search(r"\w$", phrase) else ""
    return f"{prefix}{body}{suffix}"


def apply_replacements(text: str, rules: Optional[Iterable[Any]]) -> str:
    """Apply replacement rules to a transcript in a single pass.

    Matching is case-insensitive and on whole words or phrases; the ``to``
    side is written exactly as given. All rules run at once, so one rule's
    output is never rewritten by another, and longer phrases win over the
    words inside them.
    """
    if not text:
        return text
    # Stored rules were validated on write; the caps are not re-checked so a
    # dictation never fails here.
    cleaned = _clean_rules(rules)
    if not cleaned:
        return text
    cleaned.sort(key=lambda r: len(r["from"]), reverse=True)
    pattern = re.compile(
        "|".join(f"(?P<r{i}>{_phrase_pattern(r['from'])})" for i, r in enumerate(cleaned)),
        flags=re.IGNORECASE,
    )
    return pattern.sub(lambda m: cleaned[int(m.lastgroup[1:])]["to"], text)


def _strip_edges(word: str) -> str:
    return _EDGE_PUNCTUATION_RE.sub("", word)


def suggest_replacements(before: str, after: str) -> list[dict[str, str]]:
    """Short word substitutions that turn ``before`` into ``after``.

    Compares the two texts word by word (case and surrounding punctuation
    ignored) and returns each place where 1-3 words were replaced by 1-3
    other words, as ``{"from": ..., "to": ...}`` in the spelling of each
    text. Insertions, deletions, case-only and punctuation-only changes are
    not suggested, and nothing is when the edit rewrote most of the text.
    """
    before_words = [w for w in (_strip_edges(w) for w in (before or "").split()) if w]
    after_words = [w for w in (_strip_edges(w) for w in (after or "").split()) if w]
    if not before_words or not after_words:
        return []

    matcher = difflib.SequenceMatcher(
        a=[_key(w) for w in before_words],
        b=[_key(w) for w in after_words],
        autojunk=False,
    )
    if matcher.ratio() < _MIN_EDIT_SIMILARITY:
        return []

    suggestions: list[dict[str, str]] = []
    seen: set[tuple[str, str]] = set()
    for tag, i1, i2, j1, j2 in matcher.get_opcodes():
        if tag != "replace":
            continue
        if not (1 <= i2 - i1 <= _MAX_SUGGESTION_WORDS and 1 <= j2 - j1 <= _MAX_SUGGESTION_WORDS):
            continue
        src = " ".join(before_words[i1:i2])
        dst = " ".join(after_words[j1:j2])
        pair = (_key(src), dst)
        if _key(src) == _key(dst) or pair in seen:
            continue
        seen.add(pair)
        suggestions.append({"from": src, "to": dst})
        if len(suggestions) >= _MAX_SUGGESTIONS:
            break
    return suggestions
