"""Build the small deterministic PPT-010 Visual QA Gold Set.

The clean inputs are copied from the real Runner PNG fixture. Broken inputs are
controlled raster mutations; no model output is used to create labels.
"""

from __future__ import annotations

import argparse
import shutil
from pathlib import Path

from PIL import Image, ImageDraw, ImageEnhance, ImageFont


WIDTH, HEIGHT = 1500, 844
FONT_CANDIDATES = [
    Path("C:/Windows/Fonts/arial.ttf"),
    Path("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"),
]


def font(size: int):
    for candidate in FONT_CANDIDATES:
        if candidate.exists():
            return ImageFont.truetype(str(candidate), size)
    return ImageFont.load_default()


def canvas(source: Image.Image) -> Image.Image:
    return source.convert("RGBA").resize((WIDTH, HEIGHT))


def draw_lines(draw: ImageDraw.ImageDraw, x: int, y: int, count: int, width: int, gap: int, fill):
    for index in range(count):
        draw.rectangle((x, y + index * gap, x + width - (index % 5) * 22, y + index * gap + 5), fill=fill)


def mutate(source: Image.Image, code: str) -> Image.Image:
    image = canvas(source)
    overlay = Image.new("RGBA", image.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)
    dark = (20, 42, 36, 235)
    accent = (195, 112, 62, 235)
    pale = (226, 228, 205, 180)

    if code == "TEXT_OVERFLOW":
        draw.text((80, 180), "OVERFLOW: this deliberately extends beyond the slide boundary", font=font(72), fill=dark)
        draw_lines(draw, 90, 330, 5, 1450, 42, dark)
    elif code == "TEXT_TOO_SMALL":
        small = image.resize((520, 293))
        image = Image.new("RGBA", image.size, (247, 246, 238, 255))
        image.alpha_composite(small, (80, 100))
        draw = ImageDraw.Draw(overlay)
        draw.text((760, 190), "tiny text", font=font(10), fill=dark)
    elif code == "ELEMENT_OVERLAP":
        draw.rounded_rectangle((140, 210, 830, 650), radius=24, fill=(24, 76, 63, 235))
        draw.rounded_rectangle((620, 350, 1370, 760), radius=24, fill=(195, 112, 62, 210))
        draw.text((220, 390), "ELEMENT A", font=font(54), fill=(255, 255, 255, 255))
        draw.text((760, 510), "ELEMENT B", font=font(54), fill=(255, 255, 255, 255))
    elif code == "ELEMENT_CLIPPED":
        draw.rectangle((1180, 210, 1660, 720), outline=accent, width=28)
        draw.text((1210, 380), "CLIPPED", font=font(72), fill=dark)
    elif code == "UNBALANCED_LAYOUT":
        draw.rectangle((70, 110, 1390, 760), fill=(247, 246, 238, 255), outline=pale, width=4)
        draw.rounded_rectangle((100, 145, 1280, 720), radius=26, fill=(24, 76, 63, 235))
        draw.text((145, 205), "ONE DOMINANT REGION", font=font(74), fill=(255, 255, 255, 255))
        draw_lines(draw, 150, 340, 8, 940, 42, (233, 235, 215, 215))
        draw.rounded_rectangle((1320, 190, 1375, 245), radius=10, fill=accent)
    elif code == "VISUAL_HIERARCHY_WEAK":
        draw.rectangle((90, 110, 1410, 750), fill=(247, 246, 238, 255), outline=pale, width=4)
        for row in range(4):
            for column in range(5):
                x = 135 + column * 250
                y = 165 + row * 135
                draw.rectangle((x, y, x + 190, y + 78), fill=(116, 128, 117, 235), outline=(85, 94, 86, 235), width=3)
                draw.rectangle((x + 22, y + 24, x + 168, y + 30), fill=(224, 226, 209, 235))
    elif code == "EXCESSIVE_EMPTY_SPACE":
        small = image.resize((470, 264))
        image = Image.new("RGBA", image.size, (247, 246, 238, 255))
        image.alpha_composite(small, (70, 70))
    elif code == "LOW_CONTRAST":
        image = ImageEnhance.Contrast(image).enhance(0.35)
        draw.text((160, 280), "LOW CONTRAST", font=font(86), fill=(218, 220, 211, 150))
    elif code == "TABLE_UNREADABLE":
        draw.rectangle((120, 140, 1380, 720), fill=(248, 248, 244, 255), outline=dark, width=4)
        for row in range(16):
            y = 140 + row * 36
            draw.line((120, y, 1380, y), fill=pale, width=2)
        for column in range(14):
            x = 120 + column * 97
            draw.line((x, 140, x, 720), fill=pale, width=2)
        draw_lines(draw, 135, 155, 15, 1200, 36, dark)
    elif code == "CHART_UNREADABLE":
        draw.rectangle((150, 160, 1350, 680), fill=(250, 250, 247, 255), outline=pale, width=4)
        for y in range(180, 680, 40):
            draw.line((180, y, 1320, y), fill=pale, width=2)
        points = [(190 + index * 92, 620 - ((index * 83) % 390)) for index in range(13)]
        draw.line(points, fill=dark, width=3)
        for x, y in points:
            draw.ellipse((x - 8, y - 8, x + 8, y + 8), fill=dark)
        draw_lines(draw, 160, 700, 4, 1200, 22, (80, 90, 82, 160))
    elif code == "FLOW_UNREADABLE":
        for row in range(3):
            for column in range(7):
                x = 100 + column * 205
                y = 190 + row * 190
                draw.rounded_rectangle((x, y, x + 145, y + 82), radius=8, outline=dark, width=4)
                if column < 6:
                    draw.line((x + 145, y + 41, x + 200, y + 41), fill=accent, width=4)
        draw_lines(draw, 100, 760, 3, 1300, 18, dark)
    elif code == "DENSE_CONTENT":
        draw.rectangle((80, 120, 1420, 760), fill=(248, 248, 244, 255), outline=pale, width=4)
        for row in range(22):
            draw_lines(draw, 110, 145 + row * 27, 1, 1240, 8, dark)
            for column in range(8):
                x = 110 + column * 155
                draw.rectangle((x, 145 + row * 27, x + 112, 148 + row * 27), fill=accent)
    elif code == "BROKEN_RENDERING":
        draw.rectangle((0, 330, WIDTH, 560), fill=(10, 10, 14, 255))
        for index in range(12):
            x = index * 137
            draw.rectangle((x, 350 + (index % 3) * 28, x + 94, 530 - (index % 4) * 19), fill=(220, 52, 111, 240))
            draw.line((x, 330, x + 110, 560), fill=(52, 230, 173, 230), width=9)
    else:
        raise ValueError(f"unsupported mutation: {code}")

    return Image.alpha_composite(image, overlay).convert("RGB")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source_dir", type=Path)
    parser.add_argument("output_dir", type=Path)
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)

    clean_slides = [1, 2, 5, 6, 7, 8, 9]
    for slide_number in clean_slides:
        source = args.source_dir / f"slide-{slide_number:02d}.png"
        if not source.exists():
            raise SystemExit(f"missing real fixture: {source}")
        shutil.copyfile(source, args.output_dir / f"clean-slide-{slide_number:02d}.png")

    mutations = [
        "TEXT_OVERFLOW", "TEXT_TOO_SMALL", "ELEMENT_OVERLAP", "ELEMENT_CLIPPED",
        "UNBALANCED_LAYOUT", "EXCESSIVE_EMPTY_SPACE", "LOW_CONTRAST", "VISUAL_HIERARCHY_WEAK",
        "TABLE_UNREADABLE", "CHART_UNREADABLE", "FLOW_UNREADABLE", "DENSE_CONTENT", "BROKEN_RENDERING",
    ]
    sources = {"TABLE_UNREADABLE": 8, "CHART_UNREADABLE": 6, "FLOW_UNREADABLE": 5}
    for code in mutations:
        source_number = sources.get(code, 2)
        source = Image.open(args.source_dir / f"slide-{source_number:02d}.png")
        mutate(source, code).save(args.output_dir / f"broken-{code.lower()}.png", format="PNG", optimize=False)


if __name__ == "__main__":
    main()
