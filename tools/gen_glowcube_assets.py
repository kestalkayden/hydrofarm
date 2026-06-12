"""Generate the Glowcube assets: textures + all repetitive blockstate/model/loot/recipe/tag JSON.

Glowcubes are full-block, always-on light-15 sources in the 16 vanilla dye colours plus an undyed
honey cream. The surface is the vanilla honey block's soft amber swirl used purely as a BRIGHTNESS
field (HONEY_LUMA below — luma only, no Mojang pixels shipped), recoloured per dye.

They render SEMI-TRANSLUCENT via a honey-style double cube: an outer SHELL (alpha 160 ~63%) around an
inset solid CORE (alpha 200 ~78%, so the centre reads ~91%). Glassy edges, colour-rich core — the
honey "framed depth" look. The two layers use two separate textures per colour: <id>.png (shell) and
<id>_core.png (core). render_type translucent in the model drives blending on both loaders (same
mechanism as the Liquid Tank glass — no client code). ALL colours share one brightness field so a
mixed-colour wall stays coherent.

This emits the near-identical JSON too (one source-of-truth generator beats hand-copies with typos).
The en_us.json lang entries and the assets/hydrofarm/items/<id>.json item-model defs ARE generated
here; lang stays hand-edited.  Run:  python tools/gen_glowcube_assets.py
"""

import colorsys
import json
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).parent.parent
ASSETS = ROOT / "shared-resources" / "assets" / "hydrofarm"
DATA = ROOT / "shared-resources" / "data" / "hydrofarm"

W = H = 16

# DyeColor.values() order — keep identical to the Java registration loop, the lang file, and the tab.
DYES = ["white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
        "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"]

# True vanilla dye colours (accurate hue + full saturation). Undyed = warm honey cream. Brightness
# comes from the honey field via HSV *value* in shade() — we deliberately DON'T pre-lighten the bases.
# Pre-lightening (the old picks) raised green/blue, which desaturated and drifted hues:
# purple->magenta, magenta->pink, red->orange.
TINT = {
    "glowcube":   (235, 206, 150),
    "white":      (249, 255, 254),
    "orange":     (249, 128, 29),
    "magenta":    (199, 78, 189),
    "light_blue": (58, 179, 218),
    "yellow":     (254, 216, 61),
    "lime":       (128, 199, 31),
    "pink":       (243, 139, 170),
    "gray":       (71, 79, 82),
    "light_gray": (157, 157, 151),
    "cyan":       (22, 156, 156),
    "purple":     (125, 48, 190),    # nudged cooler than vanilla (137,50,184) to widen the blue gap
    "blue":       (38, 92, 205),     # nudged bluer than vanilla (60,68,170) — read as blue, not violet
    "brown":      (131, 84, 50),
    "green":      (94, 124, 22),
    "red":        (176, 46, 38),
    "black":      (29, 29, 33),
}

# Brightness field (0-255) = the luma of vanilla honey_block_top. Only the grey-scale shape is used;
# every glowcube recolours it, so no Mojang colour data ships. See module docstring.
HONEY_LUMA = [
    [165, 165, 195, 195, 195, 208, 195, 195, 208, 208, 195, 195, 195, 215, 208, 178],
    [165, 195, 195, 208, 208, 208, 195, 195, 178, 178, 195, 208, 223, 208, 195, 178],
    [165, 195, 208, 208, 208, 195, 178, 178, 195, 208, 223, 223, 208, 195, 178, 165],
    [178, 195, 195, 208, 195, 178, 178, 178, 178, 195, 195, 195, 178, 178, 178, 165],
    [208, 195, 195, 195, 195, 208, 208, 195, 178, 178, 178, 178, 178, 165, 165, 178],
    [208, 195, 208, 208, 208, 195, 178, 165, 165, 165, 165, 165, 165, 165, 178, 208],
    [195, 208, 208, 178, 165, 165, 165, 165, 165, 165, 165, 178, 178, 195, 208, 208],
    [195, 208, 178, 178, 178, 165, 165, 165, 178, 178, 178, 178, 195, 195, 208, 195],
    [208, 208, 178, 178, 165, 165, 165, 178, 178, 178, 178, 195, 195, 208, 195, 195],
    [208, 178, 178, 165, 165, 178, 178, 195, 195, 195, 195, 195, 208, 195, 195, 208],
    [208, 178, 165, 165, 165, 195, 195, 195, 195, 208, 208, 208, 208, 195, 178, 195],
    [178, 178, 165, 165, 195, 195, 195, 208, 215, 215, 215, 208, 208, 195, 165, 178],
    [178, 165, 165, 178, 195, 208, 215, 223, 223, 215, 208, 195, 195, 178, 165, 178],
    [178, 165, 178, 178, 208, 215, 223, 223, 208, 208, 215, 195, 178, 178, 165, 195],
    [165, 165, 178, 178, 208, 215, 215, 208, 208, 215, 208, 195, 178, 178, 178, 195],
    [165, 165, 178, 195, 215, 215, 208, 208, 208, 215, 195, 178, 178, 165, 195, 178],
]

VALUE_FLOOR = 0.83      # honey swirl modulates HSV value over [0.83 .. 1.10] x the dye's own value,
VALUE_RANGE = 0.27      # keeping hue + saturation intact (gentle swirl, no washing toward white)
SHELL_ALPHA = 160       # outer shell ~63% — glassy edges
CORE_ALPHA = 200        # inset core ~78% (centre reads ~91% through both layers) — solid colour

