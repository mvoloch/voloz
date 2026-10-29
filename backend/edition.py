"""Which edition this server runs as.

``studio`` is the full Voicebox server (voice generation, cloning, stories,
effects, local models). ``lite`` is dictation only: captures, settings and
cloud transcription/refinement, built without PyTorch. The lite build simply
leaves torch out, so the default follows from whether torch is installed;
``VOICEBOX_EDITION=lite|studio`` overrides that (tests, dev servers).
"""

import importlib.util
import os
from functools import lru_cache


@lru_cache(maxsize=1)
def get_edition() -> str:
    """Return ``"lite"`` or ``"studio"``."""
    forced = os.environ.get("VOICEBOX_EDITION", "").strip().lower()
    if forced in ("lite", "studio"):
        return forced
    return "studio" if importlib.util.find_spec("torch") is not None else "lite"


def is_lite() -> bool:
    return get_edition() == "lite"
