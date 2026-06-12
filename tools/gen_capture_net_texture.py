"""Generate animal_capture_net.png — a 16x16 inventory icon for the capture net.
   Visualizes a diamond net pattern attached to a wooden handle, viewed at a slight angle."""

from PIL import Image
from pathlib import Path

OUT = Path(__file__).parent.parent / "shared-resources" / "assets" / "hydrofarm" / "textures" / "item" / "animal_capture_net.png"

TRANS = (0, 0, 0, 0)
HANDLE_BROWN = (115, 78, 50, 255)
HANDLE_DARK  = (80, 55, 35, 255)
HANDLE_LIGHT = (155, 110, 75, 255)
RIM_GREY     = (170, 170, 175, 255)
RIM_DARK     = (110, 110, 115, 255)
NET_LIGHT    = (220, 220, 225, 255)
NET_DARK     = (140, 140, 145, 255)
OUTLINE      = (40, 40, 45, 255)

img = Image.new("RGBA", (16, 16), TRANS)
px = img.load()

# Net hoop (oval/circle at top-right) — drawn as a rough rounded shape
# Center the hoop around (10, 5), radius ~4
hoop_cells = [
    (8, 2), (9, 2), (10, 2), (11, 2),
    (7, 3), (12, 3),
    (6, 4), (13, 4),
    (6, 5), (13, 5),
    (6, 6), (13, 6),
    (7, 7), (12, 7),
    (8, 8), (9, 8), (10, 8), (11, 8),
]
for (x, y) in hoop_cells:
    px[x, y] = OUTLINE

# Net mesh interior — alternating light/dark to suggest weave
interior = [
    (8, 3), (10, 3),
    (7, 4), (9, 4), (11, 4), (12, 4),
    (8, 5), (10, 5), (12, 5),
    (7, 5), (9, 5), (11, 5),
    (8, 6), (10, 6), (12, 6),
    (7, 6), (9, 6), (11, 6),
    (9, 7), (10, 7), (11, 7),
]
for i, (x, y) in enumerate(interior):
    px[x, y] = NET_LIGHT if (x + y) % 2 == 0 else NET_DARK

# Hoop rim highlight (top-left bright)
px[7, 3] = RIM_GREY
px[8, 2] = RIM_GREY
px[6, 4] = RIM_GREY
px[6, 5] = RIM_DARK
px[13, 4] = RIM_DARK
px[13, 5] = RIM_DARK
px[13, 6] = RIM_DARK
px[12, 7] = RIM_DARK

# Handle — diagonal from bottom-left up to the hoop attach point
handle_cells = [
    (1, 14), (2, 14),       # grip bottom
    (2, 13), (3, 13),
    (3, 12), (4, 12),
    (4, 11), (5, 11),
    (5, 10), (6, 10),
    (6, 9),  (7, 9),
    (7, 8),
]
for (x, y) in handle_cells:
    px[x, y] = OUTLINE

# Handle fill — wood texture
fill = [
    (2, 13), (3, 13),
    (3, 12), (4, 12),
    (4, 11), (5, 11),
    (5, 10), (6, 10),
    (6, 9),  (7, 9),
]
for (x, y) in fill:
    px[x, y] = HANDLE_BROWN

# Handle highlight (upper-left edge)
px[2, 13] = HANDLE_LIGHT
px[3, 12] = HANDLE_LIGHT
px[4, 11] = HANDLE_LIGHT
px[5, 10] = HANDLE_LIGHT
px[6, 9]  = HANDLE_LIGHT

# Handle shadow (lower-right edge)
px[3, 13] = HANDLE_DARK
px[4, 12] = HANDLE_DARK
px[5, 11] = HANDLE_DARK
px[6, 10] = HANDLE_DARK
px[7, 9]  = HANDLE_DARK

# Grip cap at bottom-left
px[1, 14] = HANDLE_DARK
px[2, 14] = HANDLE_DARK
px[1, 13] = OUTLINE

OUT.parent.mkdir(parents=True, exist_ok=True)
img.save(OUT)
print(f"Wrote {OUT}")
