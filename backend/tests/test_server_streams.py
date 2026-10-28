"""
The server's stdout/stderr wrapper keeps writing after a character the
pipe's codepage can't encode.

``server.py`` replaces sys.stdout and imports the whole backend when
imported, so the class is loaded from its source on its own.
"""

import ast
import io
import os
from pathlib import Path


def _pipe_safe_stream_class():
    src = (Path(__file__).parents[1] / "server.py").read_text(encoding="utf-8")
    node = next(
        n
        for n in ast.parse(src).body
        if isinstance(n, ast.ClassDef) and n.name == "_PipeSafeStream"
    )
    namespace = {"os": os}
    exec(ast.get_source_segment(src, node), namespace)
    return namespace["_PipeSafeStream"]


def test_unencodable_character_does_not_silence_the_stream():
    raw = io.BytesIO()
    pipe = io.TextIOWrapper(raw, encoding="cp1252", write_through=True)
    stream = _pipe_safe_stream_class()(pipe)

    stream.write("model → loaded\n")
    stream.write("next line\n")

    assert raw.getvalue() == b"model ? loaded\nnext line\n"
