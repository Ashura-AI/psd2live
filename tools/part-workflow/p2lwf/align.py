"""把候选部件图（已抠图 RGBA）自动摆到参考图上：相似变换（缩放 + 小旋转 + 平移）。

评分只在“原画可见区域 T”内比较颜色，并惩罚候选图伸进参考背景（不被任何上层覆盖）的面积，
因此候选图可以比原画多出被遮挡的部分，也可以略好于原画，而不会被强行拉回原画轮廓。
求解：多尺度带掩码模板匹配给出若干起点 → 对每个起点做由粗到细的模式搜索 → 取最优并报告可区分度。
"""
from __future__ import annotations

import math

import cv2
import numpy as np

from .common import affine3, alpha01, bbox_of, scale3, sim_matrix, to_lab, trans3, warp_rgba


class _Eval:
    """在一个裁切窗口和工作倍率下，对候选变换打分。"""

    def __init__(self, ref_rgb, T, allowed, cand, pivot, crop, w):
        x0, y0, x1, y1 = crop
        self.crop, self.w, self.pivot, self.cand = crop, w, pivot, cand
        ref = ref_rgb[y0:y1, x0:x1]
        self.size = (max(1, int(round((x1 - x0) * w))), max(1, int(round((y1 - y0) * w))))

        def rz(a, interp):
            return cv2.resize(a, self.size, interpolation=interp)

        self.lab = cv2.GaussianBlur(to_lab(rz(ref, cv2.INTER_AREA)), (0, 0), 0.8)
        self.T = rz(T[y0:y1, x0:x1].astype(np.float32), cv2.INTER_AREA) > 0.5
        self.allowed = rz(allowed[y0:y1, x0:x1].astype(np.float32), cv2.INTER_AREA) > 0.5
        self.nT = max(int(self.T.sum()), 1)

    def matrix(self, p):
        s, deg, cx, cy = p
        M = affine3(sim_matrix(s, deg, cx, cy, self.pivot))
        return (scale3(self.w) @ trans3(-self.crop[0], -self.crop[1]) @ M)[:2]

    def score(self, p, parts=False):
        pl = warp_rgba(self.cand, self.matrix(p), self.size, interp=cv2.INTER_LINEAR)
        a = pl[..., 3] > 127
        vis = a & self.T
        nv = int(vis.sum())
        if nv < 30:
            return (1e3, {}) if parts else 1e3
        lab = to_lab(pl[vis][:, :3].reshape(-1, 1, 3)).reshape(-1, 3)
        dE = np.linalg.norm(lab - self.lab[vis], axis=1)
        color = float(np.mean(np.sqrt(dE * dE + 16.0) - 4.0)) / 25.0
        cover = nv / self.nT
        spill = float((a & ~self.allowed).sum()) / self.nT
        total = color + 3.0 * (1 - cover) + 4.0 * spill
        if parts:
            return total, {"color": color, "coverage": cover, "spill": spill}
        return total


def _refine(ev, p0, rot_limit, steps0=(0.05, 0.6, 4.0, 4.0), min_steps=(0.002, 0.05, 0.25, 0.25), max_iter=160):
    p = list(p0)
    best = ev.score(p)
    steps = list(steps0)
    it = 0
    while it < max_iter:
        improved = False
        for i in range(4):
            if i == 1 and rot_limit <= 0:
                continue
            for sign in (1, -1):
                q = list(p)
                q[i] = q[i] * (1 + sign * steps[i]) if i == 0 else q[i] + sign * steps[i]
                if i == 1:
                    q[1] = max(-rot_limit, min(rot_limit, q[1]))
                sc = ev.score(q)
                it += 1
                if sc < best - 1e-6:
                    best, p, improved = sc, q, True
        if not improved:
            steps = [s * 0.5 for s in steps]
            if all(s < m for s, m in zip(steps, min_steps)):
                break
    return p, best


