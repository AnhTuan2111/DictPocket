"""Sinh toàn bộ icon của DictPocket từ các lưới pixel 16x16.

    python scripts/generate-icons.py

Chỉ cần chạy lại khi đổi thiết kế. Kết quả đã nằm sẵn trong app-desktop/src/main/resources
nên build thường không cần Python. Script chỉ dùng thư viện chuẩn.
"""

import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app-desktop" / "src" / "main" / "resources"

# Bảng màu bó hẹp trong tông Win9x; '.' là trong suốt.
PALETTE = {
    "k": (0, 0, 0), "w": (255, 255, 255), "g": (192, 192, 192), "d": (128, 128, 128),
    "b": (0, 0, 128), "B": (16, 132, 208), "c": (128, 224, 255),
    "y": (255, 255, 85), "Y": (192, 160, 0), "n": (32, 160, 32), "N": (0, 96, 0),
    "m": (138, 90, 42), "o": (255, 154, 32),
}

ICONS = {
    # Cuốn sách xanh, ruy-băng xanh lá và chữ D: icon của app.
    "book": [
        "", "..kkkkkkkkkkk", ".kcBBBBBBBBbk", ".kcBnnBBBBBbk", ".kcBnnBBBBBbk", ".kcBnnBBBBBbk", ".kcBnBnBBBBbk",
        ".kcBBwwwwBBbk", ".kcBBwBBBwBbk", ".kcBBwBBBwBbk", ".kcBBwBBBwBbk", ".kcBBwBBBwBbk", ".kcBBwwwwBBbk",
        ".kcBBBBBBBBbk", ".kbbbbbbbbbbk", "..kkkkkkkkkkk",
    ],
    "search": [
        "", "....kkkk", "...kcccck", "..kcwwccck", "..kcwcccck", "..kcccccck", "..kcccccck", "...kcccck",
        "....kkkkmm", ".........mm", "..........mm", "...........mm", "............mm", "............kk",
    ],
    "note": [
        "", "..kkkkkkkkkk", "..kwwwwwwwwkd", "..kwbbbbbbwkd", "..kwwwwwwwwkd", "..kwbbbbbbwkd", "..kwwwwwwwwkd",
        "..kwbbbbwwwkd", "..kwwwwwwwwkd", "..kwbbbbbbwkd", "..kwwwwwwwwkd", "..kwbbbwwwwkd", "..kwwwwwwwwkd",
        "..kkkkkkkkkkd", "...dddddddddd",
    ],
    "chat": [
        "", ".kkkkkkkk", "kcBBBBBBbk", "kcBBBBBBbk", "kcBBBBBBbk", "kcBBBBBBbk", ".kkkkkkkk", "..kk...kkkkkkkk",
        "..k...kyyyyyyyyk", "......kyyyyyyyyk", "......kyyyyyyyyk", "......kyyyyyyyyk", ".......kkkkkkkk",
        "............kk", "............k",
    ],
    "folder": [
        "", "", "", ".kkkkk", ".kyyyykkkkkkkkk", ".kwwwwwwwwwwwwk", ".kyyyyyyyyyyyyk", ".kyyyyyyyyyyyyk",
        ".kyyyyyyyyyyyyk", ".kyyyyyyyyyyyyk", ".kyyyyyyyyyyyyk", ".kyyyyyyyyyyyyk", ".kYYYYYYYYYYYYk",
        ".kkkkkkkkkkkkkk", "..dddddddddddd",
    ],
    "chip": [
        "", "...d.d.d.d.d", "...d.d.d.d.d", "..kkkkkkkkkkk", "..kBBBBBBBBBk", "..kBBBBBBBBBk", "..kBBwBBwwwBk",
        "..kBwBwBBwBBk", "..kBwwwBBwBBk", "..kBwBwBBwBBk", "..kBwBwBwwwBk", "..kBBBBBBBBBk", "..kkkkkkkkkkk",
        "...d.d.d.d.d", "...d.d.d.d.d",
    ],
    "info": [
        "", "....kkkkkkkk", "..kkBBBBBBBBkk", ".kBBBBBBBBBBBBk", ".kBBBBBwwBBBBBk", ".kBBBBBBBBBBBBk",
        ".kBBBBBwwBBBBBk", ".kBBBBBwwBBBBBk", ".kBBBBBwwBBBBBk", ".kBBBBBwwBBBBBk", ".kBBBBBwwBBBBBk",
        ".kBBBBwwwwBBBBk", ".kBBBBBBBBBBBBk", "..kkBBBBBBBBkk", "....kkkkkkkk",
    ],
    "history": [
        "", "....kkkkkkkk", "..kkwwwwwwwwkk", ".kwwwwwwwwwwwwk", ".kwwwwwkwwwwwwk", ".kwwwwwkwwwwwwk",
        ".kwwwwwkwwwwwwk", ".kwwwwwkwwwwwwk", ".kwwwwwkkkkkwwk", ".kwwwwwwwwwwwwk", ".kwwwwwwwwwwwwk",
        ".kwwwwwwwwwwwwk", ".kwwwwwwwwwwwwk", "..kkwwwwwwwwkk", "....kkkkkkkk",
    ],
    "warn": [
        "", "......kyyk", "......kyyk", ".....kyyyyk", ".....kyyyyk", "....kyykkyyk", "....kyykkyyk",
        "...kyyykkyyyk", "...kyyykkyyyk", "..kyyyykkyyyyk", "..kyyyyyyyyyyk", ".kyyyyykkyyyyyk",
        ".kyyyyykkyyyyyk", "kyyyyyyyyyyyyyyk", "kyyyyyyyyyyyyyyk", "kkkkkkkkkkkkkkkk",
    ],
}

