"""Generate energy_cell_face.png — the Energy Cell's SEAMLESS base face: muted dark-red grain (fine
   per-pixel noise, low contrast, NO bright reds). Pure high-frequency noise has no border and no
   large-scale feature, so it reads as a continuous rough surface across cells in ANY connected shape
   (it repeats per block like vanilla stone/dirt — normal and unobtrusive at this contrast).

   This is only the BASE. The energy "glow" is added separately later via the mod's emission code
   (an emissive layer that lights up / scales with charge and reads correctly under shaders), rather
   than baking bright colour into this tiling texture. Frame is shared with the Liquid Tank. 16x16.

   RNG is seeded so the texture is reproducible across runs."""

import random
from PIL import Image
from pathlib import Path

OUT = (Path(__file__).parent.parent / "shared-resources" / "assets" / "hydrofarm"
       / "textures" / "block" / "energy_cell_face.png")

W = H = 16
R0, G0, B0 = 64, 26, 20   # muted dark-red base


def clamp(v):
    return max(0, min(255, v))


random.seed(20240607)
img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
px = img.load()
for y in range(H):
    for x in range(W):
        n = random.randint(-8, 6)                       # tighter spread -> flatter, subtler grain
        s = random.random()
        fleck = -5 if s < 0.10 else (3 if s > 0.93 else 0)    # gentle, sparse grain flecks
        d = n + fleck
        px[x, y] = (clamp(R0 + d), clamp(G0 + d * 3 // 5), clamp(B0 + d // 2), 255)

OUT.parent.mkdir(parents=True, exist_ok=True)
img.save(OUT)
print(f"Wrote {OUT}")
