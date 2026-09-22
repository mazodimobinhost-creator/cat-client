#!/usr/bin/env python3
"""Cat Client branding assets (purple / black / white).

Draws the launcher icon, adaptive-icon foreground, notification icon and the
Android TV banner with Pillow — no external services, reproducible output.

Usage: python3 scripts/branding/generate-icons.py
"""
import pathlib
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

RES = pathlib.Path(__file__).resolve().parents[2] / 'app' / 'src' / 'main' / 'res'
FONT_BOLD = RES / 'font' / 'vazirmatn_bold.ttf'

VIOLET = (139, 92, 246)
VIOLET_DEEP = (109, 40, 217)
FUCHSIA = (217, 70, 239)
WHITE = (247, 245, 255)
INK = (10, 5, 16)
DARK_TOP = (48, 14, 82)
DARK_BOTTOM = (6, 3, 12)


def gradient_background(size: int, top=DARK_TOP, bottom=DARK_BOTTOM) -> Image.Image:
    """Vertical purple-black gradient with a soft violet glow behind the cat."""
    base = np.zeros((size, size, 3), dtype=np.float32)
    for y in range(size):
        t = y / max(size - 1, 1)
        base[y, :, :] = np.array(top) * (1 - t) + np.array(bottom) * t

    yy, xx = np.mgrid[0:size, 0:size].astype(np.float32)
    cx, cy = size * 0.5, size * 0.62
    dist = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2) / (size * 0.62)
    glow = np.clip(1.0 - dist, 0, 1) ** 2.0
    glow_rgb = np.array(VIOLET, dtype=np.float32) * 0.70
    base += glow[:, :, None] * glow_rgb[None, None, :]
    return Image.fromarray(np.clip(base, 0, 255).astype(np.uint8), 'RGB')


def scale_geometry(values, scale):
    return [v * scale for v in values]


def draw_cat(draw: ImageDraw.ImageDraw, size: float, ox=0.0, oy=0.0, white=WHITE, colored=True,
             mono=None):
    """Draw the cat mark inside a `size` box whose top-left corner is (ox, oy)."""
    def px(v):
        return ox + v * size

    def py(v):
        return oy + v * size

    head = white if mono is None else mono
    ear_inner = VIOLET if colored and mono is None else (head if mono else VIOLET)
    eye_color = INK if mono is None else (0, 0, 0, 0)

    # ears (behind the head)
    left_ear = [(px(0.20), py(0.42)), (px(0.17), py(0.06)), (px(0.45), py(0.28))]
    right_ear = [(px(0.80), py(0.42)), (px(0.83), py(0.06)), (px(0.55), py(0.28))]
    draw.polygon(left_ear, fill=head)
    draw.polygon(right_ear, fill=head)
    draw.polygon([(px(0.235), py(0.36)), (px(0.215), py(0.15)), (px(0.375), py(0.29))], fill=ear_inner)
    draw.polygon([(px(0.765), py(0.36)), (px(0.785), py(0.15)), (px(0.625), py(0.29))], fill=ear_inner)

    # head
    draw.ellipse([px(0.14), py(0.22), px(0.86), py(0.96)], fill=head)

    # eyes
    draw.ellipse([px(0.29), py(0.46), px(0.45), py(0.66)], fill=eye_color)
    draw.ellipse([px(0.55), py(0.46), px(0.71), py(0.66)], fill=eye_color)
    if mono is None:
        draw.ellipse([px(0.325), py(0.50), px(0.375), py(0.56)], fill=WHITE)
        draw.ellipse([px(0.585), py(0.50), px(0.635), py(0.56)], fill=WHITE)

    # nose + mouth
    nose = VIOLET_DEEP if mono is None else head
    draw.polygon([(px(0.455), py(0.72)), (px(0.545), py(0.72)), (px(0.50), py(0.79))], fill=nose)
    if mono is None:
        draw.arc([px(0.40), py(0.72), px(0.50), py(0.86)], start=0, end=120, fill=INK, width=int(size * 0.012))
        draw.arc([px(0.50), py(0.72), px(0.60), py(0.86)], start=60, end=180, fill=INK, width=int(size * 0.012))

    # whiskers
    whisker = VIOLET if mono is None else head
    width = max(1, int(size * 0.014))
    for dx, dy in ((0.26, 0.74), (0.24, 0.80), (0.26, 0.86)):
        draw.line([(px(0.13), py(dy)), (px(dx), py(dy + 0.015))], fill=whisker, width=width)
        draw.line([(px(0.87), py(dy)), (px(1 - dx), py(dy + 0.015))], fill=whisker, width=width)


