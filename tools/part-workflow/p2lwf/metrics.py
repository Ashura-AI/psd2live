"""补全指标：评价一个“补全后的新部件图层 N”相对“原画抠图 O”。两者都是已摆放到同一画布的 RGBA。

两项主指标：
  overlap_fidelity  重叠区域:原画重合度，0..1，咨询性（原画有缺陷时降到 0.7 也可能更好）
  completion_ratio  新图层总体积:原画（alpha 质量之比），有遮挡的部件必须大于 1
诊断：hidden_boundary_fraction、hidden_gain、visible_spill、composite_fidelity、style_drift、seam_score。
阈值只是初值，需要用真实部件标定（见 references/metrics.md）。
"""
from __future__ import annotations

import cv2
import numpy as np

from .common import alpha01, over, premult_blur, to_lab

DEFAULTS = {
    "min_fidelity": 0.70,        # 低于此值给 info，不阻止
    "completion_eps": 0.05,      # ratio ≤ 1+eps 视为没有补全
    "hidden_boundary_min": 0.03, # 原画轮廓中贴着上层的比例，高于此值才期望有补全（待标定）
    "spill_max": 0.03,
    "style_max": 0.50,
    "seam_max": 0.50,
    "contour_p95_max": 8.0,      # 轮廓距离 P95（画布像素），与画布尺寸有关，需标定
    "delta_e0": 12.0,
    "shift_tolerance": 2,
}


def _union(alphas, shape):
    U = np.zeros(shape, np.float32)
    for a in alphas:
        U = np.maximum(U, a)
    return U


def _crop_bbox(mask, pad):
    ys, xs = np.where(mask)
    if len(xs) == 0:
        return None
    H, W = mask.shape
    return max(0, xs.min() - pad), max(0, ys.min() - pad), min(W, xs.max() + 1 + pad), min(H, ys.max() + 1 + pad)


def contour_distance(O, N, uppers=None):
    """原画轮廓（排除贴着上层的部分）到新图层轮廓的距离分布，单位为画布像素。
    它比 overlap_fidelity 更直接地反映“轮廓是否对上”，肩端、手尖偏位会在 P95 上体现。"""
    o = alpha01(O) > 0.5
    n = alpha01(N) > 0.5
    k3 = np.ones((3, 3), np.uint8)
    edge_o = o & ~cv2.erode(o.astype(np.uint8), k3).astype(bool)
    edge_n = n & ~cv2.erode(n.astype(np.uint8), k3).astype(bool)
    if uppers:
        U = _union([alpha01(u) for u in uppers], o.shape) > 0.5
        edge_o &= ~cv2.dilate(U.astype(np.uint8), np.ones((9, 9), np.uint8)).astype(bool)
    if edge_o.sum() < 20 or edge_n.sum() < 20:
        return None
    dist = cv2.distanceTransform((~edge_n).astype(np.uint8), cv2.DIST_L2, 3)
    d = dist[edge_o]
    return {"contour_p50": float(np.percentile(d, 50)), "contour_p95": float(np.percentile(d, 95)),
            "contour_max": float(d.max()), "contour_points": int(edge_o.sum())}


def _lab_blur(rgba):
    return to_lab(np.clip(premult_blur(rgba, 1.0), 0, 255).astype(np.uint8))


def _overlap(O, N, cfg):
    aO, aN = alpha01(O), alpha01(N)
    bb = _crop_bbox((aO > 0.02) | (aN > 0.02), cfg["shift_tolerance"] + 2)
    if bb is None:
        return None
    x0, y0, x1, y1 = bb
    O, N, aO, aN = O[y0:y1, x0:x1], N[y0:y1, x0:x1], aO[y0:y1, x0:x1], aN[y0:y1, x0:x1]
    LO, LN = _lab_blur(O), _lab_blur(N)
    t = cfg["shift_tolerance"]
    dE = np.full(aO.shape, 1e9, np.float32)
    for dy in range(-t, t + 1):
        for dx in range(-t, t + 1):
            sh = np.roll(np.roll(LN, dy, axis=0), dx, axis=1)
            dE = np.minimum(dE, np.linalg.norm(LO - sh, axis=2))
    s = np.exp(-dE / cfg["delta_e0"])
    w = np.minimum(aO, aN)
    sumO = float(aO.sum())
    return {
        "overlap_fidelity": float((w * s).sum() / max(sumO, 1e-6)),
        "coverage": float(w.sum() / max(sumO, 1e-6)),
        "color_similarity": float((w * s).sum() / max(float(w.sum()), 1e-6)),
        "mean_delta_e": float((w * dE).sum() / max(float(w.sum()), 1e-6)),
    }


