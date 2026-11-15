"""Cuts a flat-background sticker sheet into separate transparent stickers.

usage: python cut_stickers.py <sheet.png> <out_dir> <name1,name2,...> [size] [green|magenta] [rows]
Stickers are found as connected shapes (so they may overlap each other's bounding boxes),
ordered row by row and left to right, and saved as square PNGs. Needs only Pillow and numpy.
"""
import sys, os
from collections import deque
import numpy as np
from PIL import Image, ImageFilter

sheet, out_dir, names = sys.argv[1], sys.argv[2], sys.argv[3].split(",")
size = int(sys.argv[4]) if len(sys.argv) > 4 else 360
key = sys.argv[5] if len(sys.argv) > 5 else "green"      # the flat background colour of the sheet
rows = int(sys.argv[6]) if len(sys.argv) > 6 else 2
os.makedirs(out_dir, exist_ok=True)

rgb = np.asarray(Image.open(sheet).convert("RGB")).astype(np.int32)
r, g, b = rgb[..., 0], rgb[..., 1], rgb[..., 2]
if key == "magenta": solid = ~((r > 150) & (b > 150) & (g < np.minimum(r, b) * 0.6))
else: solid = ~((g > 120) & (g > r * 1.35) & (g > b * 1.35))

# Label connected shapes on a quarter-size copy: fast enough in plain Python and gaps of a few pixels still separate.
STEP = 4
small = solid[::STEP, ::STEP]
labels = np.zeros(small.shape, dtype=np.int32)
shapes = []
for y, x in zip(*np.nonzero(small)):
    if labels[y, x]: continue
    index = len(shapes) + 1
    labels[y, x] = index
    queue, count, y0, y1, x0, x1 = deque([(y, x)]), 0, y, y, x, x
    while queue:
        cy, cx = queue.popleft(); count += 1
        y0, y1, x0, x1 = min(y0, cy), max(y1, cy), min(x0, cx), max(x1, cx)
        for ny, nx in ((cy - 1, cx), (cy + 1, cx), (cy, cx - 1), (cy, cx + 1)):
            if 0 <= ny < small.shape[0] and 0 <= nx < small.shape[1] and small[ny, nx] and not labels[ny, nx]:
                labels[ny, nx] = index; queue.append((ny, nx))
    shapes.append((index, count, y0, y1, x0, x1))
shapes = [s for s in shapes if s[1] > small.size * 0.01]      # drops specks and the corner mark
if len(shapes) != len(names):
    sys.exit("found %d stickers but %d names were given" % (len(shapes), len(names)))
band = small.shape[0] / rows
shapes.sort(key=lambda s: (int(((s[2] + s[3]) / 2) // band), s[4]))

for name, (index, _, y0, y1, x0, x1) in zip(names, shapes):
    pad = 2
    y0, y1, x0, x1 = max(0, y0 - pad), min(small.shape[0], y1 + 1 + pad), max(0, x0 - pad), min(small.shape[1], x1 + 1 + pad)
    own = Image.fromarray(((labels[y0:y1, x0:x1] == index) * 255).astype(np.uint8))
    own = own.resize((own.width * STEP, own.height * STEP), Image.NEAREST).filter(ImageFilter.MaxFilter(9))
    Y0, X0 = y0 * STEP, x0 * STEP
    region = solid[Y0:Y0 + own.height, X0:X0 + own.width]
    own = own.crop((0, 0, region.shape[1], region.shape[0]))
    alpha = Image.fromarray(((region & (np.asarray(own) > 0)) * 255).astype(np.uint8))
    alpha = alpha.filter(ImageFilter.MinFilter(3)).filter(ImageFilter.GaussianBlur(0.8))   # shave the coloured fringe
    cut = Image.fromarray(rgb[Y0:Y0 + region.shape[0], X0:X0 + region.shape[1]].astype(np.uint8)).convert("RGBA")
    cut.putalpha(alpha)
    scale = (size - 8) / max(cut.size)
    cut = cut.resize((max(1, round(cut.width * scale)), max(1, round(cut.height * scale))), Image.LANCZOS)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.paste(cut, ((size - cut.width) // 2, (size - cut.height) // 2), cut)
    canvas.save(os.path.join(out_dir, name + ".png"), optimize=True)
    print(name, cut.size)
