"""Generate Android launcher mipmaps from assets/vk_music_icon.*"""
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "assets"
RES = ROOT / "app" / "src" / "main" / "res"

candidates = [
    ASSETS / "vk_music_icon.png",
    ASSETS / "vk_music_icon.jpg",
]
src = next((p for p in candidates if p.exists()), None)
if src is None:
    raise SystemExit(f"Icon not found in {ASSETS}")

img = Image.open(src).convert("RGBA")
w, h = img.size
side = min(w, h)
left = (w - side) // 2
top = (h - side) // 2
img = img.crop((left, top, left + side, top + side))

densities = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

fg_path = RES / "drawable" / "ic_launcher_foreground.png"
fg_path.parent.mkdir(parents=True, exist_ok=True)
img.resize((432, 432), Image.Resampling.LANCZOS).save(fg_path, format="PNG", optimize=True)
print("wrote", fg_path)

master = ASSETS / "vk_music_icon.png"
img.resize((512, 512), Image.Resampling.LANCZOS).save(master, format="PNG", optimize=True)
print("wrote", master)

old_vector = RES / "drawable" / "ic_launcher_foreground.xml"
if old_vector.exists():
    old_vector.unlink()
    print("removed", old_vector)

for folder, size in densities.items():
    d = RES / folder
    d.mkdir(parents=True, exist_ok=True)
    out = img.resize((size, size), Image.Resampling.LANCZOS)
    out.save(d / "ic_launcher.png", format="PNG", optimize=True)
    out.save(d / "ic_launcher_round.png", format="PNG", optimize=True)
    print("wrote", d / "ic_launcher.png", size)

print("done")
