"""Generate synthetic video fixtures. Requires Pillow and FFmpeg; neither is an app dependency."""
import argparse
import io
from pathlib import Path
import struct
import subprocess

from PIL import Image

parser = argparse.ArgumentParser()
parser.add_argument("--ffmpeg", default="ffmpeg")
args = parser.parse_args()
target = Path(__file__).resolve().parent.parent / "assets"
target.mkdir(parents=True, exist_ok=True)


def chunk(tag, data):
    return tag + struct.pack("<I", len(data)) + data + (b"\x00" if len(data) % 2 else b"")


def listing(tag, data):
    return chunk(b"LIST", tag + data)


frames = []
for n in range(40):
    encoded = io.BytesIO()
    Image.new("RGB", (320, 180), (n * 5 % 256, 100, 220)).save(encoded, format="JPEG", quality=80)
    frames.append(encoded.getvalue())

maxframe = max(map(len, frames))
avih = struct.pack("<14I", 200000, maxframe * 5, 0, 0x10, len(frames), 0, 1, maxframe, 320, 180, 0, 0, 0, 0)
strh = struct.pack("<4s4sIHHIIIIIIIIhhhh", b"vids", b"MJPG", 0, 0, 0, 0, 1, 5, 0, len(frames), maxframe,
                   0xffffffff, 0, 0, 0, 320, 180)
strf = struct.pack("<IiiHH4sIiiII", 40, 320, 180, 1, 24, b"MJPG", maxframe, 0, 0, 0, 0)
headers = listing(b"hdrl", chunk(b"avih", avih) + listing(b"strl", chunk(b"strh", strh) + chunk(b"strf", strf)))
movie = bytearray()
index = bytearray()
for frame in frames:
    index += struct.pack("<4sIII", b"00dc", 0x10, 4 + len(movie), len(frame))
    movie += chunk(b"00dc", frame)
body = b"AVI " + headers + listing(b"movi", movie) + chunk(b"idx1", index)
avi = target / "preview.avi"
avi.write_bytes(chunk(b"RIFF", body))
cover = target / "fixture-cover.png"
Image.new("RGB", (32, 32), (240, 10, 30)).save(cover)
try:
    subprocess.run([args.ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-i", str(avi), "-c", "copy",
                    "-attach", str(cover), "-metadata:s:t", "mimetype=image/png", "-metadata:s:t", "filename=cover.png",
                    str(target / "cover-art.mkv")], check=True)
    subprocess.run([args.ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-i", str(avi), "-i", str(cover),
                    "-map", "0:v", "-map", "1:v", "-c:v:0", "libx264", "-pix_fmt:v:0", "yuv420p",
                    "-c:v:1", "png", "-disposition:v:1", "attached_pic", str(target / "cover-art.mp4")], check=True)
finally:
    cover.unlink(missing_ok=True)