ALL = ["glowcube"] + [f"{c}_glowcube" for c in DYES]


def tint_key(name):
    return "glowcube" if name == "glowcube" else name[:-len("_glowcube")]


def shade(base, b):
    """Render the honey swirl as HSV *value* variation, keeping the dye's true hue and saturation —
    just brighten/darken. This is what fixes the pastel/hue-drift: mixing toward white (the old way)
    removed saturation, so purple read as magenta and red as orange."""
    b = max(0.0, min(1.0, b))
    h, s, v = colorsys.rgb_to_hsv(*(c / 255.0 for c in base))
    v = min(1.0, v * (VALUE_FLOOR + VALUE_RANGE * b))
    r, g, bl = colorsys.hsv_to_rgb(h, s, v)
    return (int(r * 255), int(g * 255), int(bl * 255))


def brightness_field():
    """Honey luma stretched to full [0,1] contrast so the swirl reads clearly; shade() then applies
    it gently as an HSV value swing (VALUE_FLOOR..VALUE_FLOOR+VALUE_RANGE)."""
    flat = [v for row in HONEY_LUMA for v in row]
    lo, hi = min(flat), max(flat)
    span = (hi - lo) or 1
    return [[(HONEY_LUMA[y][x] - lo) / span for x in range(W)] for y in range(H)]


def gen_textures():
    field = brightness_field()
    out_dir = ASSETS / "textures" / "block"
    out_dir.mkdir(parents=True, exist_ok=True)
    for name in ALL:
        base = TINT[tint_key(name)]
        shell = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        core = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        sp, cp = shell.load(), core.load()
        for y in range(H):
            for x in range(W):
                r, g, b = shade(base, field[y][x])
                sp[x, y] = (r, g, b, SHELL_ALPHA)
                cp[x, y] = (r, g, b, CORE_ALPHA)
        shell.save(out_dir / f"{name}.png")
        core.save(out_dir / f"{name}_core.png")
    print(f"Wrote {2 * len(ALL)} textures (shell + core) to {out_dir}")


def write_json(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, indent=2) + "\n", encoding="utf-8")


def glowcube_model(name):
    """Honey-style double cube: a translucent outer SHELL (culls against opaque neighbours) around an
    inset translucent CORE. Two textures => independent shell/core opacity; the overlap is the depth."""
    sides = ("down", "up", "north", "south", "west", "east")
    outer = {d: {"texture": "#shell", "cullface": d} for d in sides}
    inner = {d: {"uv": [1, 1, 15, 15], "texture": "#core"} for d in sides}
    return {
        "parent": "minecraft:block/block",
        "render_type": "minecraft:translucent",
        "textures": {
            "particle": f"hydrofarm:block/{name}",
            "shell": f"hydrofarm:block/{name}",
            "core": f"hydrofarm:block/{name}_core",
        },
        "elements": [
            {"from": [0, 0, 0], "to": [16, 16, 16], "faces": outer},
            {"from": [1, 1, 1], "to": [15, 15, 15], "faces": inner},
        ],
    }


def gen_json():
    for name in ALL:
        write_json(ASSETS / "blockstates" / f"{name}.json",
                   {"variants": {"": {"model": f"hydrofarm:block/{name}"}}})
        write_json(ASSETS / "models" / "block" / f"{name}.json", glowcube_model(name))
        write_json(ASSETS / "models" / "item" / f"{name}.json",
                   {"parent": f"hydrofarm:block/{name}"})
        # 1.21.4+ item-model definition — without this the in-hand item is the missing-model magenta.
        write_json(ASSETS / "items" / f"{name}.json",
                   {"model": {"type": "minecraft:model", "model": f"hydrofarm:item/{name}"}})
        write_json(DATA / "loot_table" / "blocks" / f"{name}.json",
                   {"type": "minecraft:block",
                    "pools": [{"rolls": 1,
                               "entries": [{"type": "minecraft:item", "name": f"hydrofarm:{name}"}],
                               "conditions": [{"condition": "minecraft:survives_explosion"}]}]})

    # Base shaped recipe: 8 honeycomb ring + glowstone dust -> 1 undyed glowcube.
    # NOTE: honeycomb is VANILLA (minecraft:honeycomb) — the very item the bee tenant drops.
    write_json(DATA / "recipe" / "glowcube.json",
               {"type": "minecraft:crafting_shaped",
                "category": "misc",
                "key": {"H": "minecraft:honeycomb", "D": "minecraft:glowstone_dust"},
                "pattern": ["HHH", "HDH", "HHH"],
                "result": {"id": "hydrofarm:glowcube"}})

    # 16 shapeless dye recipes, tag-based so ANY glowcube + a dye re-colours in place.
    for c in DYES:
        write_json(DATA / "recipe" / f"{c}_glowcube.json",
                   {"type": "minecraft:crafting_shapeless",
                    "category": "misc",
                    "ingredients": [{"tag": "hydrofarm:glowcubes"}, {"item": f"minecraft:{c}_dye"}],
                    "result": {"id": f"hydrofarm:{c}_glowcube"}})

    # Item tag with all 17 (singular 'item' dir — matches this repo's tags/entity_type convention).
    write_json(DATA / "tags" / "item" / "glowcubes.json",
               {"replace": False, "values": [f"hydrofarm:{n}" for n in ALL]})

    print("Wrote 17 blockstates + 17 block models + 17 item models + 17 item defs + 17 loot tables "
          "+ 1 shaped + 16 shapeless recipes + 1 item tag")


if __name__ == "__main__":
    gen_textures()
    gen_json()
