"""纯色背景（默认白色）抠图：泛洪判背景 + 已知背景色求 alpha + 前景色去污染。

原理：C = αF + (1−α)B，已知背景 B，从邻近的确定前景估计 F，则
α = ((C−B)·(F−B)) / |F−B|²。只有与图像边框连通的接近背景色的像素才算背景，
所以前景内部不与边框连通的白色（白发、白袖）不会被抠空。
局限：白色前景直接接触边框，或与背景之间没有任何非白线条隔开时无法区分，
此时应换用与部件颜色最远的背景色重新生成（见 suggest_bg）。
"""
from __future__ import annotations

import cv2
import numpy as np

from .common import alpha01, hex_to_rgb, over

BG_CANDIDATES = {
    "white": (255, 255, 255),
    "green": (0, 255, 0),
    "magenta": (255, 0, 255),
    "black": (0, 0, 0),
    "cyan": (0, 255, 255),
    "gray": (128, 128, 128),
}


def estimate_bg(rgb: np.ndarray) -> np.ndarray:
    border = np.concatenate([rgb[0], rgb[-1], rgb[:, 0], rgb[:, -1]]).astype(np.float32)
    return np.median(border, axis=0)


def suggest_bg(rgb: np.ndarray, alpha: np.ndarray | None = None, prefer: str = "white") -> dict:
    """为一个部件挑背景色。默认白；只有部件轮廓边缘本身有大量近白色像素（白发、白袖、白纱贴着背景）时，
    才改用与前景最远的候选色。被深色线条包住的内部白色不影响白底抠图。"""
    if alpha is None:
        alpha = np.ones(rgb.shape[:2], np.float32)
    m = (alpha > 0.5).astype(np.uint8)
    if m.sum() == 0:
        return {"name": prefer, "hex": "#FFFFFF", "reason": "无前景像素，使用默认"}
    edge = (m > 0) & ~cv2.erode(m, np.ones((7, 7), np.uint8)).astype(bool)  # 轮廓内侧约 3px 的带
    px_edge = rgb[edge].astype(np.float32)
    px_all = rgb[m > 0].astype(np.float32)
    if len(px_all) > 20000:
        px_all = px_all[np.random.default_rng(0).choice(len(px_all), 20000, replace=False)]
    edge_white = float(np.mean(np.linalg.norm(px_edge - 255, axis=1) < 40)) if len(px_edge) else 0.0
    dists = {n: float(np.percentile(np.linalg.norm(px_all - np.array(c, np.float32), axis=1), 1))
             for n, c in BG_CANDIDATES.items()}
    if edge_white < 0.08:
        n = prefer
        reason = f"轮廓边缘近白像素 {edge_white:.0%}，使用默认背景"
    else:
        n = max(dists, key=dists.get)
        reason = f"轮廓边缘近白像素 {edge_white:.0%}，白色前景会与白底相连，改用与前景最远的颜色"
    c = BG_CANDIDATES[n]
    return {"name": n, "hex": "#%02X%02X%02X" % c, "reason": reason, "edge_white_fraction": edge_white, "p1_distance": dists[n]}


