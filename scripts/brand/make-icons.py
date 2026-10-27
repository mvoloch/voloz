"""Regenerate the Tauri app icons from the Voloz brand assets.

Run from the repository root with a Python that has Pillow (the backend venv
does):

    backend/venv/bin/python scripts/brand/make-icons.py

1. ``tauri icon`` builds every platform format from ``branding/voloz-icon-1024.png``
   (tile + symbol + the "voloz" wordmark).
2. Sizes where the wordmark can't be read — 64 px and below, and the small
   Windows tiles — are replaced by the symbol alone (``voloz-mark-*``), which is
   drawn on a 16-unit grid and stays sharp at 16/32/48/64 px.
3. ``icon.ico`` is rebuilt so its 16–64 px layers come from the symbol and its
   128/256 px layers from the full icon (Windows picks the layer by size: the
   taskbar and tray use the small ones).
"""

import subprocess
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
BRANDING = ROOT / "branding"
ICONS = ROOT / "tauri" / "src-tauri" / "icons"

ICON_1024 = BRANDING / "voloz-icon-1024.png"
MARK_1024 = BRANDING / "voloz-mark-1024.png"

# Windows tile PNGs that Tauri ships; below this size the wordmark is unreadable.
WORDMARK_MIN_PX = 128


def grid_resize(src: Image.Image, size: int) -> Image.Image:
    """Downscale the 1024 px symbol; BOX on exact divisors keeps edges on the grid."""
    method = Image.Resampling.BOX if 1024 % size == 0 else Image.Resampling.LANCZOS
    return src.resize((size, size), method)


def mark_at(mark: Image.Image, size: int) -> Image.Image:
    """Prefer the pixel-exact renders the brand kit ships for 16/32/48 px."""
    exact = BRANDING / f"voloz-mark-{size}.png"
    if exact.exists():
        return Image.open(exact).convert("RGBA")
    return grid_resize(mark, size)


def main() -> int:
    subprocess.run(
        ["bun", "run", "tauri", "icon", str(ICON_1024), "-o", str(ICONS)],
        cwd=ROOT / "tauri",
        check=True,
    )

    mark = Image.open(MARK_1024).convert("RGBA")
    icon = Image.open(ICON_1024).convert("RGBA")

    # Plain PNG sizes Tauri uses on Windows/Linux.
    for name, size in (("32x32.png", 32), ("64x64.png", 64)):
        mark_at(mark, size).save(ICONS / name)

    # Windows Store / tile logos: symbol below the wordmark threshold.
    for path in ICONS.glob("Square*Logo.png"):
        size = Image.open(path).size[0]
        if size < WORDMARK_MIN_PX:
            mark_at(mark, size).save(path)
    store = ICONS / "StoreLogo.png"
    if store.exists():
        mark_at(mark, Image.open(store).size[0]).save(store)

    # Multi-resolution ICO: symbol for 16–64 px, full icon for 128/256 px.
    layers = {s: mark_at(mark, s) for s in (16, 24, 32, 48, 64)}
    layers.update({s: grid_resize(icon, s) for s in (128, 256)})
    sizes = sorted(layers)
    largest = layers[sizes[-1]]
    largest.save(
        ICONS / "icon.ico",
        format="ICO",
        sizes=[(s, s) for s in sizes],
        append_images=[layers[s] for s in sizes[:-1]],
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
