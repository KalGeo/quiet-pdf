#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Makes the store listing graphics in fastlane/metadata/android/<locale>/images/.

- icon.png             512x512, drawn from the same shapes as the launcher icon
- featureGraphic.png   1024x500
- phoneScreenshots/    1080x1920: a caption above a framed screenshot. Google Play rejects
                       the raw 1080x2400 captures, whose long side is more than twice the
                       short one.

Run from the repository root after tools/frame_screenshots.py; needs Pillow and the Noto Sans
fonts (with Greek):

    python3 tools/store_graphics.py
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

FRAMED = Path("docs/screenshots/framed")
METADATA = Path("fastlane/metadata/android")
FONT_BOLD = "/usr/share/fonts/truetype/noto/NotoSans-Bold.ttf"
FONT_REGULAR = "/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf"

RED = (211, 47, 47)
PALE_RED = (255, 205, 210)
BACKGROUND = (247, 242, 242)
INK = (32, 26, 26)
SOFT_INK = (96, 84, 84)

# (screenshot, caption) per locale, in store order.
CAPTIONS = {
    "en-US": [
        ("2-reader", "Opens your PDFs.\nNothing else."),
        ("3-fullscreen", "The whole screen\nfor the page"),
        ("4-search", "Find any word,\nhighlighted"),
        ("5-night", "Night mode for\nlate reading"),
        ("1-recent", "Back where you\nstopped, every time"),
    ],
    "el-GR": [
        ("2-reader", "Ανοίγει τα PDF σου.\nΤίποτα άλλο."),
        ("3-fullscreen", "Όλη η οθόνη\nγια τη σελίδα"),
        ("4-search", "Βρες κάθε λέξη,\nεπισημασμένη"),
        ("5-night", "Νυχτερινή λειτουργία\nγια το βράδυ"),
        ("1-recent", "Συνεχίζεις από εκεί\nπου σταμάτησες"),
    ],
}
TAGLINE = {
    "en-US": "Just your PDFs.\nNo tracking, no internet access.",
    "el-GR": "Μόνο τα PDF σου.\nΧωρίς παρακολούθηση, χωρίς internet.",
}


def draw_page_icon(img: Image.Image, scale: float, offset: tuple[float, float]) -> None:
    """The launcher icon's page (viewport units 0..108) at [scale] pixels per unit."""
    ox, oy = offset
    d = ImageDraw.Draw(img)
    p = lambda pts: [(ox + x * scale, oy + y * scale) for x, y in pts]
    d.polygon(p([(36, 28), (61, 28), (72, 39), (72, 80), (36, 80)]), fill="white")
    d.polygon(p([(61, 28), (61, 39), (72, 39)]), fill=PALE_RED)
    for top, right in [(47, 66), (54, 66), (61, 66), (68, 56)]:
        d.rectangle(p([(42, top), (right, top + 3)]), fill=RED)


def icon() -> Image.Image:
    size, ss = 512, 4
    big = Image.new("RGB", (size * ss, size * ss), RED)
    draw_page_icon(big, size * ss / 108, (0, 0))
    return big.resize((size, size), Image.LANCZOS)


def text_block(draw, xy, text, font, fill, spacing, anchor="la"):
    draw.multiline_text(xy, text, font=font, fill=fill, spacing=spacing, anchor=anchor, align="left")


def feature_graphic(locale: str) -> Image.Image:
    w, h, ss = 1024, 500, 2
    img = Image.new("RGB", (w * ss, h * ss), RED)
    # big icon page on the left
    draw_page_icon(img, 7.2 * ss, (-150 * ss, -45 * ss))
    d = ImageDraw.Draw(img)
    text_block(d, (430 * ss, 150 * ss), "Quiet PDF", ImageFont.truetype(FONT_BOLD, 84 * ss), "white", 0)
    text_block(d, (434 * ss, 270 * ss), TAGLINE[locale], ImageFont.truetype(FONT_REGULAR, 30 * ss),
               (255, 235, 235), 12 * ss)
    return img.resize((w, h), Image.LANCZOS)


def screenshot(name: str, caption: str) -> Image.Image:
    w, h = 1080, 1920
    img = Image.new("RGBA", (w, h), BACKGROUND + (255,))
    d = ImageDraw.Draw(img)
    font = ImageFont.truetype(FONT_BOLD, 66)
    d.multiline_text((w // 2, 175), caption, font=font, fill=INK, anchor="mm", align="center", spacing=14)
    phone = Image.open(FRAMED / f"{name}.png")
    target_h = h - 330 - 50
    phone = phone.resize((round(phone.width * target_h / phone.height), target_h), Image.LANCZOS)
    img.alpha_composite(phone, ((w - phone.width) // 2, 330))
    return img.convert("RGB")


def main() -> None:
    for locale, shots in CAPTIONS.items():
        images = METADATA / locale / "images"
        (images / "phoneScreenshots").mkdir(parents=True, exist_ok=True)
        icon().save(images / "icon.png", optimize=True)
        feature_graphic(locale).save(images / "featureGraphic.png", optimize=True)
        for i, (name, caption) in enumerate(shots, start=1):
            screenshot(name, caption).save(images / "phoneScreenshots" / f"{i}.png", optimize=True)
        print(f"{locale}: icon, featureGraphic, {len(shots)} screenshots")


if __name__ == "__main__":
    main()