def _coarse_starts(ev, patch, scales, k_per_scale=2):
    """形状粗配准：对每个尺度，用一次互相关求 Σ(a·W)，W = T − 3·(不允许区域)。
    候选 alpha 覆盖越多原画可见区域、伸进不允许区域越少得分越高，所以被遮挡多出来的部分不会拖累匹配。"""
    cands = []
    Wmap = ev.T.astype(np.float32) - 3.0 * (~ev.allowed).astype(np.float32)
    ph, pw = patch.shape[:2]
    for s in scales:
        f = s * ev.w
        tw, th = int(round(pw * f)), int(round(ph * f))
        if tw < 6 or th < 6:
            continue
        a = cv2.resize(patch[..., 3], (tw, th), interpolation=cv2.INTER_AREA).astype(np.float32) / 255.0
        px, py = tw // 2, th // 2
        big = cv2.copyMakeBorder(Wmap, py, py, px, px, cv2.BORDER_CONSTANT, value=0.0)
        if tw > big.shape[1] or th > big.shape[0]:
            continue
        res = cv2.matchTemplate(big, a, cv2.TM_CCORR) / ev.nT
        r = max(tw, th) // 4
        for _ in range(k_per_scale):
            _, mx, _, loc = cv2.minMaxLoc(res)
            if mx <= 0:
                break
            lx, ly = loc
            cands.append((-float(mx), s, lx - px + tw / 2.0, ly - py + th / 2.0))
            cv2.circle(res, (lx, ly), r, -1e9, -1)
    return cands


