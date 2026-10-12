"""从参考图抠出“原画部件”：参考图前景 × 包含多边形 − 排除多边形。上层图层（遮挡物）同样用它抠出。"""
from __future__ import annotations

import cv2
import numpy as np

from . import matte as Mt
from .common import load_rgba, over, parse_polygon, polygon_mask, save_rgb


def cutout(ref_rgba, include=None, exclude=None, bg=(255, 255, 255), use_matte=True):
    """include / exclude 为多边形 'x,y;x,y;...' 字符串列表（画布坐标）。返回 RGBA 画布大小。"""
    rgb = over(ref_rgba)
    H, W = rgb.shape[:2]
    if use_matte:
        fg, _ = Mt.matte(rgb, bg=bg)
    else:
        fg = np.dstack([rgb, np.full((H, W), 255, np.uint8)])
    a = fg[..., 3].astype(np.float32) / 255.0
    if include:
        inc = np.zeros((H, W), np.float32)
        for p in include:
            inc = np.maximum(inc, polygon_mask((W, H), parse_polygon(p)))
        a = a * inc
    for p in exclude or []:
        a = a * (1 - polygon_mask((W, H), parse_polygon(p)))
    out = fg.copy()
    out[..., 3] = np.clip(a * 255 + 0.5, 0, 255).astype(np.uint8)
    out[..., :3] = np.where(out[..., 3:4] > 0, out[..., :3], 0)
    return out


def preview(ref_rgba, layers, path):
    """把若干 RGBA 层以不同颜色描边叠在参考图上，用来检查多边形是否画对。"""
    img = over(ref_rgba).copy()
    colors = [(255, 0, 0), (0, 160, 255), (0, 200, 0), (255, 140, 0), (160, 0, 255)]
    for i, L in enumerate(layers):
        m = (L[..., 3] > 127).astype(np.uint8)
        tint = np.zeros_like(img)
        tint[m > 0] = colors[i % len(colors)]
        img = np.where(m[..., None] > 0, (img * 0.6 + tint * 0.4).astype(np.uint8), img)
        cnts, _ = cv2.findContours(m, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        cv2.drawContours(img, cnts, -1, colors[i % len(colors)], 2)
    save_rgb(img, path)
