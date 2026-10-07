#!/usr/bin/env python3
"""Packs PNG files into a Windows .ico (PNG-compressed entries, supported since Vista).

Usage: make-ico.py out.ico 16.png 32.png 48.png 256.png
Each PNG must be square and at most 256 px; sizes are read from the PNG header.
"""
import struct
import sys


def png_size(data: bytes) -> int:
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a PNG")
    width, height = struct.unpack(">II", data[16:24])
    if width != height or width > 256:
        raise ValueError(f"icon images must be square and <= 256 px, got {width}x{height}")
    return width


def main() -> None:
    out, *sources = sys.argv[1:]
    images = [open(path, "rb").read() for path in sources]
    sizes = [png_size(image) for image in images]
    header = struct.pack("<HHH", 0, 1, len(images))
    offset = 6 + 16 * len(images)
    entries = b""
    for image, size in zip(images, sizes):
        dim = 0 if size == 256 else size  # 0 means 256 in the directory entry
        entries += struct.pack("<BBBBHHII", dim, dim, 0, 0, 1, 32, len(image), offset)
        offset += len(image)
    with open(out, "wb") as f:
        f.write(header + entries + b"".join(images))


if __name__ == "__main__":
    main()
