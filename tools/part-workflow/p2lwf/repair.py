"""局部修复（遮罩补画）：只用于小范围缺陷，不用于补出整个部件。

make_repair：参考图裁切 + 保留色填充遮罩区 + mask.png + 提示词。
paste_back：结果先在遮罩外标定配准，再只取遮罩内的新像素，窄带羽化，并校验遮罩外是否被改动。
"""
from __future__ import annotations

import os

import cv2
import numpy as np

from .common import dump, hex_to_rgb, load_rgba, over, save_rgb
from .task import _read_template


def make_repair(ref_path, mask_path, box, out_dir, desc="a small defect or hidden area", fill="#FF00FF", pad=24):
    os.makedirs(out_dir, exist_ok=True)
    ref = over(load_rgba(ref_path))
    mask = load_rgba(mask_path)
    m = (mask[..., 3] > 127) if (mask[..., 3] < 255).any() else (mask[..., :3].mean(axis=2) > 127)
    H, W = ref.shape[:2]
    x0, y0, x1, y1 = box
    x0, y0, x1, y1 = max(0, x0 - pad), max(0, y0 - pad), min(W, x1 + pad), min(H, y1 + pad)
    crop = ref[y0:y1, x0:x1].copy()
    mc = m[y0:y1, x0:x1]
    crop[mc] = hex_to_rgb(fill)
    save_rgb(crop, os.path.join(out_dir, "input.png"))
    save_rgb(np.repeat((mc * 255).astype(np.uint8)[..., None], 3, axis=2), os.path.join(out_dir, "mask.png"))
    h, w = crop.shape[:2]
    with open(os.path.join(out_dir, "prompt.txt"), "w", encoding="utf-8") as f:
        f.write(_read_template("repair.txt").format(w=w, h=h, fill_hex=fill.upper(), repair_desc=desc))
    meta = {"kind": "repair", "ref": os.path.abspath(ref_path), "box": [x0, y0, x1, y1], "size": [w, h], "fill": fill}
    dump(meta, os.path.join(out_dir, "task.json"))
    return meta


def _calibrate(res, inp, keep):
    """用遮罩外区域把结果配准到输入（相似变换 + 小缩放）。返回对齐后的结果与矩阵。"""
    g1 = cv2.cvtColor(inp, cv2.COLOR_RGB2GRAY).astype(np.float32)
    g2 = cv2.cvtColor(res, cv2.COLOR_RGB2GRAY).astype(np.float32)
    warp = np.eye(2, 3, dtype=np.float32)
    mask = keep.astype(np.uint8) * 255
    try:
        _, warp = cv2.findTransformECC(g1, g2, warp, cv2.MOTION_AFFINE,
                                       (cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 200, 1e-6), mask, 5)
    except cv2.error:
        return res, None
    aligned = cv2.warpAffine(res, warp, (inp.shape[1], inp.shape[0]),
                             flags=cv2.INTER_LINEAR | cv2.WARP_INVERSE_MAP, borderMode=cv2.BORDER_REPLICATE)
    return aligned, warp


def paste_back(task_dir, result_path, out_path, band=4, match=True, bg_hex=None):
    import json
    with open(os.path.join(task_dir, "task.json"), encoding="utf-8") as f:
        meta = json.load(f)
    inp = cv2.cvtColor(cv2.imread(os.path.join(task_dir, "input.png")), cv2.COLOR_BGR2RGB)
    mask = cv2.imread(os.path.join(task_dir, "mask.png"), 0) > 127
    h, w = inp.shape[:2]
    res = over(load_rgba(result_path))
    resized_from = res.shape[1::-1]
    res = cv2.resize(res, (w, h), interpolation=cv2.INTER_AREA if res.shape[0] > h else cv2.INTER_CUBIC)

    keep = ~cv2.dilate(mask.astype(np.uint8), np.ones((9, 9), np.uint8)).astype(bool)
    aligned, warp = _calibrate(res, inp, keep)
    res = aligned

    diff_out = np.abs(res.astype(np.float32) - inp.astype(np.float32)).mean(axis=2)
    outside_mean = float(diff_out[keep].mean())
    fill = np.array(hex_to_rgb(meta["fill"]), np.float32)
    magenta_left = int(((np.linalg.norm(res.astype(np.float32) - fill, axis=2) < 60) & mask).sum())

    out = inp.astype(np.float32)
    dt = cv2.distanceTransform(mask.astype(np.uint8), cv2.DIST_L2, 3)
    m = np.clip(dt / max(band, 1), 0, 1)[..., None]
    new = res.astype(np.float32)
    bias = np.zeros(3, np.float32)
    if match:
        ring = cv2.dilate(mask.astype(np.uint8), np.ones((13, 13), np.uint8)).astype(bool) & ~cv2.dilate(mask.astype(np.uint8), np.ones((5, 5), np.uint8)).astype(bool)
        if ring.sum() > 50:
            bias = (inp[ring].astype(np.float32) - res[ring].astype(np.float32)).mean(axis=0)
            bias = np.clip(bias, -12, 12)
        new = np.clip(new + bias, 0, 255)
    out = out * (1 - m) + new * m
    out = np.where(mask[..., None], out, inp.astype(np.float32))  # 遮罩外严格不变
    out = np.clip(out + 0.5, 0, 255).astype(np.uint8)
    save_rgb(out, out_path)
    changed_outside = int((np.abs(out.astype(np.int16) - inp.astype(np.int16)).max(axis=2)[~mask] > 0).sum())
    report = {
        "result_original_size": list(resized_from), "calibrated": warp is not None,
        "outside_mean_abs_diff_before_paste": outside_mean,
        "magenta_pixels_left_in_mask": magenta_left,
        "tone_bias_applied": [float(b) for b in bias],
        "pixels_changed_outside_mask": changed_outside,
        "warnings": [],
    }
    if outside_mean > 8:
        report["warnings"].append("遮罩外差异偏大：生成工具可能改动了非遮罩区域，或配准失败；请看对比图")
    if magenta_left > 0:
        report["warnings"].append("遮罩内仍有保留色像素")
    dump(report, os.path.splitext(out_path)[0] + "_report.json")
    return report