def _stats(L, mask):
    """区域内的颜色直方图、梯度直方图与平均梯度。"""
    gx = cv2.Sobel(L[..., 0], cv2.CV_32F, 1, 0, ksize=3)
    gy = cv2.Sobel(L[..., 0], cv2.CV_32F, 0, 1, ksize=3)
    g = np.sqrt(gx * gx + gy * gy)
    m = mask
    px = L[m]
    h = cv2.calcHist([px.reshape(-1, 1, 3).astype(np.float32)], [0, 1, 2], None, [8, 8, 8],
                     [0, 100, -100, 100, -100, 100])
    h = h / max(float(h.sum()), 1e-9)
    gh, _ = np.histogram(g[m], bins=16, range=(0, 40))
    gh = gh / max(float(gh.sum()), 1e-9)
    return h, gh, float(g[m].mean())


def _bhatt(p, q):
    p, q = np.asarray(p, np.float64).ravel(), np.asarray(q, np.float64).ravel()
    bc = float(np.sum(np.sqrt(p * q)))
    return float(np.sqrt(max(0.0, 1.0 - bc)))


def _style(O, N):
    aO, aN = alpha01(O), alpha01(N)
    inO = cv2.erode((aO > 0.5).astype(np.uint8), np.ones((5, 5), np.uint8)).astype(bool)
    new = cv2.erode(((aN > 0.5) & (aO < 0.1)).astype(np.uint8), np.ones((5, 5), np.uint8)).astype(bool)
    if inO.sum() < 150 or new.sum() < 150:
        return None
    LO, LN = _lab_blur(O), _lab_blur(N)
    h1, g1, m1 = _stats(LO, inO)
    h2, g2, m2 = _stats(LN, new)
    bc = _bhatt(h1, h2)
    bg = _bhatt(g1, g2)
    edge = float(min(1.0, abs(np.log((m2 + 1e-3) / (m1 + 1e-3)))))
    return {"style_drift": (bc + bg + edge) / 3.0, "style_color": bc, "style_gradient": bg, "style_edge": edge}


def _seam(O, N):
    aO, aN = alpha01(O), alpha01(N)
    o = (aO > 0.5).astype(np.uint8)
    k = np.ones((7, 7), np.uint8)
    ring_in = (o.astype(bool)) & ~cv2.erode(o, k).astype(bool)
    new = (aN > 0.5) & ~cv2.dilate(o, np.ones((5, 5), np.uint8)).astype(bool)  # 新增区域（排除轮廓抗锯齿带）
    ring_out = new & cv2.dilate(o, np.ones((11, 11), np.uint8)).astype(bool)
    ring_in = ring_in & cv2.dilate(ring_out.astype(np.uint8), np.ones((11, 11), np.uint8)).astype(bool)
    if ring_in.sum() < 50 or ring_out.sum() < 50:
        return None
    LN = to_lab(N[..., :3])
    LO = to_lab(O[..., :3])
    a = LO[ring_in].mean(axis=0)
    b = LN[ring_out].mean(axis=0)
    return float(min(1.0, np.linalg.norm(a - b) / 30.0))


