"""装配与检查：按层序合成、输出 PSD、覆盖缺口与逐层“隐藏后露底”检查。

spec（JSON）：{"canvas": [W, H], "layers": [{"name": "arm-l", "file": "arm_l.png"}, ...]}，从下到上；
文件为画布大小的 RGBA（ingest 产出的 placed.png），相对路径以 spec 所在目录为基准。
"""
from __future__ import annotations

import json
import os

import cv2
import numpy as np

from . import matte as Mt
from .common import alpha01, bbox_of, dump, load_rgba, over, save_rgb, save_rgba


def load_spec(path):
    with open(path, encoding="utf-8") as f:
        spec = json.load(f)
    base = os.path.dirname(os.path.abspath(path))
    W, H = spec["canvas"]
    layers = []
    for it in spec["layers"]:
        arr = load_rgba(os.path.join(base, it["file"]) if not os.path.isabs(it["file"]) else it["file"])
        if arr.shape[:2] != (H, W):
            raise ValueError(f"{it['name']}: 尺寸 {arr.shape[1]}x{arr.shape[0]} 与画布 {W}x{H} 不一致")
        layers.append((it["name"], arr))
    return (W, H), layers


def composite(layers, size_wh, hide=(), bg=(255, 255, 255)):
    W, H = size_wh
    acc = np.zeros((H, W, 3), np.float32)
    acc[:] = bg
    for name, L in layers:
        if name in hide:
            continue
        a = alpha01(L)[..., None]
        acc = acc * (1 - a) + L[..., :3].astype(np.float32) * a
    return np.clip(acc + 0.5, 0, 255).astype(np.uint8)


def write_psd(layers, size_wh, path):
    try:
        from psd_tools import PSDImage
        from psd_tools.api.layers import PixelLayer
        from PIL import Image
    except ImportError as e:
        raise SystemExit("写 PSD 需要 psd-tools：pip install psd-tools") from e
    W, H = size_wh
    psd = PSDImage.new("RGB", (W, H))
    for name, L in layers:  # 从下到上：先加入的在下面
        bb = bbox_of(alpha01(L), 0.0)
        if bb is None:
            continue
        x0, y0, x1, y1 = bb
        im = Image.fromarray(np.ascontiguousarray(L[y0:y1, x0:x1]), "RGBA")
        psd.append(PixelLayer.frompil(im, psd, name, top=y0, left=x0))
    psd.save(path)


def coverage(layers, ref_rgba, size_wh, out_dir, bg=(255, 255, 255)):
    """所有层 alpha 并集未覆盖的参考前景（缺口）。"""
    os.makedirs(out_dir, exist_ok=True)
    W, H = size_wh
    ref_rgb = over(ref_rgba)
    fg = Mt.matte(ref_rgb, bg=bg)[0][..., 3] > 127
    U = np.zeros((H, W), bool)
    for _, L in layers:
        U |= L[..., 3] > 127
    gap = fg & ~U
    gap = cv2.morphologyEx(gap.astype(np.uint8), cv2.MORPH_OPEN, np.ones((3, 3), np.uint8)).astype(bool)
    vis = ref_rgb.copy()
    vis[gap] = (255, 0, 0)
    save_rgb(vis, os.path.join(out_dir, "coverage_gap.png"))
    n, cc, stats, _ = cv2.connectedComponentsWithStats(gap.astype(np.uint8), connectivity=8)
    blobs = sorted(({"area": int(stats[i, cv2.CC_STAT_AREA]),
                     "bbox": [int(stats[i, 0]), int(stats[i, 1]), int(stats[i, 0] + stats[i, 2]), int(stats[i, 1] + stats[i, 3])]}
                    for i in range(1, n)), key=lambda b: -b["area"])
    return {"foreground_px": int(fg.sum()), "gap_px": int(gap.sum()), "gap_fraction": float(gap.sum() / max(fg.sum(), 1)),
            "largest_gaps": blobs[:10], "image": os.path.join(out_dir, "coverage_gap.png")}


def seam_exposure(layers, size_wh, out_dir, only=None):
    """逐层隐藏：该层可见区域里，其余各层并集没有覆盖的部分就是隐藏它后会露底的位置。"""
    os.makedirs(out_dir, exist_ok=True)
    W, H = size_wh
    res = {}
    for name, L in layers:
        if only and name not in only:
            continue
        others = np.zeros((H, W), bool)
        for n2, L2 in layers:
            if n2 != name:
                others |= L2[..., 3] > 127
        mine = L[..., 3] > 127
        exp = mine & ~others
        # 只报告该层之下没有别的东西、且位于其他层包围范围内的洞（轮廓外缘本来就该露）
        ob = bbox_of(others.astype(np.float32), 0.5)
        hole = np.zeros_like(exp)
        if ob:
            filled = cv2.morphologyEx(others.astype(np.uint8), cv2.MORPH_CLOSE, np.ones((15, 15), np.uint8)).astype(bool)
            hole = mine & ~others & filled
        hide_img = composite(layers, size_wh, hide=(name,))
        hide_img[hole] = (255, 0, 0)
        p = os.path.join(out_dir, f"exposure_{name}.png")
        save_rgb(hide_img, p)
        res[name] = {"unique_px": int(exp.sum()), "hole_px": int(hole.sum()), "image": p}
    return res
