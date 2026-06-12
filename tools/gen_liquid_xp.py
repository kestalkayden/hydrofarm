"""Generates Liquid XP fluid textures (still + flow) as animated 16x256 PNGs.

  flow texture (sides, vertical walls): scrolling band base + parallax highlight
  crests + 7 rising sparkle tracks. Used by LiquidTankRenderer for wall quads.

  still texture (top quad): subtle calm base + drifting froth clumps + 4 staggered
  sparkle-pop ring events. Non-directional motion appropriate for top-down view.

Both PNGs are 16 wide x 256 tall (16 stacked frames of 16x16). frametime=3 ticks
(~0.15 s) per frame; full cycle ~2.4 s. Re-run this script to regenerate after
tweaking constants below.
"""

import argparse
import math
import os
import random
from PIL import Image

# Shared palette — both textures must read as the same liquid.
# DARK/MID_GREEN drive the base body color of the liquid; LIGHT_GREEN, SPARKLE,
# and PEAK drive crests/froth/bubbles. Darkening only the base shifts contrast
# in favor of the bright accents without changing how the glow itself reads.
DARK_GREEN  = (50, 160, 70)
MID_GREEN   = (70, 180, 90)
LIGHT_GREEN = (120, 230, 140)
SPARKLE     = (180, 255, 200)
PEAK        = (220, 255, 230)

FRAMES = 16
SIZE = 16


def lerp(a, b, t):
    return tuple(int(a[i] * (1 - t) + b[i] * t) for i in range(3))


def clamp(v, lo, hi):
    return max(lo, min(hi, v))


def gen_flow(out_path: str, seed: int = 42):
    """Side-wall texture. Three animated layers: scrolling base bands, parallax
    highlight crests, rising sparkles. Motion direction = up (toward v0 in UV)."""
    rng = random.Random(seed)
    img = Image.new('RGBA', (SIZE, FRAMES * SIZE), (0, 0, 0, 0))
    px = img.load()

    # Base brightness pattern: sine wave over 16 rows so bands tile vertically
    # when the texture scrolls. Range tuned to keep contrast subtle (~30%).
    base_brightness = [
        0.5 + 0.35 * math.sin(2 * math.pi * y / 16) for y in range(SIZE)
    ]

    # Fixed per-pixel noise pattern (consistent across frames so the wall doesn't
    # look like TV static; the SCROLL is what conveys motion, not the noise).
    noise = [[rng.randint(-2, 2) for _ in range(SIZE)] for _ in range(SIZE)]

    # 7 rising sparkle tracks: (x, initial_y, size, brightness, speed_skip)
    # speed_skip=N means the sparkle advances every (N+1)th frame, giving slower
    # tracks for variety. Sparkle size 1 = single pixel; 2 = two stacked pixels.
    tracks = [
        (1,  0,  2, PEAK,    0),
        (3,  3,  1, SPARKLE, 0),
        (5,  7,  1, SPARKLE, 1),  # half-speed
        (8,  11, 2, PEAK,    0),
        (10, 5,  1, SPARKLE, 0),
        (12, 9,  1, SPARKLE, 1),  # half-speed
        (14, 13, 2, SPARKLE, 0),
    ]

    # Highlight crests: thin lighter bands scrolling 1 px / frame (faster than
    # the base which scrolls 1 px / 2 frames) — gives parallax depth.
    crest_rows_initial = [2, 11]

    for frame in range(FRAMES):
        # Both layers must move UP visually. In texture space (v0=top of wall),
        # "up" means lower row index over time — which for the base sample-shift
        # formula means ADDING the shift (output[y] = brightness[y+shift] makes
        # the peak migrate to lower y), but for the crest position-set formula
        # means SUBTRACTING the shift (cy = crest_y0 - shift moves the crest to
        # lower y directly). Mixing them was the bug.
        base_shift = frame // 2  # 1 px every 2 frames, sample-shift = +shift
        crest_shift = -frame     # 1 px every frame, position-set = -shift

        for y in range(SIZE):
            # Base layer: sample shifted brightness
            band_y = (y + base_shift) % SIZE
            t = base_brightness[band_y]
            color = lerp(DARK_GREEN, MID_GREEN, t)

            for x in range(SIZE):
                r = clamp(color[0] + noise[x][y], 0, 255)
                g = clamp(color[1] + noise[x][y], 0, 255)
                b = clamp(color[2] + noise[x][y], 0, 255)
                px[x, frame * SIZE + y] = (r, g, b, 255)

        # Crest highlights: paint thin lighter rows at shifted positions. Blend
        # is intentionally subtle — they exist to give parallax motion against
        # the slower base scroll, not to be a visible "line" feature.
        for crest_y0 in crest_rows_initial:
            cy = (crest_y0 + crest_shift) % SIZE
            for x in range(SIZE):
                base = px[x, frame * SIZE + cy]
                blended = lerp((base[0], base[1], base[2]), LIGHT_GREEN, 0.12)
                px[x, frame * SIZE + cy] = (blended[0], blended[1], blended[2], 255)

        # Sparkle tracks: bright pixel with a fainter trail one step behind.
        for (tx, ty0, size, sparkle_color, speed_skip) in tracks:
            # Track advances by 1 every (speed_skip+1) frames.
            advance = frame // (speed_skip + 1)
            # Rising = y decreases in texture space (where v0=top).
            cur_y = (ty0 - advance) % SIZE
            trail_y = (cur_y + 1) % SIZE  # trail sits BELOW the sparkle

            # Bright sparkle pixel(s)
            for dy in range(size):
                y_paint = (cur_y - dy) % SIZE  # if size=2, paint cur_y and cur_y-1
                px[tx, frame * SIZE + y_paint] = (*sparkle_color, 255)

            # Trail (one step below sparkle, halfway between base and sparkle)
            base = px[tx, frame * SIZE + trail_y]
            trail_color = lerp(
                (base[0], base[1], base[2]),
                sparkle_color,
                0.5,
            )
            px[tx, frame * SIZE + trail_y] = (trail_color[0], trail_color[1], trail_color[2], 255)

    img.save(out_path)
    print(f"wrote {out_path}")


