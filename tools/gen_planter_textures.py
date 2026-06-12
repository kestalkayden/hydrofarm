"""Generate two textures for the planter:
   - hydrofarm_planter.png       : a 3D-looking inventory icon (used by item model)
   - hydrofarm_planter_wall.png  : tileable wall pattern (used by the bed dome model)
   These were previously one texture, which forced the inventory icon to look like a
   wall slat. Splitting fixes both the icon AND keeps the in-bed visual unchanged."""

from PIL import Image
from pathlib import Path

OUT_DIR = Path(__file__).parent.parent / "shared-resources" / "assets" / "hydrofarm" / "textures" / "block"

TRANS = (0, 0, 0, 0)

# ---- Inventory icon: small pot with soil + sprout ----
def make_icon():
    RIM_DARK   = (140, 140, 145, 255)
    RIM_LIGHT  = (200, 200, 205, 255)
    WALL_LIGHT = (230, 230, 235, 255)
    WALL_MID   = (190, 190, 195, 255)
    WALL_DARK  = (150, 150, 155, 255)
    SOIL_TOP   = (90, 68, 50, 255)
    SOIL_DARK  = (62, 46, 32, 255)
    SPROUT_GREEN = (95, 160, 60, 255)
    SPROUT_DARK  = (55, 110, 35, 255)
    OUTLINE      = (40, 40, 45, 255)

    img = Image.new("RGBA", (16, 16), TRANS)
    px = img.load()

    # Top rim of pot
    for x in range(2, 14): px[x, 4] = OUTLINE
    for x in range(3, 13): px[x, 5] = RIM_LIGHT
    for x in range(3, 13): px[x, 6] = RIM_DARK

    # Soil visible in opening (3/4 view from above)
    for x in range(4, 12):
        px[x, 5] = SOIL_TOP
        px[x, 6] = SOIL_DARK
    px[3, 5] = OUTLINE; px[12, 5] = OUTLINE
    px[3, 6] = OUTLINE; px[12, 6] = OUTLINE

    # Side walls
    for y in range(7, 14):
        px[2, y] = OUTLINE
        px[13, y] = OUTLINE
        for x in range(3, 13):
            if x <= 5:   px[x, y] = WALL_LIGHT
            elif x <= 9: px[x, y] = WALL_MID
            else:        px[x, y] = WALL_DARK
    # Decorative seam
    for y in range(7, 14): px[8, y] = WALL_DARK

    # Base
    px[3, 14] = OUTLINE; px[12, 14] = OUTLINE
    for x in range(4, 12): px[x, 14] = WALL_DARK
    for x in range(4, 12): px[x, 15] = OUTLINE

    # Tiny sprout poking up
    px[7, 3] = SPROUT_DARK
    px[8, 3] = SPROUT_GREEN
    px[7, 4] = SPROUT_GREEN
    px[8, 4] = SPROUT_DARK

    return img

# ---- Wall texture: tileable vertical slats matching the previous look ----
def make_wall():
    WHITE      = (235, 235, 238, 255)
    LIGHT_GREY = (210, 210, 215, 255)
    SHADOW     = (165, 165, 170, 255)
    GROOVE     = (130, 130, 135, 255)

    img = Image.new("RGBA", (16, 16), WHITE)
    px = img.load()

    # Vertical slat grooves every 4 pixels
    for y in range(16):
        for x in (3, 7, 11):
            px[x, y] = GROOVE
        # Light highlight just to the right of each groove
        for x in (4, 8, 12):
            px[x, y] = LIGHT_GREY
        # Subtle shading on the right edge of each slat
        for x in (6, 10, 14):
            px[x, y] = SHADOW

    return img

OUT_DIR.mkdir(parents=True, exist_ok=True)

icon_path = OUT_DIR / "hydrofarm_planter.png"
make_icon().save(icon_path)
print(f"Wrote {icon_path}")

wall_path = OUT_DIR / "hydrofarm_planter_wall.png"
make_wall().save(wall_path)
print(f"Wrote {wall_path}")