def compute(O, N, uppers=None, fully_visible=False, ref=None, lowers=None, thresholds=None) -> dict:
    cfg = dict(DEFAULTS)
    cfg.update(thresholds or {})
    aO, aN = alpha01(O), alpha01(N)
    sumO, sumN = float(aO.sum()), float(aN.sum())
    res = {"original_area": sumO, "new_area": sumN,
           "completion_ratio": sumN / max(sumO, 1e-6)}

    ov = _overlap(O, N, cfg)
    if ov:
        res.update(ov)

    U = _union([alpha01(u) for u in (uppers or [])], aO.shape)
    have_upper = bool(uppers)
    new = np.clip(aN - aO, 0, 1)
    if have_upper:
        res["hidden_gain"] = float((new * U).sum() / max(sumO, 1e-6))
        res["visible_spill"] = float((new * (1 - U)).sum() / max(sumO, 1e-6))
        o = (aO > 0.5).astype(np.uint8)
        edge = (o.astype(bool)) & ~cv2.erode(o, np.ones((3, 3), np.uint8)).astype(bool)
        near_up = cv2.dilate((U > 0.5).astype(np.uint8), np.ones((5, 5), np.uint8)).astype(bool)
        n_edge = int(edge.sum())
        res["hidden_boundary_fraction"] = float((edge & near_up).sum() / n_edge) if n_edge else 0.0
        res["hidden_boundary_px"] = int((edge & near_up).sum())
    else:
        res["hidden_gain"] = res["visible_spill"] = res["hidden_boundary_fraction"] = None

    st = _style(O, N)
    res["style_drift"] = st["style_drift"] if st else None
    if st:
        res["style_detail"] = {k: v for k, v in st.items() if k != "style_drift"}
    res["seam_score"] = _seam(O, N)
    cd = contour_distance(O, N, uppers)
    res.update(cd or {"contour_p50": None, "contour_p95": None})

    if ref is not None:
        comp = np.zeros(O.shape, np.uint8)
        layers = list(lowers or []) + [N] + list(uppers or [])
        acc = np.full(O.shape[:2] + (3,), 255.0, np.float32)
        for L in layers:
            a = alpha01(L)[..., None]
            acc = acc * (1 - a) + L[..., :3].astype(np.float32) * a
        region = (aO > 0.05) | (aN > 0.05)
        if region.any():
            dE = np.linalg.norm(to_lab(np.clip(acc, 0, 255).astype(np.uint8)) - to_lab(ref[..., :3]), axis=2)
            res["composite_fidelity"] = float(np.exp(-dE[region] / cfg["delta_e0"]).mean())
    else:
        res["composite_fidelity"] = None

    flags = []
    r = res["completion_ratio"]
    hb = res["hidden_boundary_fraction"]
    if have_upper and hb is not None and hb >= cfg["hidden_boundary_min"] and res.get("hidden_boundary_px", 0) >= 6 and r <= 1 + cfg["completion_eps"] and not fully_visible:
        flags.append({"rule": "NOT_COMPLETED", "level": "error",
                      "detail": f"completion_ratio={r:.3f}，原画轮廓有 {hb:.0%} 紧贴上层，但新图层没有补出被遮挡的部分"})
    if not have_upper and not fully_visible:
        flags.append({"rule": "NO_UPPER_INFO", "level": "warning",
                      "detail": "未提供上层图层，无法判断补全是否必需；请用 --upper 传入遮挡该部件的图层，或用 --fully-visible 声明无遮挡"})
    if r < 0.98:
        flags.append({"rule": "SHRUNK", "level": "warning", "detail": f"completion_ratio={r:.3f} < 1，新图层比原画还小"})
    if res.get("visible_spill") is not None and res["visible_spill"] > cfg["spill_max"]:
        flags.append({"rule": "VISIBLE_SPILL", "level": "warning",
                      "detail": f"visible_spill={res['visible_spill']:.3f}，新增面积落在未被上层覆盖处，合成观感会变化"})
    if res["style_drift"] is not None and res["style_drift"] > cfg["style_max"]:
        flags.append({"rule": "STYLE_DRIFT", "level": "warning", "detail": f"style_drift={res['style_drift']:.3f}"})
    if res["seam_score"] is not None and res["seam_score"] > cfg["seam_max"]:
        flags.append({"rule": "SEAM", "level": "warning", "detail": f"seam_score={res['seam_score']:.3f}，新旧区域交界处颜色突变"})
    if res.get("contour_p95") is not None and res["contour_p95"] > cfg["contour_p95_max"]:
        flags.append({"rule": "POOR_FIT", "level": "warning",
                      "detail": f"轮廓距离 P95={res['contour_p95']:.1f}px（中位 {res['contour_p50']:.1f}px）：新图层轮廓与原画未对上，可能需要局部修形或地标对齐"})
    if "overlap_fidelity" in res and res["overlap_fidelity"] < cfg["min_fidelity"]:
        flags.append({"rule": "LOW_FIDELITY", "level": "info",
                      "detail": f"overlap_fidelity={res['overlap_fidelity']:.3f}；原画有缺陷时较低可能是合理的修复，请目视确认"})
    res["flags"] = flags
    res["thresholds"] = cfg
    res["blocked"] = any(f["level"] == "error" for f in flags)
    return res
