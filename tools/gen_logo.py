#!/usr/bin/env python3
"""
Generate the NEROLAND CORE mod logo (square).

Core is the foundation library every Neroland mod builds on, so the scene is a
faceted central "core" prism — its facets coloured by Core's four backbone
materials (Nero Alloy teal, Starsteel steel-blue, Void Crystal purple, Plasma Glass
cyan) — with ecosystem nodes orbiting and connected back to it (every mod depends on
Core), over a Neroland-palette starfield. Renders supersampled then downsamples.

Outputs:
  art/logo/nerolandcore_logo.png       (1024x1024 master)
  art/logo/nerolandcore_logo_400.png   (CurseForge/Modrinth-ready)
  common/src/main/resources/nerolandcore_logo.png  (256x256 in-game mods-list icon)
"""
import math
import os
import random
import argparse
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "art/logo")
ICON = os.path.join(ROOT, "common/src/main/resources")
os.makedirs(OUT, exist_ok=True)
os.makedirs(ICON, exist_ok=True)

FINAL = 1024
SS = 2
R = FINAL * SS
rng = random.Random(7)

# Neroland Core material palette
NERO_ALLOY = (38, 166, 154)    # teal (industrial)
STARSTEEL  = (140, 178, 208)   # steel-blue (space-era)
VOID_CRYS  = (158, 92, 206)    # purple (alien)
PLASMA     = (96, 212, 232)    # cyan (plasma glass)
ACCENT     = (120, 235, 220)
BRIGHT     = (224, 252, 250)


def _font(size):
    for path in (
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
    ):
        if os.path.exists(path):
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


# ---------- background ----------
def background():
    top = np.array([6, 12, 18], float)
    bot = np.array([12, 20, 32], float)
    yy = np.linspace(0, 1, R)[:, None, None]
    img = top[None, None, :] * (1 - yy) + bot[None, None, :] * yy
    img = np.repeat(img, R, axis=1)

    Y, X = np.mgrid[0:R, 0:R].astype(float)

    def glow(cx, cy, rad, color, strength):
        d = np.sqrt((X - cx) ** 2 + (Y - cy) ** 2)
        f = np.clip(1 - d / rad, 0, 1) ** 2 * strength
        for c in range(3):
            img[:, :, c] += color[c] * f

    glow(R * 0.30, R * 0.32, R * 0.55, (20, 90, 90), 0.45)   # teal nebula
    glow(R * 0.74, R * 0.70, R * 0.52, (70, 30, 120), 0.42)  # purple nebula
    glow(R * 0.5, R * 0.5, R * 0.42, (20, 50, 70), 0.30)

    d = np.sqrt((X - R / 2) ** 2 + (Y - R / 2) ** 2) / (R * 0.72)
    vig = np.clip(1 - (d ** 2) * 0.85, 0.25, 1)
    img *= vig[:, :, None]
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGB").convert("RGBA")