# Bản AI: cùng cuốn sách, thêm tia sáng vàng ở góc phải trên để phân biệt trên taskbar.
SPARKLE = ["..o..", ".oyo.", "oyyyo", ".oyo.", "..o.."]


def overlay(base, top, x0, y0):
    rows = [list(r.ljust(16, ".")) for r in base] + [["."] * 16 for _ in range(16 - len(base))]
    for dy, line in enumerate(top):
        for dx, ch in enumerate(line):
            if ch != ".":
                rows[y0 + dy][x0 + dx] = ch
    return ["".join(r) for r in rows]


ICONS["book-ai"] = overlay(ICONS["book"], SPARKLE, 11, 0)


def to_pixels(rows):
    """Lưới ký tự -> 16x16 điểm RGBA."""
    pixels = []
    for y in range(16):
        row = rows[y] if y < len(rows) else ""
        for x in range(16):
            ch = row[x] if x < len(row) else "."
            pixels.append(PALETTE[ch] + (255,) if ch in PALETTE else (0, 0, 0, 0))
    return pixels


def scale(pixels, factor):
    """Phóng nguyên số lần, không làm mịn để giữ nét pixel."""
    size = 16 * factor
    out = []
    for y in range(size):
        for x in range(size):
            out.append(pixels[(y // factor) * 16 + (x // factor)])
    return size, out


def png_bytes(size, pixels):
    raw = bytearray()
    for y in range(size):
        raw.append(0)
        for r, g, b, a in pixels[y * size:(y + 1) * size]:
            raw += bytes((r, g, b, a))

    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b"")


def ico_bytes(images):
    """ICO chứa nhiều ảnh PNG: Explorer, taskbar và hộp thoại gỡ cài đặt mỗi nơi lấy một cỡ."""
    head = struct.pack("<HHH", 0, 1, len(images))
    entries, blobs, offset = b"", b"", 6 + 16 * len(images)
    for size, data in images:
        dim = 0 if size >= 256 else size
        entries += struct.pack("<BBBBHHII", dim, dim, 0, 0, 1, 32, len(data), offset)
        blobs += data
        offset += len(data)
    return head + entries + blobs


def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    print("wrote", path.relative_to(ROOT))


def main():
    app_sizes = {16: 1, 32: 2, 48: 3, 256: 16}
    for name, grid in (("app", "book"), ("app-ai", "book-ai")):
        base = to_pixels(ICONS[grid])
        images = []
        for size, factor in app_sizes.items():
            real, px = scale(base, factor)
            data = png_bytes(real, px)
            write(RES / "icon" / f"{name}-{size}.png", data)
            images.append((real, data))
        write(RES / "icon" / f"{name}.ico", ico_bytes(images))

    # Icon nút bấm: giữ 16x16, app phóng lên khi hiển thị.
    for name in ("search", "note", "chat", "folder", "chip", "info", "warn", "history"):
        write(RES / "ui" / f"{name}.png", png_bytes(16, to_pixels(ICONS[name])))


if __name__ == "__main__":
    main()
