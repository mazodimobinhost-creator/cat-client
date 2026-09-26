#!/usr/bin/env python3
"""Cat Client branding assets (purple / black / white).

Draws the launcher icon, adaptive-icon foreground (+ monochrome themed layer),
notification icon and the Android TV banner with Pillow — reproducible output.

Design: a clean white cat head with a violet lightning bolt rising from behind
the head (tip framed between the ears). The bolt never crosses the face.

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
WHITE = (250, 248, 255)
INK = (16, 10, 28)
DARK_TOP = (58, 18, 100)
DARK_BOTTOM = (8, 4, 16)


def gradient_background(size: int) -> Image.Image:
    """Vertical purple-black gradient with a soft violet glow behind the mark."""
    base = np.zeros((size, size, 3), dtype=np.float32)
    for y in range(size):
        t = y / max(size - 1, 1)
        base[y, :, :] = np.array(DARK_TOP) * (1 - t) + np.array(DARK_BOTTOM) * t
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float32)
    dist = np.sqrt((xx - size * 0.5) ** 2 + (yy - size * 0.58) ** 2) / (size * 0.62)
    glow = np.clip(1.0 - dist, 0, 1) ** 2.2
    base += glow[:, :, None] * (np.array(VIOLET, dtype=np.float32) * 0.55)[None, None, :]
    return Image.fromarray(np.clip(base, 0, 255).astype(np.uint8), 'RGB')


def _bolt_points(cx, cy, size):
    """Zigzag bolt, vertical, tip up. size = height of the bolt."""
    w = size * 0.46
    h = size
    return [
        (cx + 0.10 * w, cy - 0.50 * h),          # top tip
        (cx - 0.48 * w, cy + 0.06 * h),          # left shoulder
        (cx - 0.06 * w, cy + 0.06 * h),          # left notch
        (cx - 0.24 * w, cy + 0.50 * h),          # bottom tip
        (cx + 0.50 * w, cy - 0.10 * h),          # right shoulder
        (cx + 0.06 * w, cy - 0.10 * h),          # right notch
    ]


def draw_bolt(canvas: Image.Image, cx, cy, size, mono=None):
    """Lightning bolt on its own layer (soft glow), drawn BEHIND the cat."""
    layer = Image.new('RGBA', canvas.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    pts = _bolt_points(cx, cy, size)
    if mono is not None:
        draw.polygon(pts, fill=mono + (255,))
    else:
        glow = layer.filter(ImageFilter.GaussianBlur(radius=size * 0.10))
        gdraw = ImageDraw.Draw(glow)
        gdraw.polygon(pts, fill=FUCHSIA + (140,))
        canvas.alpha_composite(glow)
        draw.polygon(pts, fill=VIOLET_DEEP + (255,))
        draw.line(pts + [pts[0]], fill=FUCHSIA + (255,), width=max(2, int(size * 0.045)), joint='curve')
        # white highlight edge on the right flank of the bolt
        draw.line([pts[0], pts[4]], fill=WHITE + (230,), width=max(2, int(size * 0.03)))
    canvas.alpha_composite(layer)
    return canvas


def draw_cat(canvas: Image.Image, cx, cy, head_r, mono=None, colored=True):
    """Clean white cat head. (cx, cy) = head centre, head_r = head radius."""
    layer = Image.new('RGBA', canvas.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    head = WHITE if mono is None else mono + (255,)
    ear_inner = (VIOLET + (255,)) if (colored and mono is None) else head
    eye = (INK + (255,)) if mono is None else (0, 0, 0, 0)
    nose = (FUCHSIA + (255,)) if mono is None else head

    def P(dx, dy):
        return (cx + dx * head_r, cy + dy * head_r)

    # ears (behind the head)
    left_ear = [P(-0.92, -0.34), P(-0.70, -1.28), P(-0.14, -0.74)]
    right_ear = [P(0.92, -0.34), P(0.70, -1.28), P(0.14, -0.74)]
    draw.polygon(left_ear, fill=head)
    draw.polygon(right_ear, fill=head)
    draw.polygon([P(-0.76, -0.44), P(-0.64, -1.05), P(-0.30, -0.68)], fill=ear_inner)
    draw.polygon([P(0.76, -0.44), P(0.64, -1.05), P(0.30, -0.68)], fill=ear_inner)

    # head
    draw.ellipse([cx - head_r, cy - head_r, cx + head_r, cy + head_r], fill=head)

    # eyes — big and round with double highlights
    for sign in (-1, 1):
        ex = cx + sign * 0.40 * head_r
        ey = cy + 0.02 * head_r
        r = 0.23 * head_r
        draw.ellipse([ex - r, ey - 1.15 * r, ex + r, ey + 1.15 * r], fill=eye)
        if mono is None:
            draw.ellipse([ex - 0.42 * r, ey - 0.85 * r, ex - 0.02 * r, ey - 0.35 * r], fill=WHITE)
            draw.ellipse([ex + 0.05 * r, ey + 0.15 * r, ex + 0.28 * r, ey + 0.45 * r], fill=WHITE)

    # nose + mouth
    nw = 0.13 * head_r
    ny = cy + 0.48 * head_r
    draw.polygon([(cx - nw, ny - nw * 0.5), (cx + nw, ny - nw * 0.5), (cx, ny + nw)], fill=nose)
    if mono is None:
        wline = max(2, int(head_r * 0.055))
        draw.arc([cx - 0.34 * head_r, ny + 0.02 * head_r, cx, ny + 0.42 * head_r],
                 start=290, end=360, fill=INK + (255,), width=wline)
        draw.arc([cx, ny + 0.02 * head_r, cx + 0.34 * head_r, ny + 0.42 * head_r],
                 start=180, end=250, fill=INK + (255,), width=wline)

    # whiskers
    whisker = (WHITE if mono is None else head)
    width = max(2, int(head_r * 0.06))
    for dy in (0.38, 0.56, 0.74):
        draw.line([P(-1.32, dy + 0.06), P(-0.70, dy)], fill=whisker, width=width)
        draw.line([P(1.32, dy + 0.06), P(0.70, dy)], fill=whisker, width=width)

    canvas.alpha_composite(layer)
    return canvas


def draw_mark(canvas: Image.Image, scale, mono=None, colored=True):
    """Compose bolt-behind-cat centred on the canvas at the given head radius."""
    n = canvas.size[0]
    head_r = n * scale
    cx, cy = n * 0.5, n * 0.60
    canvas = draw_bolt(canvas, cx + head_r * 0.34, cy - head_r * 0.55, head_r * 2.4, mono=mono)
    return draw_cat(canvas, cx, cy, head_r, mono=mono, colored=colored)


def rounded_mask(size: int, radius_ratio=0.22) -> Image.Image:
    mask = Image.new('L', (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, size - 1, size - 1], radius=int(size * radius_ratio), fill=255)
    return mask


def icon_image(size: int, rounded=True) -> Image.Image:
    canvas = gradient_background(size).convert('RGBA')
    canvas = draw_mark(canvas, 0.245)
    if rounded:
        canvas.putalpha(rounded_mask(size))
    return canvas


def foreground_image(size: int) -> Image.Image:
    """Adaptive-icon foreground: whole mark inside the safe 66% centre."""
    canvas = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    return draw_mark(canvas, 0.155)


def monochrome_image(size: int) -> Image.Image:
    """Android 13+ themed icon: single-colour silhouette."""
    canvas = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    return draw_mark(canvas, 0.155, mono=(255, 255, 255), colored=False)


def notification_image(size: int) -> Image.Image:
    canvas = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    return draw_cat(canvas, size * 0.5, size * 0.56, size * 0.34, mono=(255, 255, 255))


def tv_banner(width=320, height=180) -> Image.Image:
    canvas = gradient_background(max(width, height)).convert('RGBA')
    canvas = canvas.resize((width, height), Image.LANCZOS)
    head_r = height * 0.30
    cx, cy = width * 0.24, height * 0.52
    canvas = draw_bolt(canvas, cx + head_r * 0.34, cy - head_r * 0.55, head_r * 2.4)
    canvas = draw_cat(canvas, cx, cy, head_r)
    draw = ImageDraw.Draw(canvas)
    try:
        font = ImageFont.truetype(str(FONT_BOLD), int(height * 0.19))
        small = ImageFont.truetype(str(FONT_BOLD), int(height * 0.095))
    except OSError:
        font = ImageFont.load_default()
        small = font
    text_x = width * 0.42
    draw.text((text_x, height * 0.30), 'Cat Client', font=font, fill=WHITE)
    draw.text((text_x, height * 0.58), 'VLESS · Trojan · WARP', font=small, fill=(209, 196, 255, 255))
    return canvas


def main():
    def save(image: Image.Image, relative: str):
        path = RES / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        image.save(path)
        print('wrote', path.relative_to(RES.parents[3]))

    master = icon_image(1024)
    save(master, 'drawable-nodpi/cat_launcher_icon.png')
    save(master, 'drawable/cat_launcher_icon.png')
    save(master, 'drawable-nodpi/whitedns_logo.png')
    save(foreground_image(1024), 'drawable-nodpi/cat_launcher_foreground.png')
    save(monochrome_image(1024), 'drawable-nodpi/cat_launcher_monochrome.png')

    for density, size in (('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192)):
        save(icon_image(size), f'mipmap-{density}/ic_launcher.png')
        save(icon_image(size, rounded=False), f'mipmap-{density}/ic_launcher_round.png')

    save(notification_image(96), 'drawable-xxxhdpi/ic_notification.png')
    save(tv_banner(), 'drawable-xhdpi/tv_banner.png')


if __name__ == '__main__':
    main()
