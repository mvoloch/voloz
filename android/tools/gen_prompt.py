"""Port the desktop refinement prompt text to Kotlin verbatim, and write a
test fixture produced by the real Python build_refinement_prompt.

Usage: python3 -I gen_prompt.py <refinement.py> <kotlin_out> <fixture_dir>
"""
import ast
import json
import sys
from pathlib import Path

src_path, kt_out, fixture_dir = sys.argv[1], Path(sys.argv[2]), Path(sys.argv[3])
src = Path(src_path).read_text()
tree = ast.parse(src)

WANTED = ["_BASE_INSTRUCTIONS", "_SMART_CLEANUP", "_SPELLING", "_SELF_CORRECTION",
          "_PRESERVE_TECHNICAL", "_VOCABULARY", "_KEEP_LANGUAGE_INSTRUCTION",
          "REFINEMENT_EXAMPLES", "_REPETITION_RUN_THRESHOLD", "_MAX_REPETITION_UNIT_CHARS"]
FUNCS = ["build_refinement_prompt", "_token_key", "collapse_repetitive_artifacts",
         "_collapse_word_runs", "_collapse_character_runs"]
ns = {}
keep = []
for node in tree.body:
    if isinstance(node, (ast.Assign, ast.AnnAssign)):
        target = node.targets[0] if isinstance(node, ast.Assign) else node.target
        if isinstance(target, ast.Name) and target.id in WANTED:
            keep.append(node)
    elif isinstance(node, ast.FunctionDef) and node.name in FUNCS:
        keep.append(node)
    elif isinstance(node, ast.ClassDef) and node.name == "RefinementFlags":
        keep.append(node)
mod = ast.Module(body=keep, type_ignores=[])
prelude = ("import re\nfrom dataclasses import dataclass\nfrom typing import Iterable, Optional\n"
           "def style_instruction(_s):\n    return None\n")
exec(prelude, ns)
exec(compile(mod, src_path, "exec"), ns)
missing = [w for w in WANTED if w not in ns]
assert not missing, missing


def kt(s: str) -> str:
    out = []
    for ch in s:
        if ch == "\\":
            out.append("\\\\")
        elif ch == '"':
            out.append('\\"')
        elif ch == "$":
            out.append("\\$")
        elif ch == "\n":
            out.append("\\n")
        elif ch == "\t":
            out.append("\\t")
        else:
            out.append(ch)
    return '"' + "".join(out) + '"'


def kt_multiline(s: str) -> str:
    # One literal per source line, joined with a trailing + (Kotlin ends the
    # expression at a newline that precedes the operator).
    lines = s.split("\n")
    parts = [kt(l + ("\n" if i < len(lines) - 1 else "")) for i, l in enumerate(lines)]
    return " +\n        ".join(parts)


names = {
    "_BASE_INSTRUCTIONS": "BASE_INSTRUCTIONS",
    "_SMART_CLEANUP": "SMART_CLEANUP",
    "_SPELLING": "SPELLING",
    "_SELF_CORRECTION": "SELF_CORRECTION",
    "_PRESERVE_TECHNICAL": "PRESERVE_TECHNICAL",
    "_VOCABULARY": "VOCABULARY_TEMPLATE",
    "_KEEP_LANGUAGE_INSTRUCTION": "KEEP_LANGUAGE",
}
body = []
for py, k in names.items():
    body.append(f"    const val {k}: String =\n        {kt_multiline(ns[py])}\n")
ex = ",\n".join(f"        {kt(u)} to\n            {kt(a)}" for u, a in ns["REFINEMENT_EXAMPLES"])
body.append(f"    /** Few-shot pairs sent as real chat turns (user -> assistant), in order. */\n"
            f"    val EXAMPLES: List<Pair<String, String>> = listOf(\n{ex},\n    )\n")

kt_out.parent.mkdir(parents=True, exist_ok=True)
kt_out.write_text(
    "// GENERATED from backend/services/refinement.py (voloz-088) by gen_prompt.py.\n"
    "// Verbatim port of the desktop refinement prompt text; do not hand-edit.\n"
    "// The assembly logic lives in RefinementPrompt.kt.\n"
    "package io.github.mvoloch.voloz.android.refine\n\n"
    "object RefinementPromptText {\n" + "\n".join(body) + "}\n"
)

fixture_dir.mkdir(parents=True, exist_ok=True)
flags = ns["RefinementFlags"]()
keep_lang = ns["_KEEP_LANGUAGE_INSTRUCTION"]
no_vocab = ns["build_refinement_prompt"](flags) + "\n\n" + keep_lang
with_vocab = ns["build_refinement_prompt"](flags, ["Voloz", "Pascoal", "MSC", "Log-In"]) + "\n\n" + keep_lang
(fixture_dir / "prompt_no_vocab.txt").write_text(no_vocab)
(fixture_dir / "prompt_with_vocab.txt").write_text(with_vocab)
(fixture_dir / "examples.json").write_text(json.dumps(ns["REFINEMENT_EXAMPLES"], ensure_ascii=False))
cases = [
    "URL URL URL URL URL URL",
    "open the URL URL URL URL URL URL, URL. now",
    "no, no, no, no, no",
    "yeah yeah yeah",
    "hello thanks for watching thanks for watching thanks for watching thanks for watching thanks for watching thanks for watching",
    "谢谢观看谢谢观看谢谢观看谢谢观看谢谢观看谢谢观看",
    "wooooooow that is great",
    "então tipo eu acho que a gente pode marcar a reunião pra terça né",
    "a b a b a b a b a b a b a b end",
    "   ",
    "",
    "Subtitles by the Amara.org community Subtitles by the Amara.org community Subtitles by the Amara.org community Subtitles by the Amara.org community Subtitles by the Amara.org community Subtitles by the Amara.org community",
]
(fixture_dir / "collapse_cases.json").write_text(json.dumps(
    [[c, ns["collapse_repetitive_artifacts"](c)] for c in cases], ensure_ascii=False))
# Every on/off combination of the refinement toggles, with and without vocabulary,
# from the desktop's own build_refinement_prompt (keep-language added on the Kotlin side).
combos = []
for c in (True, False):
    for sc in (True, False):
        for t in (True, False):
            f = ns["RefinementFlags"](smart_cleanup=c, self_correction=sc, preserve_technical=t)
            combos.append({
                "flags": [c, sc, t],
                "plain": ns["build_refinement_prompt"](f),
                "vocab": ns["build_refinement_prompt"](f, ["Voloz", "Groq"]),
            })
(fixture_dir / "prompt_flags.json").write_text(json.dumps(combos, ensure_ascii=False))
print("ok", len(no_vocab), len(with_vocab), len(combos))
