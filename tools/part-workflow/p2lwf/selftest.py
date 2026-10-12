"""合成数据自检：手臂被袖子遮住一段，候选图是整条手臂（放大 1.35 倍、偏移、带噪点、白底）。

检查：抠图保留内部白色并去白边；对齐恢复缩放与位置；补全指标对“复述原画”与“补出被遮部分”给出不同结论。
这是工具自身的回归检查，不是真实素材上的可行性测试。
"""
from __future__ import annotations

import json
import os
import tempfile

import cv2
import numpy as np

from . import align as A
from . import compare as C
from . import matte as Mt
from . import metrics as Me
from .common import alpha01, bbox_of, over, save_rgb, save_rgba

W, H = 800, 1200
SKIN = (238, 196, 176)
OUTL = (140, 84, 76)

ARM_PTS = [(330, 320), (300, 560), (232, 770), (205, 840)]
WIDTHS = [78, 64, 44, 30]
SLEEVE = np.array([(210, 250), (430, 250), (450, 540), (190, 540)], np.float32)


def _poly_arm(scale=1.0, origin=(0.0, 0.0), k=4, size=(W, H)):
    """画整条手臂（含被袖子遮住的上段）到透明 RGBA，坐标按 scale/origin 变换。"""
    w, h = size
    img = np.zeros((h * k, w * k, 4), np.uint8)
    pts = [((x * scale) + origin[0], (y * scale) + origin[1]) for x, y in ARM_PTS]
    ws = [v * scale for v in WIDTHS]

    def seg(col, extra, a=255):
        for i in range(len(pts) - 1):
            p1 = (int(pts[i][0] * k), int(pts[i][1] * k))
            p2 = (int(pts[i + 1][0] * k), int(pts[i + 1][1] * k))
            cv2.line(img, p1, p2, (*col, a), int((ws[i] + ws[i + 1]) / 2 * k) + extra * k, cv2.LINE_8)
        for (x, y), wd in zip(pts, ws):
            cv2.circle(img, (int(x * k), int(y * k)), int(wd / 2 * k) + extra * k // 2 * 2 // 2, (*col, a), -1)

    seg(OUTL, 3)
    seg(SKIN, 0)
    # 高光条
    for i in range(len(pts) - 1):
        p1 = (int((pts[i][0] - ws[i] * 0.18) * k), int(pts[i][1] * k))
        p2 = (int((pts[i + 1][0] - ws[i + 1] * 0.18) * k), int(pts[i + 1][1] * k))
        cv2.line(img, p1, p2, (255, 225, 210, 255), max(2, int(ws[i] * 0.12 * k)))
    # 手腕处白色袖口（内部白色，用深色线条包住，不与背景相连）
    cx, cy = pts[2]
    cv2.ellipse(img, (int(cx * k), int(cy * k)), (int(ws[2] * 0.78 * k), int(ws[2] * 0.22 * k)), -18, 0, 360, (90, 90, 110, 255), -1)
    cv2.ellipse(img, (int(cx * k), int(cy * k)), (int(ws[2] * 0.70 * k), int(ws[2] * 0.15 * k)), -18, 0, 360, (255, 255, 255, 255), -1)
    rgba = cv2.resize(img, (w, h), interpolation=cv2.INTER_AREA)
    # INTER_AREA 对非预乘缩小会让边缘变暗，这里对颜色按 alpha 归一
    a = rgba[..., 3:4].astype(np.float32)
    big_a = img[..., 3:4].astype(np.float32) / 255.0
    num = cv2.resize((img[..., :3].astype(np.float32) * big_a), (w, h), interpolation=cv2.INTER_AREA)
    den = cv2.resize(big_a, (w, h), interpolation=cv2.INTER_AREA)[..., None]
    rgb = np.where(den > 1e-3, num / np.maximum(den, 1e-3), 0)
    return np.concatenate([np.clip(rgb, 0, 255), a], axis=2).astype(np.uint8)


def _sleeve_layer():
    from .common import polygon_mask
    m = polygon_mask((W, H), SLEEVE)
    rgba = np.zeros((H, W, 4), np.uint8)
    rgba[..., :3] = (252, 252, 252)
    rgba[..., 3] = (m * 255).astype(np.uint8)
    # 描边
    cnt = np.round(SLEEVE).astype(np.int32)
    edge = np.zeros((H, W), np.uint8)
    cv2.polylines(edge, [cnt], True, 255, 3, cv2.LINE_AA)
    rgba[..., :3] = np.where(edge[..., None] > 0, (120, 120, 140), rgba[..., :3])
    rgba[..., 3] = np.maximum(rgba[..., 3], edge)
    return rgba


def _torso_layer():
    rgba = np.zeros((H, W, 4), np.uint8)
    cv2.rectangle(rgba, (330, 250), (560, 800), (150, 190, 230, 255), -1)
    cv2.rectangle(rgba, (330, 250), (560, 800), (60, 80, 120, 255), 3)
    return rgba


def _over(base, top):
    a = alpha01(top)[..., None]
    out = base.astype(np.float32) * (1 - a) + top[..., :3].astype(np.float32) * a
    return np.clip(out, 0, 255).astype(np.uint8)


def run(out_dir=None) -> int:
    out_dir = out_dir or tempfile.mkdtemp(prefix="p2lwf_selftest_")
    os.makedirs(out_dir, exist_ok=True)
    ok = True

    def check(name, cond, detail=""):
        nonlocal ok
        ok &= bool(cond)
        print(("PASS " if cond else "FAIL ") + name + (f"  [{detail}]" if detail else ""))

    # 场景
    arm_full = _poly_arm()
    sleeve = _sleeve_layer()
    torso = _torso_layer()
    ref = np.full((H, W, 3), 255, np.uint8)
    ref = _over(ref, torso)
    ref = _over(ref, arm_full)
    ref = _over(ref, sleeve)
    save_rgb(ref, os.path.join(out_dir, "ref.png"))
    ref_rgba = np.dstack([ref, np.full((H, W), 255, np.uint8)])

    # 原画抠图 O：手臂可见部分
    O = arm_full.copy()
    O[..., 3] = (alpha01(arm_full) * (1 - alpha01(sleeve)) * 255).astype(np.uint8)
    save_rgba(O, os.path.join(out_dir, "original.png"))
    save_rgba(sleeve, os.path.join(out_dir, "sleeve.png"))

    # 候选图：整条手臂，放大 1.35 倍，偏移，白底，带噪点
    CW, CH = 1100, 1500
    cand_rgba = _poly_arm(scale=1.35, origin=(60.0, 90.0), size=(CW, CH))
    cand_rgb = over(cand_rgba)
    rng = np.random.default_rng(1)
    cand_rgb = np.clip(cand_rgb.astype(np.float32) + rng.normal(0, 1.6, cand_rgb.shape), 0, 255).astype(np.uint8)
    save_rgb(cand_rgb, os.path.join(out_dir, "candidate_raw.png"))

    # 1. 抠图
    m, info = Mt.matte(cand_rgb, bg=None)
    save_rgba(m, os.path.join(out_dir, "candidate_matte.png"))
    truth_a = alpha01(cand_rgba)
    got_a = alpha01(m)
    err = float(np.abs(truth_a - got_a).mean())
    check("matte: alpha 平均误差小", err < 0.004, f"mean|Δα|={err:.4f}")
    cuff = (int(ARM_PTS[2][0] * 1.35 + 60), int(ARM_PTS[2][1] * 1.35 + 90))
    check("matte: 前景内部的白色不被抠空", got_a[cuff[1], cuff[0]] > 0.95, f"α={got_a[cuff[1], cuff[0]]:.2f}")
    edge = (got_a > 0.2) & (got_a < 0.8)
    if edge.sum() > 50:
        c = m[..., :3][edge].astype(np.float32).mean(axis=0)
        check("matte: 半透明边缘没有白边", c.mean() < 235, f"边缘平均色={c.round().tolist()}")
    check("matte: 背景被清除", got_a[:20, :20].max() == 0)

    # 2. 对齐
    fg = Mt.matte(ref, bg=(255, 255, 255))[0][..., 3] > 127
    r = A.align(ref, m, alpha01(O), uppers=[alpha01(sleeve)], ref_fg=fg)
    used = r.pop("candidate_used")
    placed = A.place(used, r["matrix"], (W, H))
    save_rgba(placed, os.path.join(out_dir, "placed.png"))
    exp_scale = 1.0 / 1.35
    check("align: 缩放恢复在 4% 内", abs(r["scale"] / exp_scale - 1) < 0.04, f"scale={r['scale']:.4f} 期望={exp_scale:.4f}")
    gb, pb = bbox_of(alpha01(arm_full)), bbox_of(alpha01(placed))
    cd = np.hypot((gb[0] + gb[2]) / 2 - (pb[0] + pb[2]) / 2, (gb[1] + gb[3]) / 2 - (pb[1] + pb[3]) / 2)
    check("align: 中心偏差 ≤ 6px", cd <= 6, f"{cd:.1f}px")
    print(f"     score={r['score']:.3f} margin={r['margin']} ambiguous={r['ambiguous']}")

    # 3. 指标
    ups = [sleeve]
    good = Me.compute(O, placed, uppers=ups, ref=ref_rgba)
    check("metrics: 补全后 completion_ratio 明显大于 1", good["completion_ratio"] > 1.15, f"{good['completion_ratio']:.3f}")
    check("metrics: 补全后 overlap_fidelity 较高", good["overlap_fidelity"] > 0.8, f"{good['overlap_fidelity']:.3f}")
    check("metrics: 补全后未被 NOT_COMPLETED 阻止", not good["blocked"], json.dumps([f["rule"] for f in good["flags"]]))
    check("metrics: 补出的部分落在上层之下", good["hidden_gain"] is not None and good["hidden_gain"] > 0.1 and good["visible_spill"] < 0.03,
          f"hidden_gain={good['hidden_gain']:.3f} spill={good['visible_spill']:.3f}")
    print(f"     style_drift={good['style_drift']} seam={good['seam_score']} composite={good['composite_fidelity']}")
    bad = Me.compute(O, O.copy(), uppers=ups, ref=ref_rgba)
    check("metrics: 只复述原画时报 NOT_COMPLETED", bad["blocked"] and any(f["rule"] == "NOT_COMPLETED" for f in bad["flags"]),
          f"ratio={bad['completion_ratio']:.3f} hidden_boundary={bad['hidden_boundary_fraction']:.2f}")
    fv = Me.compute(O, O.copy(), uppers=ups, fully_visible=True)
    check("metrics: 声明 fully_visible 可豁免", not fv["blocked"])

    # 4. 对比图
    save_rgb(C.sheet(ref, placed, O, [(alpha01(sleeve) * 255).astype(np.uint8)]), os.path.join(out_dir, "sheet.png"))
    check("compare: 对比图已生成", os.path.exists(os.path.join(out_dir, "sheet.png")))

    print(f"输出目录: {out_dir}")
    print("ALL PASS" if ok else "SOME FAILED")
    return 0 if ok else 1