def align(ref_rgb, cand, T, uppers=None, ref_fg=None, scale_range=(0.35, 3.0), n_scales=18,
          rot_limit=0.0, mirror=False, work_max=800, init=None, roi=None) -> dict:
    """
    ref_rgb: 参考图 RGB uint8（画布）。cand: 候选 RGBA uint8（任意尺寸，已抠图）。
    T: 画布大小 float 0..1，原画中该部件的可见区域（原画抠图的 alpha）。
    uppers: 画布大小 alpha 列表（上层图层）。候选图只允许在 T 与上层之下伸展。
    ref_fg: 画布大小布尔，参考图前景；没有 uppers 时候选图可伸入任何前景。
    scale_range: 相对“T 的外包尺寸 / 候选图外包尺寸”的搜索范围。
    init: (scale, cx, cy) 手工初值，其中 scale 为 候选像素→画布像素 倍率，(cx, cy) 为候选外包框中心落点。
    返回 matrix（2x3，镜像后的候选像素→画布）、scale、rotation_deg、score、margin、ambiguous 等；
    结果里的 `candidate_used` 是实际用于摆放的候选图（镜像时已翻转）。
    """
    H, W = ref_rgb.shape[:2]
    if mirror:
        cand = np.ascontiguousarray(cand[:, ::-1])
    bb = bbox_of(alpha01(cand), 0.5)
    if bb is None:
        raise ValueError("候选图没有前景像素")
    cb = cand[bb[1]:bb[3], bb[0]:bb[2]]
    k = 1.0
    if max(cb.shape[:2]) > 1600:
        k = 1600.0 / max(cb.shape[:2])
        cb = cv2.resize(cb, (int(cb.shape[1] * k), int(cb.shape[0] * k)), interpolation=cv2.INTER_AREA)
    pivot = (cb.shape[1] / 2.0, cb.shape[0] / 2.0)

    U = np.zeros((H, W), np.float32)
    for u in uppers or []:
        U = np.maximum(U, u.astype(np.float32))
    allowed = (T > 0.5) | (U > 0.5)
    if not uppers:
        allowed = allowed | (ref_fg if ref_fg is not None else np.ones((H, W), bool))

    tb = bbox_of(T, 0.5)
    if tb is None:
        raise ValueError("T（原画可见区域）为空")
    if roi is None:
        pad = int(max(tb[2] - tb[0], tb[3] - tb[1]) * 0.8)
        roi = (max(0, tb[0] - pad), max(0, tb[1] - pad), min(W, tb[2] + pad), min(H, tb[3] + pad))
    w = min(1.0, work_max / max(roi[2] - roi[0], roi[3] - roi[1]))
    ev = _Eval(ref_rgb, T, allowed, cb, pivot, roi, w)

    base = max(tb[2] - tb[0], tb[3] - tb[1]) / max(cb.shape[:2])  # 缩小候选像素 → 画布
    starts = []
    if init is not None:
        s0, cx0, cy0 = init
        starts = [(0.0, s0 / k, cx0, cy0)]
    else:
        scales = np.geomspace(scale_range[0], scale_range[1], n_scales) * base
        cl = sorted(_coarse_starts(ev, cb, scales), key=lambda t: t[0])
        for sc, s, cxw, cyw in cl[:6]:
            starts.append((sc, s, cxw / w + roi[0], cyw / w + roi[1]))
        if not starts:
            starts = [(0.0, base, (tb[0] + tb[2]) / 2.0, (tb[1] + tb[3]) / 2.0)]

    results = sorted(((_refine(ev, (s, 0.0, cx, cy), rot_limit)[::-1]) for _, s, cx, cy in starts), key=lambda t: t[0])
    distinct = []
    for sc, p in results:
        if all(abs(math.log(p[0] / q[0])) > 0.04 or abs(p[2] - q[2]) > 6 or abs(p[3] - q[3]) > 6 for _, q in distinct):
            distinct.append((sc, p))
    best_sc, best_p = distinct[0]
    second = distinct[1][0] if len(distinct) > 1 else None

    # 全分辨率精修（窗口为 T 与候选图所在范围）
    M_c = sim_matrix(best_p[0], best_p[1], best_p[2], best_p[3], pivot)
    wb = bbox_of(warp_rgba(cb, M_c, (W, H), cv2.INTER_LINEAR)[..., 3].astype(np.float32) / 255.0, 0.5) or tb
    win = (max(0, min(tb[0], wb[0]) - 16), max(0, min(tb[1], wb[1]) - 16),
           min(W, max(tb[2], wb[2]) + 16), min(H, max(tb[3], wb[3]) + 16))
    w2 = min(1.0, 1400.0 / max(win[2] - win[0], win[3] - win[1]))
    ev2 = _Eval(ref_rgb, T, allowed, cb, pivot, win, w2)
    best_p, sc2 = _refine(ev2, best_p, rot_limit, steps0=(0.01, 0.2, 1.5, 1.5),
                          min_steps=(0.0005, 0.02, 0.1, 0.1), max_iter=200)
    _, parts = ev2.score(best_p, parts=True)

    s, deg, cx, cy = best_p
    M_small = affine3(sim_matrix(s, deg, cx, cy, pivot))
    M_native = (M_small @ scale3(k) @ trans3(-bb[0], -bb[1]))[:2]  # 镜像后的候选像素 → 画布
    margin = None if second is None else float((second - best_sc) / max(second, 1e-6))
    poor = []
    if parts.get("coverage", 0) < 0.85:
        poor.append(f"原画可见区域只被覆盖 {parts.get('coverage', 0):.0%}")
    if parts.get("spill", 0) > 0.10:
        poor.append(f"候选图伸进不允许区域 {parts.get('spill', 0):.0%}")
    if parts.get("color", 0) > 0.35:
        poor.append("可见区域颜色差异大")
    fit = {
        "quality": "poor" if poor else "ok",
        "reasons": poor,
        # margin 为空表示没有第二个独立候选（例如手工初值），此时可区分度未知，不等于可靠
        "confidence": "unknown" if margin is None else ("low" if margin < 0.1 else "ok"),
    }
    return {
        "fit": fit,
        "matrix": M_native.tolist(),
        "scale": float(s * k), "rotation_deg": float(deg), "center": [float(cx), float(cy)],
        "score": float(sc2), "score_parts": parts,
        "second_best_score": None if second is None else float(second),
        "margin": margin,
        "ambiguous": bool(margin is not None and margin < 0.1),
        "mirror": bool(mirror),
        "n_starts": len(starts), "n_distinct": len(distinct),
        "candidate_used": cand,
    }


def place(cand, matrix, size_wh):
    return warp_rgba(cand, np.array(matrix, np.float64), size_wh, interp=cv2.INTER_CUBIC)