def gen_still(out_path: str, seed: int = 24):
    """Top-quad texture. Calm base with drifting froth clumps + 4 staggered
    sparkle-pop ring events. No strong directional motion (top is non-axial)."""
    rng = random.Random(seed)
    img = Image.new('RGBA', (SIZE, FRAMES * SIZE), (0, 0, 0, 0))
    px = img.load()

    # Calm base: subtle per-pixel noise, fixed across frames so the surface
    # doesn't shimmer chaotically. Only the froth + sparkle-pops should move.
    noise = [[rng.randint(-3, 3) for _ in range(SIZE)] for _ in range(SIZE)]

    # Froth clumps: small clusters of LIGHT_GREEN pixels that drift slowly.
    # Each clump = (cx, cz, pattern) where pattern is a set of (dx, dz) offsets
    # describing the clump shape relative to its center. Drift = 1 px in a fixed
    # direction every 4 frames, wrapping at edges.
    froth_clumps = [
        (3,  4,  [(0, 0), (1, 0), (0, 1)],            (1,  0)),   # drift +x
        (10, 2,  [(0, 0), (-1, 0), (0, -1)],          (-1, 0)),   # drift -x
        (7,  9,  [(0, 0), (1, 0), (1, 1), (0, 1)],    (0,  1)),   # drift +z
        (13, 11, [(0, 0), (-1, 0), (0, -1)],          (0, -1)),   # drift -z
        (2,  13, [(0, 0), (1, 0), (0, 1)],            (1,  0)),
        (12, 6,  [(0, 0), (-1, 0), (0, 1)],           (0,  1)),
    ]

    # Sparkle pops: (start_frame, x, z). Each pop runs 5 frames.
    #   frame 0: bright center pixel only
    #   frame 1: center + 4 cardinal neighbors at sparkle color
    #   frame 2: center fades, neighbors brighten to peak (ring forming)
    #   frame 3: ring at peak, no center
    #   frame 4: ring fades, last gasp
    pops = [
        (0,  4,  4),
        (4,  10, 8),
        (8,  6,  12),
        (12, 13, 3),
    ]

    POP_LIFETIME = 5

    for frame in range(FRAMES):
        # Layer 1 — base color with fixed noise pattern
        for y in range(SIZE):
            for x in range(SIZE):
                base = MID_GREEN
                r = clamp(base[0] + noise[x][y], 0, 255)
                g = clamp(base[1] + noise[x][y], 0, 255)
                b = clamp(base[2] + noise[x][y], 0, 255)
                px[x, frame * SIZE + y] = (r, g, b, 255)

        # Layer 2 — drifting froth clumps
        drift_step = frame // 4
        for (cx, cz, pattern, (dx, dz)) in froth_clumps:
            ox = (cx + dx * drift_step) % SIZE
            oz = (cz + dz * drift_step) % SIZE
            for (px_off, pz_off) in pattern:
                fx = (ox + px_off) % SIZE
                fz = (oz + pz_off) % SIZE
                # Blend toward LIGHT_GREEN at 70%
                base = px[fx, frame * SIZE + fz]
                blended = lerp((base[0], base[1], base[2]), LIGHT_GREEN, 0.7)
                px[fx, frame * SIZE + fz] = (blended[0], blended[1], blended[2], 255)

        # Layer 3 — sparkle pop events
        for (start, sx, sz) in pops:
            age = (frame - start) % FRAMES
            if age >= POP_LIFETIME:
                continue
            # Draw based on age
            if age == 0:
                # Just the center, bright
                px[sx, frame * SIZE + sz] = (*PEAK, 255)
            elif age == 1:
                # Center + 4 cardinals at SPARKLE
                px[sx, frame * SIZE + sz] = (*PEAK, 255)
                for (dx, dz) in [(1, 0), (-1, 0), (0, 1), (0, -1)]:
                    nx, nz = (sx + dx) % SIZE, (sz + dz) % SIZE
                    px[nx, frame * SIZE + nz] = (*SPARKLE, 255)
            elif age == 2:
                # Center fades to base; cardinals peak
                for (dx, dz) in [(1, 0), (-1, 0), (0, 1), (0, -1)]:
                    nx, nz = (sx + dx) % SIZE, (sz + dz) % SIZE
                    px[nx, frame * SIZE + nz] = (*PEAK, 255)
            elif age == 3:
                # Ring at sparkle color (mid-fade)
                for (dx, dz) in [(1, 0), (-1, 0), (0, 1), (0, -1)]:
                    nx, nz = (sx + dx) % SIZE, (sz + dz) % SIZE
                    base = px[nx, frame * SIZE + nz]
                    blended = lerp((base[0], base[1], base[2]), SPARKLE, 0.6)
                    px[nx, frame * SIZE + nz] = (blended[0], blended[1], blended[2], 255)
            elif age == 4:
                # Ring barely visible, last gasp
                for (dx, dz) in [(1, 0), (-1, 0), (0, 1), (0, -1)]:
                    nx, nz = (sx + dx) % SIZE, (sz + dz) % SIZE
                    base = px[nx, frame * SIZE + nz]
                    blended = lerp((base[0], base[1], base[2]), LIGHT_GREEN, 0.5)
                    px[nx, frame * SIZE + nz] = (blended[0], blended[1], blended[2], 255)

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

    out_dir = os.path.abspath(args.out_dir)
    gen_flow(os.path.join(out_dir, 'liquid_xp_flow.png'))
    gen_still(os.path.join(out_dir, 'liquid_xp_still.png'))


if __name__ == '__main__':
    main()
