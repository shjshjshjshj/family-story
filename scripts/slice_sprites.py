"""Cuts a magenta-background sticker sheet into transparent sprites.
usage: slice_sprites.py <sheet.png> <cols> <rows> <name,name,...> <outdir> [outdir...]"""
import sys, os
from PIL import Image
import numpy as np
def run(sheet, cols, rows, names, outdirs, size=360):
    im = Image.open(sheet).convert('RGB'); a = np.asarray(im).astype(np.int16)
    r, g, b = a[..., 0], a[..., 1], a[..., 2]
    mag = np.clip((np.minimum(r, b) - g) / 110.0, 0, 1)      # magenta distance -> soft alpha
    alpha = ((1 - mag) * 255).astype(np.uint8)
    alpha[mag > 0.82] = 0
    rgb = a.copy()
    edge = (mag > 0.05)
    rgb[edge, 1] = np.maximum(rgb[edge, 1], np.minimum(rgb[edge, 0], rgb[edge, 2]))  # despill the pink fringe
    out = np.dstack([rgb.astype(np.uint8), alpha])
    H, W = alpha.shape
    for i, name in enumerate(names):
        cx, cy = i % cols, i // cols
        cell = out[cy * H // rows:(cy + 1) * H // rows, cx * W // cols:(cx + 1) * W // cols]
        cell = cell.copy(); cell[:, :16, 3] = 0; cell[:, -16:, 3] = 0   # drop slivers from neighbouring cells
        ys, xs = np.where(cell[..., 3] > 40)
        crop = Image.fromarray(cell[ys.min():ys.max() + 1, xs.min():xs.max() + 1], 'RGBA')
        canvas = Image.new('RGBA', (size, size), (0, 0, 0, 0))
        crop.thumbnail((size - 16, size - 16), Image.LANCZOS)
        canvas.paste(crop, ((size - crop.width) // 2, size - 8 - crop.height))
        for d in outdirs:
            os.makedirs(d, exist_ok=True); canvas.save(os.path.join(d, name + '.png'), optimize=True)
    print('sliced', sheet, names)
if __name__ == '__main__': run(sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4].split(','), sys.argv[5:])
