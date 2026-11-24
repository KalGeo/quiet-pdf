#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Puts the raw screenshots in a phone-shaped frame for the README.

Reads every PNG in docs/screenshots/ (the plain 1080x2400 captures, which are also what a
store listing needs) and writes a framed copy to docs/screenshots/framed/: rounded screen
corners, a thin bezel and a transparent background, so they sit well on a light or a dark
page.

Run from the repository root after retaking screenshots; needs Pillow:

    python3 tools/frame_screenshots.py
"""
from pathlib import Path

from PIL import Image, ImageDraw

SOURCE = Path("docs/screenshots")
OUTPUT = SOURCE / "framed"

OUTPUT_WIDTH = 720          # the README shows them far smaller; this keeps them sharp when opened
BEZEL = 0.013               # bezel thickness, as a fraction of the screen width
CORNER = 0.10               # screen corner radius, as a fraction of the screen width
BEZEL_COLOUR = (206, 206, 212, 255)
SUPERSAMPLE = 4             # masks are drawn this much larger, then shrunk, for smooth corners


def rounded_mask(size: tuple[int, int], radius: int) -> Image.Image:
    big = Image.new("L", (size[0] * SUPERSAMPLE, size[1] * SUPERSAMPLE), 0)
    ImageDraw.Draw(big).rounded_rectangle(
        (0, 0, big.width - 1, big.height - 1), radius * SUPERSAMPLE, fill=255
    )
    return big.resize(size, Image.LANCZOS)


def frame(screen: Image.Image) -> Image.Image:
    width, height = screen.size
    bezel = round(width * BEZEL)
    corner = round(width * CORNER)
    size = (width + 2 * bezel, height + 2 * bezel)

    phone = Image.new("RGBA", size, (0, 0, 0, 0))
    phone.paste(Image.new("RGBA", size, BEZEL_COLOUR), (0, 0), rounded_mask(size, corner + bezel))
    phone.paste(screen.convert("RGBA"), (bezel, bezel), rounded_mask(screen.size, corner))

    scale = OUTPUT_WIDTH / size[0]
    return phone.resize((OUTPUT_WIDTH, round(size[1] * scale)), Image.LANCZOS)


def main() -> None:
    OUTPUT.mkdir(exist_ok=True)
    for path in sorted(SOURCE.glob("*.png")):
        framed = frame(Image.open(path))
        framed.save(OUTPUT / path.name, optimize=True)
        print(f"{path} -> {OUTPUT / path.name} {framed.size}")


if __name__ == "__main__":
    main()
