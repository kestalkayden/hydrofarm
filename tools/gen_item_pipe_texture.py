"""Generate item_pipe.png by recoloring the shared pipe base pattern (tools/pipe_base.png, the
   original copper liquid-pipe art) — preserves the exact pattern (UV layout, shading direction,
   bevel positions) but maps the copper/orange palette to a charcoal-with-faint-blue palette so
   item pipes are visually distinct without redrawing. Liquid pipes (blue) share the same base via
   gen_liquid_pipe_texture.py; the base lives in tools/ so the live liquid_pipe.png can be recoloured."""

from PIL import Image
from pathlib import Path
import sys

ROOT = Path(__file__).parent.parent
SRC = Path(__file__).parent / "pipe_base.png"
OUT = ROOT / "shared-resources" / "assets" / "hydrofarm" / "textures" / "block" / "item_pipe.png"

# Tone presets — pass one as argv[1] (default: charcoal_blue).
TONES = {
    "charcoal":      {"darken": 0.45, "red_off": 0,  "green_off": 0,  "blue_off": 0},
    "charcoal_blue": {"darken": 0.45, "red_off": -8, "green_off": -3, "blue_off": +10},
    "faint_blue":    {"darken": 0.65, "red_off": -12,"green_off": -4, "blue_off": +20},
}
tone_name = sys.argv[1] if len(sys.argv) > 1 else "charcoal_blue"
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
