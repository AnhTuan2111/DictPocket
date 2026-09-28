"""Sinh icon .ico cho hai ban phat hanh.

    python scripts/tao-icon.py

Chi can chay lai khi muon doi thiet ke icon. Ket qua da commit san o
app-desktop/src/main/resources/icon/, nen dung app binh thuong thi khong can Python.

Thiet ke: chu "A" va "V" (Anh - Viet) tren nen bo goc mau nhan cua app.
    - Ban thuong : xanh  #3b6ea5  (dung mau nhan trong css/dict.css)
    - Ban AI     : xanh la #89b482 (mau "dat" trong css/dict.css)

Vi sao nen SANG chu khong phai nen toi: thanh taskbar Windows mac dinh la mau toi,
icon nen toi thi bien mat vao do. Da thu ca hai.

Vi sao ve rieng cho co nho: mui ve hai dau giua hai chu con 2 pixel o co 16, thanh mot
cuc mo. Co <= 24 thi bo mui ve, chi con hai chu.
"""

import io
import struct
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = ROOT / "app-desktop" / "src" / "main" / "resources" / "icon"

# Windows nhan .ico nhieu co trong mot file; Explorer, taskbar va hop thoai
# "Programs and Features" moi cho lay mot co khac nhau.
SIZES = [16, 20, 24, 32, 48, 64, 128, 256]

# Co xuat them ra PNG cho cua so JavaFX (xem main()).
PNG_SIZES = {16, 32, 48, 256}

EDITIONS = {
    "app": ("#4a80bd", "#2d5b8b"),      # xanh, ban thuong
    "app-ai": ("#9ac492", "#5f8f58"),   # xanh la, ban AI
}

FONT_CANDIDATES = [
    "C:/Windows/Fonts/segoeuib.ttf",
    "C:/Windows/Fonts/arialbd.ttf",
    "C:/Windows/Fonts/calibrib.ttf",
]


def font(size: int) -> ImageFont.FreeTypeFont:
    for path in FONT_CANDIDATES:
        if Path(path).is_file():
            return ImageFont.truetype(path, size)
    raise SystemExit("khong tim thay font bold nao trong " + ", ".join(FONT_CANDIDATES))


def centered(draw: ImageDraw.ImageDraw, xy, text: str, f, fill) -> None:
    """Dat tam chu vao dung diem xy. textbbox cho hop that, khong phai chieu cao dong."""
    left, top, right, bottom = draw.textbbox((0, 0), text, font=f)
    draw.text((xy[0] - (left + right) / 2, xy[1] - (top + bottom) / 2), text, font=f, fill=fill)


def gradient(n: int, light: str, dark: str) -> Image.Image:
    """Chuyen mau doc, nhat tren dam duoi. Nhe thoi - dam qua thi o co 16 thanh loang lo."""
    top = Image.new("RGB", (1, 1), light).getpixel((0, 0))
    bottom = Image.new("RGB", (1, 1), dark).getpixel((0, 0))
    band = Image.new("RGB", (1, n))
    for y in range(n):
        t = y / max(1, n - 1)
        band.putpixel((0, y), tuple(int(a + (b - a) * t) for a, b in zip(top, bottom)))
    return band.resize((n, n)).convert("RGBA")


def draw_icon(px: int, light: str, dark: str) -> Image.Image:
    """Ve o co lon gap 4 roi thu nho lai - cach re nhat de co bien mem."""
    scale = 4
    n = px * scale
    img = Image.new("RGBA", (n, n), (0, 0, 0, 0))

    # Nen bo goc, cat tu anh chuyen mau bang mot mat na. Ban kinh 22% canh: tron hon nua
    # thi o co 16 nhin ra hinh tron, vuong hon thi lac long giua icon Windows 11.
    mask = Image.new("L", (n, n), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, n - 1, n - 1], radius=int(n * 0.22), fill=255)
    img.paste(gradient(n, light, dark), (0, 0), mask)

    d = ImageDraw.Draw(img)
    white = "#f4f7fb"

    # Gach cheo ngan doi o: dau hieu "hai thu tieng" doc duoc ca o co 16, trong khi mui ten
    # hai dau thi den co 32 da nat thanh mot cuc. Da ve thu mui ten va bo.
    if px >= 48:
        w = max(2, int(n * 0.028))
        d.line([n * 0.74, n * 0.26, n * 0.26, n * 0.74], fill=white, width=w)

    # Chu phai du nho de khong cham vao gach cheo. Co nho thi bo gach cheo di roi cho chu
    # to them - nhet ca ba thu vao 16 diem anh thi chi ra mot cuc mo.
    ratio, ax, ay, vx, vy = (0.44, 0.30, 0.31, 0.70, 0.69) if px >= 48 \
        else (0.54, 0.31, 0.32, 0.69, 0.68)
    f = font(int(n * ratio))
    centered(d, (n * ax, n * ay), "A", f, white)
    centered(d, (n * vx, n * vy), "V", f, white)

    return img.resize((px, px), Image.LANCZOS)


def write_ico(path: Path, frames: list[Image.Image]) -> None:
    """Tu ghi file .ico.

    Khong dung Image.save(..., sizes=...) cua Pillow vi no thu nho TU MOT anh goc cho moi
    co - tuc la ban ve rieng cho co 16 bi bo di. Dinh dang .ico thi don gian: mot bang muc
    luc roi den du lieu tung anh, anh PNG nhet nguyen vao duoc (Windows Vista tro len).
    """
    blobs = []
    for frame in frames:
        buf = io.BytesIO()
        frame.save(buf, format="PNG")
        blobs.append(buf.getvalue())

    offset = 6 + 16 * len(frames)                      # ICONDIR + cac ICONDIRENTRY
    header = struct.pack("<HHH", 0, 1, len(frames))    # reserved, type=icon, so anh
    entries, data = b"", b""
    for frame, blob in zip(frames, blobs):
        w, h = frame.size
        entries += struct.pack(
            "<BBBBHHII",
            0 if w == 256 else w,   # co 256 ghi la 0, dinh dang chi co 1 byte
            0 if h == 256 else h,
            0, 0,                   # so mau trong bang mau (0 = khong dung bang), reserved
            1, 32,                  # so mat phang, so bit mot diem
            len(blob), offset,
        )
        data += blob
        offset += len(blob)
    path.write_bytes(header + entries + data)


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for name, (light, dark) in EDITIONS.items():
        frames = [draw_icon(px, light, dark) for px in SIZES]
        ico = OUT_DIR / f"{name}.ico"
        write_ico(ico, frames)
        # PNG rieng cho cua so JavaFX: Stage.getIcons() nhan nhieu co roi tu chon co hop
        # nhat cho thanh tieu de va cho Alt+Tab. Khong doc duoc .ico nen phai co ban PNG.
        for px, frame in zip(SIZES, frames):
            if px in PNG_SIZES:
                frame.save(OUT_DIR / f"{name}-{px}.png", format="PNG")
        print(f"{ico.relative_to(ROOT)}  {ico.stat().st_size / 1024:.1f} KB  {len(SIZES)} co")


if __name__ == "__main__":
    main()
