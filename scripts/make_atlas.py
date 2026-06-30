#!/usr/bin/env python3
"""
Packs a directory of same-size tile PNGs into a uniform-grid texture atlas.

Usage:
    python3 tools/make_atlas.py <tiles_dir> <output_atlas.png> [--cols N] [--tile-size WxH]

Examples:
    python3 tools/make_atlas.py art/tiles app/src/commonMain/composeResources/drawable/dungeon_atlas.png
    python3 tools/make_atlas.py art/tiles out.png --cols 8
    python3 tools/make_atlas.py art/tiles out.png --tile-size 64x64

Output:
    <output_atlas.png>        — the packed atlas image
    <output_atlas.txt>        — tile name → index mapping for use in code
"""

import argparse
import math
import sys
from pathlib import Path
from PIL import Image


def parse_args():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("tiles_dir", help="Directory containing tile PNG files")
    p.add_argument("output", help="Output atlas PNG path")
    p.add_argument("--cols", type=int, default=0, help="Columns in the atlas grid (default: ceil(sqrt(n)))")
    p.add_argument("--tile-size", help="Force tile size as WxH, e.g. 64x64 (default: inferred from first tile)")
    return p.parse_args()


def main():
    args = parse_args()

    tiles_dir = Path(args.tiles_dir)
    if not tiles_dir.is_dir():
        sys.exit(f"error: tiles_dir '{tiles_dir}' is not a directory")

    tile_paths = sorted(tiles_dir.glob("*.png"))
    if not tile_paths:
        sys.exit(f"error: no PNG files found in '{tiles_dir}'")

    # Determine tile size
    if args.tile_size:
        try:
            tw, th = (int(v) for v in args.tile_size.lower().split("x"))
        except ValueError:
            sys.exit("error: --tile-size must be WxH, e.g. 64x64")
    else:
        with Image.open(tile_paths[0]) as probe:
            tw, th = probe.size

    # Validate all tiles match expected size
    bad = []
    for p in tile_paths:
        with Image.open(p) as img:
            if img.size != (tw, th):
                bad.append(f"  {p.name}: {img.size[0]}x{img.size[1]}")
    if bad:
        sys.exit("error: tile size mismatch (expected {}x{}):\n{}".format(tw, th, "\n".join(bad)))

    n = len(tile_paths)
    cols = args.cols if args.cols > 0 else math.ceil(math.sqrt(n))
    rows = math.ceil(n / cols)

    atlas = Image.new("RGBA", (cols * tw, rows * th), (0, 0, 0, 0))

    index_lines = []
    for i, path in enumerate(tile_paths):
        col = i % cols
        row = i // cols
        with Image.open(path) as tile:
            atlas.paste(tile.convert("RGBA"), (col * tw, row * th))
        index_lines.append(f"{i:3d}  {path.stem}")

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    atlas.save(output, "PNG")

    index_path = output.with_suffix(".txt")
    index_path.write_text(
        f"# Atlas: {output.name}  tile_size={tw}x{th}  cols={cols}  rows={rows}\n"
        + "# index  name\n"
        + "\n".join(index_lines)
        + "\n"
    )

    print(f"Atlas:  {output}  ({cols * tw}x{rows * th}px, {cols} cols × {rows} rows)")
    print(f"Tiles:  {n} tiles at {tw}x{th}px each")
    print(f"Index:  {index_path}")
    print()
    for line in index_lines:
        print(" ", line)


if __name__ == "__main__":
    main()
