"""
Text shortcuts (snippets) — a spoken trigger phrase that becomes a saved
text: "meu endereço" → the full postal address, "assinatura" → a
multi-line signature.

- A dictation that is only the trigger (filler words and punctuation
  around it allowed) becomes the saved text verbatim and is not refined
  (:func:`match_whole_snippet`).
- A trigger inside a longer dictation is expanded in place
  (:func:`expand_snippets`). With refinement on, each trigger is first
  swapped for a placeholder token that the LLM is told to keep
  (:func:`protect_snippets`) and the token becomes the saved text after
  refinement (:func:`restore_snippets`): the model never sees the saved
  text, and can't respell the trigger so it no longer matches ("meu
  email" → "meu e-mail"). With refinement off the raw transcript is
  expanded.

Matching ignores case, accents and the punctuation between words, so
"Meu endereço", "meu endereco" and "meu, endereço" all match the trigger
"meu endereço". Pure (no I/O, no heavy imports) like ``vocabulary.py``.
"""

import re
import unicodedata
from typing import Any, Iterable, Optional

# A shortcut list is a handful of addresses, signatures and links; the caps
# keep the settings row small. 4000 characters fits a long signature block.
MAX_SNIPPETS = 100
MAX_TRIGGER_CHARS = 100
MAX_SNIPPET_TEXT_CHARS = 4000

# ``llm_model`` label of a capture whose delivered text came from a shortcut
# rather than from the LLM, so the UI can say so.
SNIPPET_LABEL = "snippet"

# Stands for a trigger while the LLM refines the text around it: brackets
# and a short id, which models copy unchanged and nobody dictates.
PLACEHOLDER_FORMAT = "[[S{}]]"
_PLACEHOLDER_RE = re.compile(r"\[\[S\d+\]\]")

# Words allowed around a trigger when the whole dictation is the shortcut
# ("é... meu endereço", "um, signature"). Compared after folding, so "então"
# is "entao" and "é" is "e".
_EDGE_FILLERS = frozenset(
    {
        # English
        "um", "umm", "uh", "uhm", "er", "erm", "ah", "oh", "hm", "hmm", "so", "ok", "okay",
        # Portuguese
        "e", "eh", "ahn", "hum", "humm", "tipo", "entao", "bom", "ne", "ai",
    }
)

_WORD_RE = re.compile(r"\w+")


def _fold(text: str) -> str:
    """Case- and accent-insensitive form: NFKD, combining marks dropped, casefolded."""
    decomposed = unicodedata.normalize("NFKD", text)
    return "".join(ch for ch in decomposed if not unicodedata.combining(ch)).casefold()


def _fold_with_index(text: str) -> tuple[str, list[int]]:
    """Fold ``text`` one character at a time, keeping where each folded char came from.

    Folding can change the length ("ß" → "ss", "é" → "e" + a dropped mark),
    so a match in the folded text is mapped back to the original through
    the returned index list.
    """
    folded: list[str] = []
    origin: list[int] = []
    for i, ch in enumerate(text):
        piece = _fold(ch)
        folded.append(piece)
        origin.extend([i] * len(piece))
    return "".join(folded), origin


def _trigger_words(trigger: str) -> list[str]:
    return _WORD_RE.findall(_fold(trigger))


def _clean_text(value: Any) -> str:
    """Expansion text: line endings normalised to ``\\n``, outer whitespace trimmed."""
    if not isinstance(value, str):
        return ""
    return value.replace("\r\n", "\n").replace("\r", "\n").strip()


def _clean_snippets(snippets: Optional[Iterable[Any]]) -> list[dict[str, str]]:
    """Trimmed shortcuts with a usable trigger and a text; a later trigger wins."""
    by_key: dict[str, dict[str, str]] = {}
    for raw in snippets or []:
        if isinstance(raw, dict):
            trigger, text = raw.get("trigger"), raw.get("text")
        else:
            trigger, text = getattr(raw, "trigger", None), getattr(raw, "text", None)
        trigger = " ".join(trigger.split()) if isinstance(trigger, str) else ""
        text = _clean_text(text)
        key = " ".join(_trigger_words(trigger))
        if not key or not text:
            continue
        # Pop first so the later shortcut also takes the later position.
        by_key.pop(key, None)
        by_key[key] = {"trigger": trigger, "text": text}
    return list(by_key.values())


