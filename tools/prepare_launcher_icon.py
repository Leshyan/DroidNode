#!/usr/bin/env python3
"""Generate launcher-friendly foreground assets from logo image.

- Keep transparency
- Crop to non-transparent content
- Fit content into a square safe area
- Export color foreground + optional monochrome mask
"""

from __future__ import annotations

import argparse
from pathlib import Path
from PIL import Image
from collections import deque


def fill_alpha_holes(img: Image.Image) -> Image.Image:
    """Fill transparent holes not connected to image borders."""
    rgba = img.convert("RGBA")
    w, h = rgba.size
    a = rgba.getchannel("A")
    ap = a.load()

    visited = [[False] * w for _ in range(h)]
    q = deque()

    for x in range(w):
        if ap[x, 0] == 0:
            visited[0][x] = True
            q.append((x, 0))
        if ap[x, h - 1] == 0 and not visited[h - 1][x]:
            visited[h - 1][x] = True
            q.append((x, h - 1))
    for y in range(h):
        if ap[0, y] == 0 and not visited[y][0]:
            visited[y][0] = True
            q.append((0, y))
        if ap[w - 1, y] == 0 and not visited[y][w - 1]:
            visited[y][w - 1] = True
            q.append((w - 1, y))

    while q:
        x, y = q.popleft()
        for nx, ny in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
            if 0 <= nx < w and 0 <= ny < h and not visited[ny][nx] and ap[nx, ny] == 0:
                visited[ny][nx] = True
                q.append((nx, ny))

    fixed = rgba.copy()
    fp = fixed.load()
    for y in range(h):
        for x in range(w):
            if ap[x, y] == 0 and not visited[y][x]:
                r, g, b, _ = fp[x, y]
                # Keep original RGB when available; fallback to white.
                if r == 0 and g == 0 and b == 0:
                    r, g, b = 255, 255, 255
                fp[x, y] = (r, g, b, 255)
    return fixed


def make_assets(src: Path, out_fg: Path, out_mono: Path, size: int, content_ratio: float) -> None:
    img = fill_alpha_holes(Image.open(src).convert("RGBA"))
    alpha = img.getchannel("A")
    bbox = alpha.getbbox()
    if bbox is None:
        raise RuntimeError("No visible pixels in source image")

    cropped = img.crop(bbox)
    cropped_alpha = cropped.getchannel("A")

    target = max(1, int(round(size * content_ratio)))
    scale = min(target / cropped.width, target / cropped.height)
    new_w = max(1, int(round(cropped.width * scale)))
    new_h = max(1, int(round(cropped.height * scale)))

    resized = cropped.resize((new_w, new_h), Image.Resampling.LANCZOS)
    resized_alpha = cropped_alpha.resize((new_w, new_h), Image.Resampling.LANCZOS)

    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    x = (size - new_w) // 2
    y = (size - new_h) // 2
    canvas.alpha_composite(resized, (x, y))
    canvas = fill_alpha_holes(canvas)

    mono = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    # White silhouette from alpha mask; launcher will recolor in themed mode if needed.
    mono_shape = Image.new("RGBA", (new_w, new_h), (255, 255, 255, 255))
    mono_shape.putalpha(resized_alpha)
    mono.alpha_composite(mono_shape, (x, y))
    mono = fill_alpha_holes(mono)

    out_fg.parent.mkdir(parents=True, exist_ok=True)
    out_mono.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(out_fg, format="PNG", optimize=True)
    mono.save(out_mono, format="PNG", optimize=True)

    print("[ok] launcher assets generated")
    print(f"  src={src}")
    print(f"  fg={out_fg}")
    print(f"  mono={out_mono}")
    print(f"  size={size} content_ratio={content_ratio} bbox={bbox}")


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--src", default="docs/assets/droidnode-logo.png")
    p.add_argument("--out-fg", default="app/src/main/res/drawable-nodpi/droidnode_icon_fg.png")
    p.add_argument("--out-mono", default="app/src/main/res/drawable-nodpi/droidnode_icon_mono.png")
    p.add_argument("--size", type=int, default=432)
    p.add_argument("--content-ratio", type=float, default=0.31)
    args = p.parse_args()

    make_assets(
        src=Path(args.src),
        out_fg=Path(args.out_fg),
        out_mono=Path(args.out_mono),
        size=args.size,
        content_ratio=args.content_ratio,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
