"""Enlarges Live2D texture pages twofold with an anime upscaler, for rendering training pairs at twice the density.

Colour is bled into transparent texels first so the upscaler never sees the arbitrary colour behind alpha 0; alpha
is enlarged by the same model (as a grey image) so edges stay as crisp as the lines.

usage: python upscale_pages.py SRC_DIR DST_DIR [MODEL]   (every *.png under SRC_DIR, same relative paths in DST_DIR)
"""
import os
import sys

sys.path.insert(0, "/opt/comfyui")
os.chdir("/opt/comfyui")

import numpy as np
import torch
from PIL import Image
from scipy import ndimage

import folder_paths  # noqa: F401
from utils.extra_config import load_extra_path_config

load_extra_path_config("/opt/comfyui/extra_model_paths.yaml")
from comfy_extras.nodes_upscale_model import ImageUpscaleWithModel, UpscaleModelLoader


def bleed(rgba: np.ndarray) -> np.ndarray:
    """RGB of transparent texels set to the nearest opaque texel's, alpha kept."""
    alpha = rgba[..., 3] > 0
    if alpha.all() or not alpha.any():
        return rgba
    _, (iy, ix) = ndimage.distance_transform_edt(~alpha, return_indices=True)
    out = rgba.copy()
    out[..., :3] = rgba[iy, ix, :3]
    return out


def enlarge(model, rgb: np.ndarray) -> np.ndarray:
    tensor = torch.from_numpy(rgb.astype(np.float32) / 255.0)[None]
    with torch.inference_mode():
        out = ImageUpscaleWithModel.execute(upscale_model=model, image=tensor)
    out = (out.args[0] if hasattr(out, "args") else out[0])[0].cpu().numpy()
    big = Image.fromarray((out * 255 + 0.5).clip(0, 255).astype(np.uint8))
    return np.asarray(big.resize((rgb.shape[1] * 2, rgb.shape[0] * 2), Image.LANCZOS))


def main():
    src, dst = sys.argv[1], sys.argv[2]
    name = sys.argv[3] if len(sys.argv) > 3 else "RealESRGAN_x4plus_anime_6B.pth"
    loaded = UpscaleModelLoader.execute(model_name=name)
    model = loaded.args[0] if hasattr(loaded, "args") else loaded[0]
    for root, _, files in os.walk(src):
        for f in files:
            if not f.lower().endswith(".png"):
                continue
            path = os.path.join(root, f)
            target = os.path.join(dst, os.path.relpath(path, src))
            if os.path.exists(target):
                continue
            rgba = bleed(np.asarray(Image.open(path).convert("RGBA")))
            color = enlarge(model, rgba[..., :3])
            alpha = enlarge(model, np.repeat(rgba[..., 3:4], 3, axis=2))[..., :1]
            os.makedirs(os.path.dirname(target), exist_ok=True)
            Image.fromarray(np.concatenate([color, alpha], axis=2), "RGBA").save(target)
            print(target, flush=True)


if __name__ == "__main__":
    main()