def matte(rgb: np.ndarray, bg=None, tol: float = 14.0, band: int = 2, min_area: int = 40,
          shrink: float = 0.0, alpha_floor: float = 0.03):
    """rgb: HxWx3 uint8。返回 (rgba uint8, info)。"""
    H, W = rgb.shape[:2]
    f = rgb.astype(np.float32)
    B = estimate_bg(rgb) if bg is None else np.array(bg, np.float32)
    d = np.linalg.norm(f - B, axis=2)

    cand = (d < tol).astype(np.uint8)
    _, lab = cv2.connectedComponents(cand, connectivity=4)
    border = np.concatenate([lab[0], lab[-1], lab[:, 0], lab[:, -1]])
    keep = np.unique(border[border > 0])
    bgdef = np.isin(lab, keep) if len(keep) else np.zeros((H, W), bool)

    notbg = ~bgdef
    k = 2 * band + 1
    fgd = cv2.erode(notbg.astype(np.uint8), np.ones((k, k), np.uint8)).astype(bool)
    anchor = (notbg & (d > tol * 3)) | fgd  # 用来估计前景色的可靠像素
    wgt = anchor.astype(np.float32)

    sig = max(float(band), 1.5)
    num = cv2.GaussianBlur(f * wgt[..., None], (0, 0), sig)
    den = cv2.GaussianBlur(wgt, (0, 0), sig)
    for s2 in (sig * 2, sig * 4, sig * 8):  # 薄线条附近权重不够时放大半径
        weak = den < 1e-3
        if not weak.any():
            break
        num2 = cv2.GaussianBlur(f * wgt[..., None], (0, 0), s2)
        den2 = cv2.GaussianBlur(wgt, (0, 0), s2)
        num = np.where(weak[..., None], num2, num)
        den = np.where(weak, den2, den)
    ok = den > 1e-4
    F = np.where(ok[..., None], num / np.maximum(den[..., None], 1e-4), f)

    FB = F - B
    denom = np.sum(FB * FB, axis=2)
    a_formula = np.sum((f - B) * FB, axis=2) / np.maximum(denom, 1e-6)
    a_formula = np.where(denom > tol * tol, a_formula, 1.0)  # F≈B：无法区分，按前景处理
    unknown = notbg & ~fgd
    alpha = np.zeros((H, W), np.float32)
    alpha[fgd] = 1.0
    alpha[unknown] = np.clip(a_formula[unknown], 0, 1)

    if shrink > 0:
        alpha = np.clip((alpha - shrink) / (1 - shrink), 0, 1)
    alpha[alpha < alpha_floor] = 0

    out_rgb = np.where((unknown & (alpha < 0.995))[..., None], np.clip(F, 0, 255), f)
    out_rgb = np.where((alpha > 0)[..., None], out_rgb, 0)

    removed = 0
    if min_area > 0:
        n, cc, stats, _ = cv2.connectedComponentsWithStats((alpha > 0.1).astype(np.uint8), connectivity=8)
        small = [i for i in range(1, n) if stats[i, cv2.CC_STAT_AREA] < min_area]
        if small:
            alpha[np.isin(cc, small)] = 0
            removed = len(small)
    out_rgb = np.where((alpha > 0)[..., None], out_rgb, 0)

    rgba = np.concatenate([out_rgb, alpha[..., None] * 255.0], axis=2)
    rgba = np.clip(rgba + 0.5, 0, 255).astype(np.uint8)
    ys, xs = np.where(alpha > 0.05)
    info = {
        "bg": [float(v) for v in B],
        "foreground_px": int((alpha > 0.5).sum()),
        "bbox": [int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1] if len(xs) else None,
        "bbox_fraction": float((xs.max() - xs.min() + 1) * (ys.max() - ys.min() + 1) / (H * W)) if len(xs) else 0.0,
        "foreground_fraction": float((alpha > 0.5).sum() / (H * W)),
        "occupancy": float((alpha > 0.5).sum() / max((xs.max() - xs.min() + 1) * (ys.max() - ys.min() + 1), 1)) if len(xs) else 0.0,
        "border_margin_fraction": float(min(xs.min(), ys.min(), W - 1 - xs.max(), H - 1 - ys.max()) / max(H, W)) if len(xs) else 0.0,
        "border_residual_px": int((alpha[:2] > 0.02).sum() + (alpha[-2:] > 0.02).sum() + (alpha[2:-2, :2] > 0.02).sum() + (alpha[2:-2, -2:] > 0.02).sum()),
        "removed_small_components": removed,
        "touches_border": bool(len(xs) and (xs.min() == 0 or ys.min() == 0 or xs.max() == W - 1 or ys.max() == H - 1)),
    }
    return rgba, info


def matte_rgba_input(img: np.ndarray, bg=None, **kw):
    """输入可能已带 alpha：把它合成到背景色后再抠。"""
    a = alpha01(img)
    if (a < 0.98).mean() > 0.01:
        B = (255, 255, 255) if bg is None else bg
        rgba, info = matte(over(img, B), bg=B, **kw)
        info["note"] = "输入已带 alpha，已合成到背景色后重新抠图"
        return rgba, info
    return matte(img[..., :3], bg=bg, **kw)


def parse_bg(s):
    if s is None or s == "auto":
        return None
    if s in BG_CANDIDATES:
        return BG_CANDIDATES[s]
    return hex_to_rgb(s)
