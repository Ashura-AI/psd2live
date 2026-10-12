"""部件任务包与导回：make_task 生成给外部 AI 的参考图 + 提示词，ingest 抠图、对齐、算指标、出对比图。"""
from __future__ import annotations

import os
import shutil

import cv2
import numpy as np

from . import align as A
from . import compare as C
from . import matte as Mt
from . import metrics as Me
from .common import (alpha01, bbox_of, dump, hex_to_rgb, load_rgba, over, rgb_to_hex, save_rgb, save_rgba)

TEMPLATES = os.path.join(os.path.dirname(__file__), "templates")

SIDE_RULES = {
    "none": "This is a single, central part; draw exactly one.",
    "left": "Draw ONLY the character's LEFT-side one (the one on the viewer's RIGHT in the reference). Do not draw its counterpart on the other side.",
    "right": "Draw ONLY the character's RIGHT-side one (the one on the viewer's LEFT in the reference). Do not draw its counterpart on the other side.",
}


def _read_template(name):
    with open(os.path.join(TEMPLATES, name), encoding="utf-8") as f:
        return f.read()


def make_task(ref_path, out_dir, part, desc, region, side="none", original=None, uppers=None, bg="auto",
              hidden_hint="e.g. under sleeves, skirts or hair", hidden_extent="smoothly to its natural root/attachment point",
              cast_examples="shadows, garter straps, skirt shadow", min_size=1536, margin=0.35, extra_rules=None):
    os.makedirs(out_dir, exist_ok=True)
    ref = load_rgba(ref_path)
    H, W = ref.shape[:2]
    x0, y0, x1, y1 = region
    mw, mh = int((x1 - x0) * margin), int((y1 - y0) * margin)
    cx0, cy0, cx1, cy1 = max(0, x0 - mw), max(0, y0 - mh), min(W, x1 + mw), min(H, y1 + mh)
    save_rgb(over(ref)[cy0:cy1, cx0:cx1], os.path.join(out_dir, "reference.png"))

    orig_abs = None
    bgsel = None
    if original:
        orig_abs = os.path.abspath(original)
        o = load_rgba(original)
        if o.shape[:2] != (H, W):
            raise ValueError("original 必须与参考图同尺寸（画布大小）")
        bgsel = Mt.suggest_bg(o[..., :3], alpha01(o))
    if bg == "auto":
        bgsel = bgsel or {"name": "white", "hex": "#FFFFFF", "reason": "默认"}
    else:
        c = Mt.parse_bg(bg)
        bgsel = {"name": bg if bg in Mt.BG_CANDIDATES else "custom", "hex": rgb_to_hex(c), "reason": "手工指定"}

    prompt = _read_template("whole_part.txt").format(
        ref="reference.png", part_desc=desc, hidden_hint=hidden_hint, hidden_extent=hidden_extent,
        cast_examples=cast_examples, side_rule=SIDE_RULES[side], bg_name=bgsel["name"].upper() if bgsel["name"] != "custom" else "solid color",
        bg_hex=bgsel["hex"], min_size=min_size, out="result.png")
    if extra_rules:
        prompt = prompt.replace("Save the result as", "Additional rules:\n" + "\n".join(f"- {r}" for r in extra_rules) + "\nSave the result as", 1)
    with open(os.path.join(out_dir, "prompt.txt"), "w", encoding="utf-8") as f:
        f.write(prompt)
    meta = {
        "kind": "whole_part", "part": part, "side": side, "desc": desc,
        "ref": os.path.abspath(ref_path), "canvas": [W, H], "region": [x0, y0, x1, y1], "crop": [cx0, cy0, cx1, cy1],
        "original": orig_abs, "uppers": [os.path.abspath(u) for u in (uppers or [])],
        "bg": bgsel,
    }
    dump(meta, os.path.join(out_dir, "part.json"))
    return meta


def ingest(task_dir, result_path, out_dir=None, mirror=False, rot_limit=0.0, scale_range=(0.4, 1.8),
           fully_visible=False, lowers=None, thresholds=None, matte_kw=None, init=None, n_scales=12):
    import json
    with open(os.path.join(task_dir, "part.json"), encoding="utf-8") as f:
        meta = json.load(f)
    out_dir = out_dir or os.path.join(task_dir, "out")
    os.makedirs(out_dir, exist_ok=True)

    ref = load_rgba(meta["ref"])
    ref_rgb = over(ref)
    H, W = ref_rgb.shape[:2]
    report = {"part": meta["part"], "task": os.path.abspath(task_dir), "result": os.path.abspath(result_path)}

    bg = hex_to_rgb(meta["bg"]["hex"])
    res_img = load_rgba(result_path)
    cand, minfo = Mt.matte_rgba_input(res_img, bg=bg, **(matte_kw or {}))
    save_rgba(cand, os.path.join(out_dir, "candidate.png"))
    report["matte"] = minfo
    if minfo.get("touches_border"):
        report["warnings"] = report.get("warnings", []) + ["候选图前景接触图像边框：可能被裁切，或白色前景与白背景相连；考虑换背景色重新生成"]
    if minfo["bbox_fraction"] > 0.9:
        report["warnings"] = report.get("warnings", []) + ["前景外包框占整图 90% 以上，疑似抠图失败（背景色不匹配或有噪点）"]

    O = load_rgba(meta["original"]) if meta.get("original") else None
    ref_fg = Mt.matte(ref_rgb, bg=(255, 255, 255))[0][..., 3] > 127
    T = alpha01(O) if O is not None else ref_fg.astype(np.float32)
    ups = [load_rgba(u) for u in meta.get("uppers", [])]
    ups_a = [alpha01(u) for u in ups]

    al = A.align(ref_rgb, cand, T, uppers=ups_a or None, ref_fg=ref_fg, scale_range=scale_range,
                 rot_limit=rot_limit, mirror=mirror, init=init, n_scales=n_scales)
    used = al.pop("candidate_used")
    placed = A.place(used, al["matrix"], (W, H))
    save_rgba(placed, os.path.join(out_dir, "placed.png"))
    report["align"] = al

    if O is not None:
        m = Me.compute(O, placed, uppers=ups, fully_visible=fully_visible, ref=ref, lowers=lowers, thresholds=thresholds)
        report["metrics"] = m
        save_rgb(C.sheet(ref_rgb, placed, O, ups_a and [(u * 255).astype(np.uint8) for u in ups_a]), os.path.join(out_dir, "sheet.png"))
    else:
        save_rgb(C.sheet(ref_rgb, placed, None, None), os.path.join(out_dir, "sheet.png"))
        report["metrics"] = None
        report.setdefault("warnings", []).append("任务没有原画抠图（original），未计算补全指标")
    dump(report, os.path.join(out_dir, "report.json"))
    return report
