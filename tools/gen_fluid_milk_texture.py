"""Generate fluid_milk_still.png and fluid_milk_flow.png for the milk fluid.

   Creates 16x16 textures (no animation for v0.6.2 — single frame keeps the look stable
   inside a tank without needing an .mcmeta animation strip). Warm-cream base with a
   subtle dot pattern so the surface reads as 'liquid' rather than flat-painted white."""

from PIL import Image
from pathlib import Path
import random

OUT_DIR = Path(__file__).parent.parent / "shared-resources" / "assets" / "hydrofarm" / "textures" / "block"

# Warm cream base — slightly off-white so it's distinguishable from snow/wool textures.
BASE       = (245, 244, 235, 255)
HIGHLIGHT  = (255, 254, 246, 255)
SHADOW     = (228, 225, 213, 255)

random.seed(42)  # Deterministic — regenerating gives the same texture.

def make(size=16):
    img = Image.new("RGBA", (size, size), BASE)
    px = img.load()
    # Sparse highlight + shadow speckles to suggest liquid surface variance.
    for _ in range(12):
        x, y = random.randint(0, size - 1), random.randint(0, size - 1)
        px[x, y] = HIGHLIGHT
    for _ in range(8):
        x, y = random.randint(0, size - 1), random.randint(0, size - 1)
        px[x, y] = SHADOW
    return img

OUT_DIR.mkdir(parents=True, exist_ok=True)
still_path = OUT_DIR / "fluid_milk_still.png"
flow_path  = OUT_DIR / "fluid_milk_flow.png"

# Same texture for still + flow; we don't render flowing milk in the world anyway.
make().save(still_path)
make().save(flow_path)
print(f"Wrote {still_path}")
print(f"Wrote {flow_path}")
