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


def _legend(width, lines):
    """顶部独立图例条，宽度随整张拼图，不受窄面板限制。"""
    h = 20 * len(lines) + 8
    bar = np.full((h, width, 3), 255, np.uint8)
    for i, t in enumerate(lines):
        cv2.putText(bar, t, (6, 20 * (i + 1)), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 0, 0), 1, cv2.LINE_AA)
    return bar


def _tag(img, text):
    """面板左上角只写短标签（1..4），详细说明放在图例里。"""
    img = img.copy()
    cv2.rectangle(img, (0, 0), (22, 20), (255, 255, 255), -1)
    cv2.putText(img, text, (5, 15), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 0, 0), 1, cv2.LINE_AA)
    return img


def sheet(ref_rgb, cand_rgba, original_rgba=None, uppers=None, height=520, pad=24):
    """所有输入为画布大小。返回拼图 RGB：顶部图例 + 四个面板。"""
    H, W = ref_rgb.shape[:2]
    mask = alpha01(cand_rgba) > 0.05
    if original_rgba is not None:
        mask |= alpha01(original_rgba) > 0.05
    bb = bbox_of(mask.astype(np.float32), 0.5) or (0, 0, W, H)
    x0, y0, x1, y1 = max(0, bb[0] - pad), max(0, bb[1] - pad), min(W, bb[2] + pad), min(H, bb[3] + pad)

    def crop(a):
        return a[y0:y1, x0:x1]

    ref_c = crop(ref_rgb)
    cand_c = crop(cand_rgba)
    ctx = ref_c.copy()
    for u in uppers or []:
        m = (crop(u) > 127).astype(np.uint8)
        cnts, _ = cv2.findContours(m, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        cv2.drawContours(ctx, cnts, -1, (255, 140, 0), 1)
    region = crop(alpha01(original_rgba)) if original_rgba is not None else None
    panels = [ctx, over(cand_c), mono_overlay(ref_c, cand_c), error_heat(ref_c, cand_c, region)]
    k = height / panels[0].shape[0]
    panels = [_tag(cv2.resize(p, (max(60, int(p.shape[1] * k)), height), interpolation=cv2.INTER_AREA), str(i + 1))
              for i, p in enumerate(panels)]
    gap = np.full((height, 6, 3), 255, np.uint8)
    row = panels[0]
    for p in panels[1:]:
        row = np.concatenate([row, gap, p], axis=1)
    legend = _legend(max(row.shape[1], 620), [
        "1 reference (orange outline = upper layers)   2 candidate placed",
        "3 mono overlay (cyan = reference only, red = candidate only)   4 dE heat on original area",
    ])
    width = max(row.shape[1], legend.shape[1])
    pad_row = np.full((height, width - row.shape[1], 3), 255, np.uint8)
    pad_leg = np.full((legend.shape[0], width - legend.shape[1], 3), 255, np.uint8)
    return np.concatenate([np.concatenate([legend, pad_leg], axis=1), np.concatenate([row, pad_row], axis=1)], axis=0)
