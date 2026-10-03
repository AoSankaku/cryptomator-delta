"""Synthetic viewer fixtures; Python standard library plus FFmpeg, no app dependencies."""
import argparse
from pathlib import Path
import struct
import subprocess
import zlib

parser = argparse.ArgumentParser()
parser.add_argument("--ffmpeg", default="ffmpeg")
args = parser.parse_args()
target = Path(__file__).resolve().parent.parent / "assets"
target.mkdir(parents=True, exist_ok=True)


def png_chunk(tag, data):
    return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data))


width, height = 96, 64
rows = b"".join(b"\0" + bytes((20, 170, 40)) * width for _ in range(height))
png = (b"\x89PNG\r\n\x1a\n" + png_chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
       + png_chunk(b"IDAT", zlib.compress(rows)) + png_chunk(b"IEND", b""))
source = target / "image.png"
source.write_bytes(png)
for extension, options in [
    ("jpg", []),
    ("webp", ["-c:v", "libwebp", "-lossless", "1"]),
    ("avif", ["-c:v", "libaom-av1", "-cpu-used", "8", "-crf", "24", "-still-picture", "1", "-pix_fmt", "yuv420p"]),
]:
    subprocess.run([args.ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-i", str(source),
                    "-frames:v", "1", *options, str(target / ("image." + extension))], check=True)

objects = [
    b"<< /Type /Catalog /Pages 2 0 R >>",
    b"<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>",
    b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 144 192] /Contents 5 0 R >>",
    b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 144 192] /Contents 6 0 R >>",
]
for color in (b"0.9 0.1 0.1", b"0.1 0.1 0.9"):
    content = color + b" rg\n0 0 144 192 re f\n"
    objects.append(b"<< /Length " + str(len(content)).encode() + b" >>\nstream\n" + content + b"endstream")
document = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
offsets = [0]
for number, value in enumerate(objects, 1):
    offsets.append(len(document))
    document += f"{number} 0 obj\n".encode() + value + b"\nendobj\n"
xref = len(document)
document += f"xref\n0 {len(offsets)}\n0000000000 65535 f \n".encode()
for offset in offsets[1:]:
    document += f"{offset:010} 00000 n \n".encode()
document += f"trailer\n<< /Size {len(offsets)} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()
(target / "pages.pdf").write_bytes(document)
print("Generated synthetic PNG/JPEG/WebP/AVIF and two-page PDF fixtures.")