def add_stars(base):
    layer = Image.new("RGBA", (R, R), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    for _ in range(480):
        x, y = rng.randint(0, R), rng.randint(0, R)
        s = rng.choice([1, 1, 1, 2, 2, 3]) * SS
        b = rng.randint(120, 255)
        tint = rng.choice([(b, b, b), (b, 255, 255), (180, 255, 240), (200, 200, 255)])
        d.ellipse([x, y, x + s, y + s], fill=tint + (rng.randint(120, 255),))
    base.alpha_composite(layer.filter(ImageFilter.GaussianBlur(2 * SS)))
    base.alpha_composite(layer)
    return base


def soft_glow(draw_fn, blur):
    layer = Image.new("RGBA", (R, R), (0, 0, 0, 0))
    draw_fn(ImageDraw.Draw(layer))
    return layer.filter(ImageFilter.GaussianBlur(blur))


# ---------- ecosystem nodes orbiting the core ----------
def nodes(base, cx, cy, ring):
    layer = Image.new("RGBA", (R, R), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    count = 9
    for i in range(count):
        a = math.radians(i * (360 / count) - 90)
        nx = cx + math.cos(a) * ring
        ny = cy + math.sin(a) * ring
        # connection line back to the core (every mod depends on Core)
        d.line([cx, cy, nx, ny], fill=(120, 200, 200, 90), width=max(1, SS))
        nr = rng.randint(7, 12) * SS
        col = rng.choice([NERO_ALLOY, STARSTEEL, VOID_CRYS, PLASMA])
        d.ellipse([nx - nr, ny - nr, nx + nr, ny + nr], fill=col + (235,))
        d.ellipse([nx - nr // 2, ny - nr // 2, nx, ny], fill=(255, 255, 255, 180))
    base.alpha_composite(layer.filter(ImageFilter.GaussianBlur(4 * SS)))
    base.alpha_composite(layer)
    return base


# ---------- central faceted core prism ----------
def core_prism(base, cx, cy, rad):
    # outer aura
    base.alpha_composite(soft_glow(
        lambda dr: dr.ellipse([cx - rad * 1.5, cy - rad * 1.5, cx + rad * 1.5, cy + rad * 1.5],
                              fill=(40, 180, 180, 150)), 30 * SS))
    base.alpha_composite(soft_glow(
        lambda dr: dr.ellipse([cx - rad * 1.1, cy - rad * 1.1, cx + rad * 1.1, cy + rad * 1.1],
                              fill=(120, 90, 200, 120)), 16 * SS))

    # hexagon vertices (pointy-top)
    hexpts = []
    for i in range(6):
        a = math.radians(60 * i - 90)
        hexpts.append((cx + math.cos(a) * rad, cy + math.sin(a) * rad))

    layer = Image.new("RGBA", (R, R), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    # six facets from center to each edge, coloured by the material families
    facet_cols = [NERO_ALLOY, STARSTEEL, VOID_CRYS, PLASMA, NERO_ALLOY, VOID_CRYS]
    for i in range(6):
        p1 = hexpts[i]
        p2 = hexpts[(i + 1) % 6]
        shade = 0.55 + 0.45 * (i / 5.0)
        col = tuple(int(c * shade) for c in facet_cols[i])
        d.polygon([(cx, cy), p1, p2], fill=col + (255,))
    # bright inner core
    ir = rad * 0.34
    d.ellipse([cx - ir, cy - ir, cx + ir, cy + ir], fill=BRIGHT + (255,))
    d.ellipse([cx - ir * 0.5, cy - ir * 0.5, cx + ir * 0.5, cy + ir * 0.5], fill=(255, 255, 255, 255))
    # facet edges + outline
    for i in range(6):
        d.line([hexpts[i], hexpts[(i + 1) % 6]], fill=(210, 255, 250, 230), width=max(1, SS * 2))
        d.line([(cx, cy), hexpts[i]], fill=(200, 245, 240, 150), width=max(1, SS))
    base.alpha_composite(layer)

    # specular sparkle top
    sx, sy = cx - rad * 0.18, cy - rad * 0.5
    base.alpha_composite(soft_glow(
        lambda dr: dr.ellipse([sx - 9 * SS, sy - 9 * SS, sx + 9 * SS, sy + 9 * SS],
                              fill=(255, 255, 255, 255)), 5 * SS))
    dd = ImageDraw.Draw(base)
    L = 20 * SS
    dd.line([sx - L, sy, sx + L, sy], fill=(255, 255, 255, 230), width=SS * 2)
    dd.line([sx, sy - L, sx, sy + L], fill=(255, 255, 255, 230), width=SS * 2)
    return base


# ---------- wordmark ----------
def wordmark(base):
    big = _font(int(R * 0.130))
    small = _font(int(R * 0.060))
    tagf = _font(int(R * 0.028))

    def centered(text, font, y, fill, glow=None):
        w = ImageDraw.Draw(base).textlength(text, font=font)
        x = (R - w) / 2
        if glow:
            gl = Image.new("RGBA", (R, R), (0, 0, 0, 0))
            ImageDraw.Draw(gl).text((x, y), text, font=font, fill=glow)
            base.alpha_composite(gl.filter(ImageFilter.GaussianBlur(9 * SS)))
            base.alpha_composite(gl.filter(ImageFilter.GaussianBlur(3 * SS)))
        out = Image.new("RGBA", (R, R), (0, 0, 0, 0))
        ImageDraw.Draw(out).text((x, y), text, font=font, fill=(8, 16, 20, 255))
        base.alpha_composite(out.filter(ImageFilter.MaxFilter(2 * SS + 1)))
        ImageDraw.Draw(base).text((x, y), text, font=font, fill=fill)

    centered("NEROLAND", big, int(R * 0.70), (236, 252, 250, 255), glow=(40, 200, 190, 255))
    centered("CORE", small, int(R * 0.845), (150, 235, 225, 255), glow=(60, 180, 200, 220))

    tag = "M O D   E C O S Y S T E M   F O U N D A T I O N"
    tw = ImageDraw.Draw(base).textlength(tag, font=tagf)
    ImageDraw.Draw(base).text(((R - tw) / 2, int(R * 0.915)), tag, font=tagf, fill=(150, 200, 205, 255))
    return base


def generate_gif(source=None, output=None):
    source = source or os.path.join(OUT, "nerolandcore_logo.png")
    output = output or os.path.join(OUT, "nerolandcore_logo.gif")
    with Image.open(source) as image:
        logo = image.convert("RGB")
    logo.thumbnail((400, 400), Image.Resampling.LANCZOS)

    width, height = logo.size
    y, x = np.mgrid[0:height, 0:width]
    distance = ((x / width - 0.5) / 0.24) ** 2 + ((y / height - 0.36) / 0.24) ** 2
    glow = np.exp(-distance * 2)[:, :, None]
    pixels = np.asarray(logo, dtype=np.float32)
    frames = []
    count = 16
    for index in range(count):
        phase = 2 * math.pi * index / count
        intensity = (1 - math.cos(phase)) / 2
        sweep = np.exp(-((x / width - 0.5 - 0.27 * math.sin(phase)) / 0.045) ** 2)
        light = glow * (intensity * 0.09 + sweep[:, :, None] * 0.12)
        frame = np.clip(pixels + light * np.array(PLASMA), 0, 255)
        image = Image.fromarray(frame.astype(np.uint8), "RGB").convert("RGBA")
        sparks = Image.new("RGBA", image.size, (0, 0, 0, 0))
        draw = ImageDraw.Draw(sparks)
        for offset, color in ((0, PLASMA), (math.pi, VOID_CRYS)):
            for trail in range(8, -1, -1):
                angle = phase + offset - trail * 0.035
                sx = width * (0.5 + 0.323 * math.cos(angle))
                sy = height * (0.36 + 0.323 * math.sin(angle))
                radius = max(1, width * 0.0035 * (1 - trail / 12))
                alpha = int(210 * (1 - trail / 9) ** 2)
                draw.ellipse((sx - radius, sy - radius, sx + radius, sy + radius), fill=color + (alpha,))
        image.alpha_composite(sparks.filter(ImageFilter.GaussianBlur(max(1, width * 0.006))))
        image.alpha_composite(sparks)
        frames.append(image.convert("RGB"))
    frames[0].save(output, save_all=True, append_images=frames[1:], duration=180, loop=0, optimize=True)
    return output


def main():
    img = background()
    img = add_stars(img)
    cx, cy, rad = int(R * 0.5), int(R * 0.36), int(R * 0.17)
    img = nodes(img, cx, cy, int(rad * 1.9))
    img = core_prism(img, cx, cy, rad)
    img = wordmark(img)

    final = img.convert("RGB").resize((FINAL, FINAL), Image.LANCZOS)
    p1 = os.path.join(OUT, "nerolandcore_logo.png")
    p2 = os.path.join(OUT, "nerolandcore_logo_400.png")
    p3 = os.path.join(ICON, "nerolandcore_logo.png")
    final.save(p1)
    final.resize((400, 400), Image.LANCZOS).save(p2)
    final.resize((256, 256), Image.LANCZOS).save(p3)
    for p in (p1, p2, p3):
        print("wrote", os.path.relpath(p, ROOT))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Generate the Neroland Core logo.")
    parser.add_argument("--gif", action="store_true", help="animate the existing master PNG")
    args = parser.parse_args()
    if args.gif:
        print("wrote", os.path.relpath(generate_gif(), ROOT))
    else:
        main()
