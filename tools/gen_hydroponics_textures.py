"""Generates the three hydroponics textures: bed pad, planter net-pot walls, and
clay-pebble soil. Re-run after tweaking constants below. Drops PNGs directly
into shared-resources/assets/hydrofarm/textures/block/."""

import argparse
import os
import random
from PIL import Image


def clamp_color(c, noise=0):
    return tuple(max(0, min(255, x + noise)) for x in c[:3]) + ((c[3] if len(c) > 3 else 255),)


# ---- Bed pad: cool gray with corner bolts + central water tint + subtle panels ----
def gen_bed(out_path):
    rng = random.Random(7)
    base  = (172, 182, 197, 255)
    water = (148, 168, 190, 255)
    bolt  = (82, 92, 112, 255)
    panel = (162, 172, 187, 255)  # slightly darker than base — subtle horizontal banding

    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    px = img.load()

    for y in range(16):
        for x in range(16):
            # Pick base color before noise
            corner = ((x < 2 and y < 2) or (x > 13 and y < 2)
                      or (x < 2 and y > 13) or (x > 13 and y > 13))
            if corner:
                c = bolt
            elif 5 <= x <= 10 and 5 <= y <= 10:
                c = water
            elif y % 4 == 3:
                c = panel
            else:
                c = base
            noise = rng.randint(-3, 3)
            px[x, y] = clamp_color(c, noise)

    img.save(out_path)
    print(f"wrote {out_path}")


# ---- Planter walls: cool-white with vertical net-pot slats ----
def gen_planter(out_path):
    rng = random.Random(13)
    base = (228, 232, 240, 255)
    slat = (160, 170, 190, 255)  # darker vertical lines
    rim  = (205, 213, 226, 255)  # subtle top/bottom rim
    slats_x = {2, 6, 10, 14}

    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    px = img.load()

    for y in range(16):
        for x in range(16):
            if y < 2 or y >= 14:
                c = rim
            elif x in slats_x:
                c = slat
            else:
                c = base
            noise = rng.randint(-3, 3)
            px[x, y] = clamp_color(c, noise)

    img.save(out_path)
    print(f"wrote {out_path}")


# ---- Clay pebbles soil: dark wet substrate with scattered orange-brown pebbles ----
def gen_pebbles(out_path):
    rng = random.Random(99)
    bg = (48, 33, 22, 255)        # dark wet substrate
    pebble_palette = [
        (140, 80, 40, 255),
        (180, 110, 65, 255),
        (210, 145, 95, 255),
        (165, 92, 50, 255),
    ]
    highlight = (235, 180, 130, 255)  # wet sheen on each pebble's top-left

    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    px = img.load()

    # Wet substrate background
    for y in range(16):
        for x in range(16):
            n = rng.randint(-5, 5)
            px[x, y] = clamp_color(bg, n)

    # Scatter ~14 small pebbles, no clustering
    pebble_positions = []
    target = 14
    attempts = 80
    while attempts > 0 and len(pebble_positions) < target:
        attempts -= 1
        cx = rng.randint(0, 14)
        cy = rng.randint(0, 14)
        too_close = any(abs(cx - x) <= 1 and abs(cy - y) <= 1 for (x, y) in pebble_positions)
        if too_close:
            continue
        pebble_positions.append((cx, cy))

    # Render each pebble as a 2x2 with a single highlight pixel at top-left
    for (cx, cy) in pebble_positions:
        shade = rng.choice(pebble_palette)
        for dx, dy in [(0, 0), (1, 0), (0, 1), (1, 1)]:
            nx, ny = cx + dx, cy + dy
            if 0 <= nx < 16 and 0 <= ny < 16:
                n = rng.randint(-5, 5)
                px[nx, ny] = clamp_color(shade, n)
        if 0 <= cx < 16 and 0 <= cy < 16:
            px[cx, cy] = highlight

    img.save(out_path)
    print(f"wrote {out_path}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        '--out-dir',
        default=os.path.join(
            os.path.dirname(__file__), '..', 'shared-resources', 'assets',
            'hydrofarm', 'textures', 'block',
        ),
    )
    args = parser.parse_args()

    out = os.path.abspath(args.out_dir)
    gen_bed(os.path.join(out, 'hydroponics_bed.png'))
    gen_planter(os.path.join(out, 'hydroponics_planter.png'))
    gen_pebbles(os.path.join(out, 'hydroponics_pebbles.png'))


if __name__ == '__main__':
    main()