def draw_bolt(canvas: Image.Image, box, alpha=255):
    """Lightning bolt across the mark (its own layer so it can glow)."""
    x, y, size = box
    layer = Image.new('RGBA', canvas.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    pts = [
        (x + 0.70 * size, y + 0.04 * size),
        (x + 0.46 * size, y + 0.50 * size),
        (x + 0.60 * size, y + 0.50 * size),
        (x + 0.40 * size, y + 0.86 * size),
        (x + 0.76 * size, y + 0.40 * size),
        (x + 0.60 * size, y + 0.40 * size),
    ]
    draw.polygon(pts, fill=VIOLET + (alpha,))
    draw.line(pts + [pts[0]], fill=FUCHSIA + (alpha,), width=max(2, int(size * 0.012)), joint='curve')
    glow = layer.filter(ImageFilter.GaussianBlur(radius=size * 0.05))
    canvas.alpha_composite(glow)
    canvas.alpha_composite(layer)
    return canvas


def rounded_mask(size: int, radius_ratio=0.22) -> Image.Image:
    mask = Image.new('L', (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, size - 1, size - 1], radius=int(size * radius_ratio), fill=255)
    return mask


def icon_image(size: int, rounded=True) -> Image.Image:
    canvas = gradient_background(size).convert('RGBA')
    draw = ImageDraw.Draw(canvas)
    mark = size * 0.62
    draw_cat(draw, mark, ox=(size - mark) / 2, oy=size * 0.14)
    canvas = draw_bolt(canvas, ((size - mark) / 2, size * 0.14, mark))
    if rounded:
        canvas.putalpha(rounded_mask(size))
    return canvas


def foreground_image(size: int) -> Image.Image:
    """Adaptive-icon foreground: cat inside the safe 66% centre, transparent."""
    canvas = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(canvas)
    mark = size * 0.52
    draw_cat(draw, mark, ox=(size - mark) / 2, oy=(size - mark) / 2 + size * 0.02)
    return draw_bolt(canvas, ((size - mark) / 2, (size - mark) / 2 + size * 0.02, mark))


def notification_image(size: int) -> Image.Image:
    canvas = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(canvas)
    draw_cat(draw, size * 0.96, ox=size * 0.02, oy=size * 0.02, mono=(255, 255, 255))
    return canvas


def tv_banner(width=320, height=180) -> Image.Image:
    canvas = gradient_background(max(width, height)).convert('RGBA')
    canvas = canvas.resize((width, height), Image.LANCZOS)
    draw = ImageDraw.Draw(canvas)
    mark = height * 0.72
    draw_cat(draw, mark, ox=width * 0.06, oy=height * 0.16)
    canvas = draw_bolt(canvas, (width * 0.06, height * 0.16, mark))
    draw = ImageDraw.Draw(canvas)
    try:
        font = ImageFont.truetype(str(FONT_BOLD), int(height * 0.20))
        small = ImageFont.truetype(str(FONT_BOLD), int(height * 0.10))
    except OSError:
        font = ImageFont.load_default()
        small = font
    text_x = width * 0.06 + mark + width * 0.04
    draw.text((text_x, height * 0.34), 'Cat Client', font=font, fill=WHITE)
    draw.text((text_x, height * 0.58), 'VLESS · Trojan · WARP', font=small, fill=(196, 181, 253, 255))
    return canvas


def main():
    produced = []

    def save(image: Image.Image, relative: str):
        path = RES / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        image.save(path)
        produced.append(str(path.relative_to(RES)))

    # master art (launcher, legacy drawables, WhiteDNS leftovers replaced)
    master = icon_image(1024)
    save(master, 'drawable-nodpi/cat_launcher_icon.png')
    save(master, 'drawable/cat_launcher_icon.png')
    save(master, 'drawable-nodpi/whitedns_logo.png')
    save(foreground_image(1024), 'drawable-nodpi/cat_launcher_foreground.png')

    # raster mipmaps (legacy launchers)
    for density, size in (('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192)):
        save(icon_image(size), f'mipmap-{density}/ic_launcher.png')
        save(icon_image(size, rounded=False), f'mipmap-{density}/ic_launcher_round.png')

    # notification + TV banner
    save(notification_image(96), 'drawable-xxxhdpi/ic_notification.png')
    save(tv_banner(), 'drawable-xhdpi/tv_banner.png')

    print('\n'.join(produced))
    print(f'\n{len(produced)} branding assets written under {RES}')


if __name__ == '__main__':
    main()