def normalize_snippets(snippets: Optional[Iterable[Any]]) -> list[dict[str, str]]:
    """Clean a list of ``{"trigger": ..., "text": ...}`` shortcuts.

    The trigger is trimmed with inner whitespace collapsed; the text keeps
    its line breaks (as ``\\n``) and loses only outer whitespace. A shortcut
    with an empty side, or a trigger with no letter or digit, is dropped.
    Two shortcuts whose triggers fold to the same words ("Meu endereço",
    "meu endereco") keep the later one.

    Raises:
        ValueError: A trigger is longer than ``MAX_TRIGGER_CHARS``, a text is
            longer than ``MAX_SNIPPET_TEXT_CHARS``, or there are more than
            ``MAX_SNIPPETS`` shortcuts.
    """
    out = _clean_snippets(snippets)
    for snippet in out:
        if len(snippet["trigger"]) > MAX_TRIGGER_CHARS:
            raise ValueError(
                f"Shortcut trigger is longer than {MAX_TRIGGER_CHARS} characters: "
                f"{snippet['trigger'][:40]}…"
            )
        if len(snippet["text"]) > MAX_SNIPPET_TEXT_CHARS:
            raise ValueError(
                f"Shortcut text for '{snippet['trigger'][:40]}' is longer than "
                f"{MAX_SNIPPET_TEXT_CHARS} characters"
            )
    if len(out) > MAX_SNIPPETS:
        raise ValueError(f"There are {len(out)} shortcuts; the limit is {MAX_SNIPPETS}")
    return out


def match_whole_snippet(transcript: str, snippets: Optional[Iterable[Any]]) -> Optional[str]:
    """The shortcut text when ``transcript`` is just a trigger, else ``None``.

    Punctuation is ignored everywhere and filler words ("um", "é", "tipo")
    are allowed before and after the trigger. When two triggers fit, the
    longer one wins.
    """
    if not transcript or not transcript.strip():
        return None
    words = _WORD_RE.findall(_fold(transcript))
    if not words:
        return None
    best: Optional[tuple[int, str]] = None
    for snippet in _clean_snippets(snippets):
        trigger = _trigger_words(snippet["trigger"])
        n = len(trigger)
        for start in range(len(words) - n + 1):
            if words[start : start + n] != trigger:
                continue
            around = words[:start] + words[start + n :]
            if all(word in _EDGE_FILLERS for word in around):
                if best is None or n > best[0]:
                    best = (n, snippet["text"])
                break
    return best[1] if best else None


def expand_snippets(text: str, snippets: Optional[Iterable[Any]]) -> str:
    """Replace every trigger inside ``text`` with its shortcut text, in one pass.

    Triggers match as whole words, ignoring case and accents, with any
    run of spaces or punctuation between their words. Longer triggers win
    over shorter ones inside them, and an inserted text is never searched
    again.
    """
    return _replace_triggers(text, snippets, lambda expansion: expansion)


def protect_snippets(text: str, snippets: Optional[Iterable[Any]]) -> tuple[str, dict[str, str]]:
    """Replace every trigger inside ``text`` with a placeholder token.

    Matching is the same as :func:`expand_snippets`. Each occurrence gets
    its own token (``[[S1]]``, ``[[S2]]``…). Returns the text and a map
    from token to shortcut text, for :func:`restore_snippets`; the map is
    empty when ``text`` has no trigger.
    """
    tokens: dict[str, str] = {}

    def to_token(expansion: str) -> str:
        token = PLACEHOLDER_FORMAT.format(len(tokens) + 1)
        tokens[token] = expansion
        return token

    return _replace_triggers(text, snippets, to_token), tokens


def restore_snippets(text: str, tokens: dict[str, str]) -> Optional[str]:
    """Put each shortcut text back in place of its token.

    Returns ``None`` when a token is missing, appears more than once, or an
    unknown one appears — the text can't be trusted to hold each shortcut
    exactly where the speaker said it.
    """
    found = _PLACEHOLDER_RE.findall(text)
    if sorted(found) != sorted(tokens):
        return None
    return _PLACEHOLDER_RE.sub(lambda m: tokens[m.group(0)], text)


def _replace_triggers(text: str, snippets: Optional[Iterable[Any]], replace) -> str:
    """Replace every trigger inside ``text`` with ``replace(shortcut text)``."""
    if not text:
        return text
    cleaned = _clean_snippets(snippets)
    if not cleaned:
        return text
    cleaned.sort(key=lambda s: len(_trigger_words(s["trigger"])), reverse=True)
    pattern = re.compile(
        "|".join(
            rf"(?P<s{i}>(?<!\w){r'\W+'.join(re.escape(w) for w in _trigger_words(s['trigger']))}(?!\w))"
            for i, s in enumerate(cleaned)
        )
    )
    folded, origin = _fold_with_index(text)
    parts: list[str] = []
    last = 0
    for match in pattern.finditer(folded):
        start = origin[match.start()]
        end = origin[match.end() - 1] + 1
        if start < last:
            continue
        parts.append(text[last:start])
        parts.append(replace(cleaned[int(match.lastgroup[1:])]["text"]))
        last = end
    if not parts:
        return text
    parts.append(text[last:])
    return "".join(parts)
