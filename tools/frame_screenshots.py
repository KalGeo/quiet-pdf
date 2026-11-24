#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Puts the raw screenshots in an Android phone frame for the README.

Reads every PNG in docs/screenshots/ (the plain 1080x2400 captures, which are also what a
store listing needs) and writes a framed copy to docs/screenshots/framed/: a dark bezel with
a centred punch-hole camera and side buttons, on a transparent background. A lighter rim
around the body keeps the phone visible on a dark page.

Run from the repository root after retaking screenshots; needs Pillow:

    python3 tools/frame_screenshots.py
"""
from pathlib import Path

from PIL import Image, ImageDraw

SOURCE = Path("docs/screenshots")
OUTPUT = SOURCE / "framed"

OUTPUT_WIDTH = 720          # the README shows them far smaller; this keeps them sharp when opened
SUPERSAMPLE = 4             # shapes are drawn this much larger, then shrunk, for smooth edges

# Sizes are fractions of the screen width, so any capture size works.
BEZEL = 0.024               # dark band between the screen and the edge of the body
RIM = 0.004                 # lighter line around the body
CORNER = 0.078              # screen corner radius
CAMERA = 0.042              # punch-hole diameter
CAMERA_TOP = 0.036          # distance from the top of the screen to the camera's edge
BUTTON_DEPTH = 0.007        # how far the side buttons stick out
# Side buttons on the right edge: (top, height), as fractions of the screen height.
BUTTONS = [(0.20, 0.065), (0.30, 0.13)]

BODY = (22, 22, 26, 255)
RIM_COLOUR = (92, 92, 100, 255)
CAMERA_COLOUR = (6, 6, 8, 255)
LENS_COLOUR = (30, 30, 38, 255)


def rounded_mask(size: tuple[int, int], radius: int) -> Image.Image:
    big = Image.new("L", (size[0] * SUPERSAMPLE, size[1] * SUPERSAMPLE), 0)
    ImageDraw.Draw(big).rounded_rectangle(
        (0, 0, big.width - 1, big.height - 1), radius * SUPERSAMPLE, fill=255
    )
    return big.resize(size, Image.LANCZOS)


def paste_rounded(target: Image.Image, colour, box: tuple[int, int, int, int], radius: int) -> None:
    """Fills the rounded rectangle [box] (left, top, width, height) with [colour]."""
    left, top, width, height = box
    target.paste(Image.new("RGBA", (width, height), colour), (left, top), rounded_mask((width, height), radius))


def frame(screen: Image.Image) -> Image.Image:
    width, height = screen.size
    bezel, rim = round(width * BEZEL), max(2, round(width * RIM))
    corner = round(width * CORNER)
    depth = round(width * BUTTON_DEPTH)
    edge = bezel + rim
    body_w, body_h = width + 2 * edge, height + 2 * edge

    # The same margin on both sides keeps the phone centred although only one side has buttons.
    phone = Image.new("RGBA", (body_w + 2 * depth, body_h), (0, 0, 0, 0))
    for top, length in BUTTONS:
        paste_rounded(phone, RIM_COLOUR, (depth + body_w - depth, edge + round(height * top), 2 * depth, round(height * length)), depth)
    paste_rounded(phone, RIM_COLOUR, (depth, 0, body_w, body_h), corner + edge)
    paste_rounded(phone, BODY, (depth + rim, rim, body_w - 2 * rim, body_h - 2 * rim), corner + bezel)
    phone.paste(screen.convert("RGBA"), (depth + edge, edge), rounded_mask(screen.size, corner))

    camera = round(width * CAMERA)
    lens = round(camera * 0.42)
    centre_x = depth + edge + width // 2
    camera_top = edge + round(width * CAMERA_TOP)
    paste_rounded(phone, CAMERA_COLOUR, (centre_x - camera // 2, camera_top, camera, camera), camera // 2)
    paste_rounded(phone, LENS_COLOUR, (centre_x - lens // 2, camera_top + (camera - lens) // 2, lens, lens), lens // 2)

    scale = OUTPUT_WIDTH / phone.width
    return phone.resize((OUTPUT_WIDTH, round(phone.height * scale)), Image.LANCZOS)


def main() -> None:
    OUTPUT.mkdir(exist_ok=True)
    for path in sorted(SOURCE.glob("*.png")):
        framed = frame(Image.open(path))
        framed.save(OUTPUT / path.name, optimize=True)
        print(f"{path} -> {OUTPUT / path.name} {framed.size}")


if __name__ == "__main__":
    main()
