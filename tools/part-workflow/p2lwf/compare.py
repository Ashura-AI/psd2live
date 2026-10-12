"""对比图：给人和 AI 看的并排图。参考 | 候选 | 单色叠加 | 误差热力图。"""
from __future__ import annotations

import cv2
import numpy as np

from .common import alpha01, bbox_of, over, to_lab


def _luma(rgb):
    return (0.299 * rgb[..., 0] + 0.587 * rgb[..., 1] + 0.114 * rgb[..., 2]).astype(np.float32)


def mono_overlay(ref_rgb, cand_rgba, opacity=1.0):
    """参考取红通道、候选取绿蓝通道：重合处为灰，仅参考更暗处偏青，仅候选更暗处偏红。"""
    c = over(cand_rgba)
    out = np.stack([_luma(ref_rgb), _luma(c), _luma(c)], axis=2)
    if opacity < 1.0:
        out = 255 - (255 - out) * opacity
    return np.clip(out, 0, 255).astype(np.uint8)


def error_heat(ref_rgb, cand_rgba, region=None, vmax=40.0):
    pl = cand_rgba
    a = alpha01(pl) > 0.5
    reg = a if region is None else (a & (region > 0.5))
    dE = np.linalg.norm(to_lab(pl[..., :3]) - to_lab(ref_rgb), axis=2)
    v = np.clip(dE / vmax, 0, 1)
    heat = cv2.applyColorMap((v * 255).astype(np.uint8), cv2.COLORMAP_TURBO)[..., ::-1]
    out = np.full(ref_rgb.shape, 255, np.uint8)
    out[reg] = heat[reg]
    return out


def _label(img, text):
    img = img.copy()
    cv2.rectangle(img, (0, 0), (min(img.shape[1], 9 * len(text) + 12), 22), (255, 255, 255), -1)
    cv2.putText(img, text, (5, 16), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 0, 0), 1, cv2.LINE_AA)
    return img


def sheet(ref_rgb, cand_rgba, original_rgba=None, uppers=None, height=520, pad=24):
    """所有输入为画布大小。返回拼图 RGB。"""
    H, W = ref_rgb.shape[:2]
    mask = alpha01(cand_rgba) > 0.05
    if original_rgba is not None:
        mask |= alpha01(original_rgba) > 0.05
    bb = bbox_of(mask.astype(np.float32), 0.5) or (0, 0, W, H)
    x0, y0, x1, y1 = max(0, bb[0] - pad), max(0, bb[1] - pad), min(W, bb[2] + pad), min(H, bb[3] + pad)
    crop = lambda a: a[y0:y1, x0:x1]

    ref_c = crop(ref_rgb)
    cand_c = crop(cand_rgba)
    ctx = ref_c.copy()
    for u in uppers or []:
        # 在参考上描出上层的轮廓，便于看遮挡关系
        m = (crop(u) > 127).astype(np.uint8)
        cnts, _ = cv2.findContours(m, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        cv2.drawContours(ctx, cnts, -1, (255, 140, 0), 1)
    region = crop(alpha01(original_rgba)) if original_rgba is not None else None
    panels = [
        _label(ctx, "reference (orange = upper layers)"),
        _label(over(cand_c), "candidate placed"),
        _label(mono_overlay(ref_c, cand_c), "mono overlay (cyan=ref only, red=cand only)"),
        _label(error_heat(ref_c, cand_c, region), "dE heat on original area"),
    ]
    k = height / panels[0].shape[0]
    panels = [cv2.resize(p, (max(1, int(p.shape[1] * k)), height), interpolation=cv2.INTER_AREA) for p in panels]
    gap = np.full((height, 6, 3), 255, np.uint8)
    row = panels[0]
    for p in panels[1:]:
        row = np.concatenate([row, gap, p], axis=1)
    return row
