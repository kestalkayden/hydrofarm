"""Generate liquid_pipe.png by recoloring the shared pipe base pattern (tools/pipe_base.png, the
   original copper liquid-pipe art) — preserves the exact pattern (UV layout, shading, bevels) but
   maps it to a deep slate-blue palette, so liquid pipes read as the "blue" member of the colour-coded
   pipe family (item = charcoal-blue, energy = dark red, liquid = blue). Same luma+offset method as
   gen_item_pipe_texture.py, so the three pipes share one flat tinted style at different hues."""

from PIL import Image
from pathlib import Path
import sys

ROOT = Path(__file__).parent.parent
SRC = Path(__file__).parent / "pipe_base.png"
OUT = ROOT / "shared-resources" / "assets" / "hydrofarm" / "textures" / "block" / "liquid_pipe.png"

# Tone presets — pass one as argv[1] (default: deep_blue). Offsets are added to the darkened luma;
# the dominant pattern pixel (copper luma ~148) lands on the listed hex.
TONES = {
    "deep_blue":   {"darken": 0.40, "red_off": -21, "green_off": 0,  "blue_off": +23},  # ~#263B52
    "slate_blue":  {"darken": 0.46, "red_off": -20, "green_off": -2, "blue_off": +22},  # brighter ~#2E4A66
    # Brighter than deep_blue + a small green boost / lower red -> a subtle cyan/teal lean.
    "bright_cyan": {"darken": 0.46, "red_off": -23, "green_off": +4, "blue_off": +22},  # ~#2D485A
}
tone_name = sys.argv[1] if len(sys.argv) > 1 else "bright_cyan"
tone = TONES.get(tone_name)
if tone is None:
    print(f"Unknown tone '{tone_name}'. Options: {list(TONES.keys())}")
    sys.exit(1)

img = Image.open(SRC).convert("RGBA")
out = Image.new("RGBA", img.size)
w, h = img.size
for y in range(h):
    for x in range(w):
        r, g, b, a = img.getpixel((x, y))
        if a == 0:
            out.putpixel((x, y), (0, 0, 0, 0))
            continue
        # Perceptual luma so highlights/shadows scale naturally — pure mean would flatten them.
        lum = int(0.299 * r + 0.587 * g + 0.114 * b)
        new_lum = int(lum * tone["darken"])
        nr = max(0, min(255, new_lum + tone["red_off"]))
        ng = max(0, min(255, new_lum + tone["green_off"]))
        nb = max(0, min(255, new_lum + tone["blue_off"]))
        out.putpixel((x, y), (nr, ng, nb, a))

OUT.parent.mkdir(parents=True, exist_ok=True)
out.save(OUT)
print(f"Wrote {OUT} (tone: {tone_name})")
