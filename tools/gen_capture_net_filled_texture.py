"""Generate animal_capture_net_filled.png — the empty 32x32 sprite with the
   net's white/gray pixels tinted a faint blue to signal a captured creature.

   We don't redraw the sprite; we read the existing animal_capture_net.png
   (the user-provided pixel art) and only shift its gray pixels toward blue.
   The brown handle and dark outline are left alone."""

from PIL import Image
from pathlib import Path

ROOT = Path(__file__).parent.parent
SRC  = ROOT / "shared-resources" / "assets" / "hydrofarm" / "textures" / "item" / "animal_capture_net.png"
DST  = ROOT / "shared-resources" / "assets" / "hydrofarm" / "textures" / "item" / "animal_capture_net_filled.png"

# Soft sky-blue target. Tint blend strength = how strongly the gray pixels
# move toward blue (0.0 = no change, 1.0 = replace entirely with blue).
TINT_COLOR = (140, 175, 220)
TINT_BLEND = 0.45

# A pixel is considered "gray" (net hoop/mesh) if its R, G, B are within this
# tolerance of each other. Higher = more pixels included.
GRAY_TOLERANCE = 12

# Skip very dark grays — those are the outline, which should stay dark/neutral.
MIN_BRIGHTNESS = 80

img = Image.open(SRC).convert("RGBA")
px = img.load()
w, h = img.size

for y in range(h):
    for x in range(w):
        r, g, b, a = px[x, y]
        if a == 0:
            continue
        if max(r, g, b) - min(r, g, b) > GRAY_TOLERANCE:
            continue  # Colored pixel (brown handle) — leave alone
        if max(r, g, b) < MIN_BRIGHTNESS:
            continue  # Dark outline — leave alone
        # Blend the gray pixel toward the tint color
        r2 = int(r * (1 - TINT_BLEND) + TINT_COLOR[0] * TINT_BLEND)
        g2 = int(g * (1 - TINT_BLEND) + TINT_COLOR[1] * TINT_BLEND)
        b2 = int(b * (1 - TINT_BLEND) + TINT_COLOR[2] * TINT_BLEND)
        px[x, y] = (r2, g2, b2, a)

DST.parent.mkdir(parents=True, exist_ok=True)
img.save(DST)
print(f"Wrote {DST}")
