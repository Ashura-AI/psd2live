"""命令行入口：python -m p2lwf <命令> ...；所有命令向 stdout 输出 JSON。"""
from __future__ import annotations

import argparse
import os
import sys

import numpy as np

from . import __version__
from . import align as A
from . import assemble as As
from . import compare as C
from . import cutout as Cu
from . import matte as Mt
from . import metrics as Me
from . import repair as Rp
from . import task as Tk
from .common import (alpha01, dump, load_rgba, over, parse_box, save_rgb, save_rgba)


def _ensure_dir(p):
    d = os.path.dirname(os.path.abspath(p))
    os.makedirs(d, exist_ok=True)


def cmd_matte(a):
    img = load_rgba(a.input)
    rgba, info = Mt.matte_rgba_input(img, bg=Mt.parse_bg(a.bg), tol=a.tol, band=a.band, min_area=a.min_area, shrink=a.shrink)
    _ensure_dir(a.output)
    save_rgba(rgba, a.output)
    print(dump(info))


def cmd_suggest_bg(a):
    img = load_rgba(a.input)
    print(dump(Mt.suggest_bg(img[..., :3], alpha01(img))))


def cmd_cutout(a):
    ref = load_rgba(a.ref)
    out = Cu.cutout(ref, include=a.include, exclude=a.exclude, use_matte=not a.no_matte)
    _ensure_dir(a.output)
    save_rgba(out, a.output)
    if a.preview:
        Cu.preview(ref, [out] + [load_rgba(p) for p in (a.preview_with or [])], a.preview)
    print(dump({"output": a.output, "area_px": int((out[..., 3] > 127).sum())}))


def cmd_task(a):
    meta = Tk.make_task(a.ref, a.out, a.part, a.desc, parse_box(a.region), side=a.side, original=a.original,
                        uppers=a.upper, bg=a.bg, hidden_hint=a.hidden_hint, hidden_extent=a.hidden_extent,
                        cast_examples=a.cast_examples, min_size=a.min_size, margin=a.margin, extra_rules=a.rule)
    print(dump({"task_dir": a.out, "prompt": os.path.join(a.out, "prompt.txt"), "bg": meta["bg"]}))


def cmd_ingest(a):
    lowers = [load_rgba(p) for p in (a.lower or [])] or None
    rep = Tk.ingest(a.task, a.result, a.out, mirror=a.mirror, rot_limit=a.rot_limit,
                    scale_range=(a.scale_min, a.scale_max), fully_visible=a.fully_visible, lowers=lowers,
                    init=tuple(float(v) for v in a.init.split(",")) if a.init else None, n_scales=a.n_scales)
    rep = dict(rep)
    out = a.out or os.path.join(a.task, "out")
    summary = {"out_dir": out, "align": {k: rep["align"][k] for k in ("scale", "rotation_deg", "center", "score", "margin", "ambiguous")}}
    if rep.get("metrics"):
        m = rep["metrics"]
        summary["metrics"] = {k: m.get(k) for k in ("overlap_fidelity", "completion_ratio", "hidden_boundary_fraction",
                                                    "hidden_gain", "visible_spill", "style_drift", "seam_score", "composite_fidelity")}
        summary["flags"] = m["flags"]
        summary["blocked"] = m["blocked"]
    summary["warnings"] = rep.get("warnings", [])
    print(dump(summary))


def cmd_align(a):
    ref = load_rgba(a.ref)
    ref_rgb = over(ref)
    cand = load_rgba(a.candidate)
    T = alpha01(load_rgba(a.original))
    ups = [alpha01(load_rgba(u)) for u in (a.upper or [])]
    fg = Mt.matte(ref_rgb)[0][..., 3] > 127
    r = A.align(ref_rgb, cand, T, uppers=ups or None, ref_fg=fg, scale_range=(a.scale_min, a.scale_max),
                rot_limit=a.rot_limit, mirror=a.mirror,
                init=tuple(float(v) for v in a.init.split(",")) if a.init else None, n_scales=a.n_scales)
    used = r.pop("candidate_used")
    _ensure_dir(a.output)
    save_rgba(A.place(used, r["matrix"], (ref.shape[1], ref.shape[0])), a.output)
    print(dump(r))


def cmd_metrics(a):
    O = load_rgba(a.original)
    N = load_rgba(a.new)
    ups = [load_rgba(p) for p in (a.upper or [])]
    ref = load_rgba(a.ref) if a.ref else None
    lowers = [load_rgba(p) for p in (a.lower or [])] or None
    th = {}
    for k in ("min_fidelity", "completion_eps", "hidden_boundary_min", "spill_max", "style_max", "seam_max"):
        v = getattr(a, k)
        if v is not None:
            th[k] = v
    print(dump(Me.compute(O, N, uppers=ups, fully_visible=a.fully_visible, ref=ref, lowers=lowers, thresholds=th)))


def cmd_compare(a):
    ref = load_rgba(a.ref)
    N = load_rgba(a.new)
    O = load_rgba(a.original) if a.original else None
    ups = [load_rgba(u)[..., 3] for u in (a.upper or [])]
    _ensure_dir(a.output)
    save_rgb(C.sheet(over(ref), N, O, ups), a.output)
    print(dump({"output": a.output}))


def cmd_repair_task(a):
    meta = Rp.make_repair(a.ref, a.mask, parse_box(a.box), a.out, desc=a.desc, fill=a.fill, pad=a.pad)
    print(dump({"task_dir": a.out, "prompt": os.path.join(a.out, "prompt.txt"), "size": meta["size"]}))


def cmd_repair_paste(a):
    _ensure_dir(a.output)
    print(dump(Rp.paste_back(a.task, a.result, a.output, band=a.band, match=not a.no_match)))


def cmd_composite(a):
    size, layers = As.load_spec(a.spec)
    _ensure_dir(a.output)
    save_rgb(As.composite(layers, size, hide=set(a.hide or [])), a.output)
    print(dump({"output": a.output, "layers": [n for n, _ in layers]}))


def cmd_psd(a):
    size, layers = As.load_spec(a.spec)
    _ensure_dir(a.output)
    As.write_psd(layers, size, a.output)
    print(dump({"output": a.output, "layers": [n for n, _ in layers]}))


def cmd_coverage(a):
    size, layers = As.load_spec(a.spec)
    print(dump(As.coverage(layers, load_rgba(a.ref), size, a.out)))


def cmd_exposure(a):
    size, layers = As.load_spec(a.spec)
    print(dump(As.seam_exposure(layers, size, a.out, only=set(a.only) if a.only else None)))


def cmd_selftest(a):
    from . import selftest
    sys.exit(selftest.run(a.out))


def main(argv=None):
    for st in (sys.stdout, sys.stderr):
        try:
            st.reconfigure(encoding="utf-8")
        except Exception:
            pass
    p = argparse.ArgumentParser(prog="p2lwf", description=f"PSD2Live 部件工作流工具 v{__version__}")
    sp = p.add_subparsers(dest="cmd", required=True)

    s = sp.add_parser("matte", help="纯色背景抠图（默认自动估计背景，推荐白底）")
    s.add_argument("input"); s.add_argument("output")
    s.add_argument("--bg", default="auto", help="auto | white | green | #RRGGBB")
    s.add_argument("--tol", type=float, default=14.0, help="判为背景的颜色距离")
    s.add_argument("--band", type=int, default=2, help="边缘未知带宽度（像素）")
    s.add_argument("--min-area", type=int, default=40, help="小于该面积的连通块删除")
    s.add_argument("--shrink", type=float, default=0.0, help="alpha 收缩 0..0.5，去残余光晕")
    s.set_defaults(fn=cmd_matte)

    s = sp.add_parser("suggest-bg", help="为一个已抠图部件建议生成时的背景色")
    s.add_argument("input"); s.set_defaults(fn=cmd_suggest_bg)

    s = sp.add_parser("cutout", help="从参考图按多边形抠出原画部件或上层图层")
    s.add_argument("--ref", required=True); s.add_argument("--output", required=True)
    s.add_argument("--include", action="append", help="多边形 'x,y;x,y;…'，可重复；省略则取整个前景")
    s.add_argument("--exclude", action="append", help="要排除的多边形（如遮挡物），可重复")
    s.add_argument("--no-matte", action="store_true", help="参考图已是透明 PNG 时不再抠白底")
    s.add_argument("--preview", help="输出描边叠加图，检查多边形是否正确")
    s.add_argument("--preview-with", action="append", help="预览里一起描出的其他 RGBA 层")
    s.set_defaults(fn=cmd_cutout)

    s = sp.add_parser("task", help="生成整部件生成任务包（参考图 + 提示词 + part.json）")
    s.add_argument("--ref", required=True); s.add_argument("--out", required=True)
    s.add_argument("--part", required=True, help="部件名，如 arm-l"); s.add_argument("--desc", required=True, help="给生成工具的部件描述（英文）")
    s.add_argument("--region", required=True, help="部件在参考图中的大致框 x0,y0,x1,y1")
    s.add_argument("--side", choices=["none", "left", "right"], default="none", help="角色自己的左右")
    s.add_argument("--original", help="原画抠图（画布大小 RGBA），用于补全指标与背景色建议")
    s.add_argument("--upper", action="append", help="遮挡该部件的上层图层（画布大小 RGBA），可重复")
    s.add_argument("--bg", default="auto"); s.add_argument("--margin", type=float, default=0.35)
    s.add_argument("--hidden-hint", default="e.g. under sleeves, skirts or hair")
    s.add_argument("--hidden-extent", default="smoothly to its natural root/attachment point")
    s.add_argument("--cast-examples", default="shadows, garter straps, skirt shadow")
    s.add_argument("--min-size", type=int, default=1536)
    s.add_argument("--rule", action="append", help="追加到提示词的规则，可重复")
    s.set_defaults(fn=cmd_task)

    s = sp.add_parser("ingest", help="导回生成结果：抠图 → 对齐 → 补全指标 → 对比图")
    s.add_argument("--task", required=True); s.add_argument("--result", required=True); s.add_argument("--out")
    s.add_argument("--mirror", action="store_true", help="先水平镜像候选图（用另一侧的图）")
    s.add_argument("--rot-limit", type=float, default=0.0, help="允许的最大旋转（度），默认锁定为 0")
    s.add_argument("--scale-min", type=float, default=0.35); s.add_argument("--scale-max", type=float, default=3.0)
    s.add_argument("--n-scales", type=int, default=18)
    s.add_argument("--init", help="手工初值 scale,cx,cy（候选像素→画布倍率，候选外包框中心落点）")
    s.add_argument("--fully-visible", action="store_true", help="声明该部件没有被遮挡，豁免 NOT_COMPLETED")
    s.add_argument("--lower", action="append", help="位于该部件之下的其他层（用于 composite_fidelity），自下而上")
    s.set_defaults(fn=cmd_ingest)

    s = sp.add_parser("align", help="单独对齐一张候选图到参考图")
    s.add_argument("--ref", required=True); s.add_argument("--candidate", required=True)
    s.add_argument("--original", required=True); s.add_argument("--output", required=True)
    s.add_argument("--upper", action="append")
    s.add_argument("--mirror", action="store_true"); s.add_argument("--rot-limit", type=float, default=0.0)
    s.add_argument("--scale-min", type=float, default=0.35); s.add_argument("--scale-max", type=float, default=3.0)
    s.add_argument("--n-scales", type=int, default=18); s.add_argument("--init")
    s.set_defaults(fn=cmd_align)

    s = sp.add_parser("metrics", help="补全指标：overlap_fidelity 与 completion_ratio 及诊断")
    s.add_argument("--original", required=True); s.add_argument("--new", required=True)
    s.add_argument("--upper", action="append"); s.add_argument("--lower", action="append")
    s.add_argument("--ref"); s.add_argument("--fully-visible", action="store_true")
    for k in ("min_fidelity", "completion_eps", "hidden_boundary_min", "spill_max", "style_max", "seam_max"):
        s.add_argument("--" + k.replace("_", "-"), type=float, dest=k)
    s.set_defaults(fn=cmd_metrics)

    s = sp.add_parser("compare", help="并排对比图：参考 | 候选 | 单色叠加 | 误差热力")
    s.add_argument("--ref", required=True); s.add_argument("--new", required=True); s.add_argument("--output", required=True)
    s.add_argument("--original"); s.add_argument("--upper", action="append")
    s.set_defaults(fn=cmd_compare)

    s = sp.add_parser("repair-task", help="局部修复任务包（保留色遮罩）")
    s.add_argument("--ref", required=True); s.add_argument("--mask", required=True)
    s.add_argument("--box", required=True); s.add_argument("--out", required=True)
    s.add_argument("--desc", default="a small defect or hidden area"); s.add_argument("--fill", default="#FF00FF")
    s.add_argument("--pad", type=int, default=24)
    s.set_defaults(fn=cmd_repair_task)

    s = sp.add_parser("repair-paste", help="局部修复导回：遮罩外标定配准，只取遮罩内新像素")
    s.add_argument("--task", required=True); s.add_argument("--result", required=True); s.add_argument("--output", required=True)
    s.add_argument("--band", type=int, default=4); s.add_argument("--no-match", action="store_true")
    s.set_defaults(fn=cmd_repair_paste)

    s = sp.add_parser("composite", help="按 spec 合成；--hide 隐藏指定层")
    s.add_argument("--spec", required=True); s.add_argument("--output", required=True); s.add_argument("--hide", action="append")
    s.set_defaults(fn=cmd_composite)

    s = sp.add_parser("psd", help="按 spec 写分层 PSD（需要 psd-tools）")
    s.add_argument("--spec", required=True); s.add_argument("--output", required=True)
    s.set_defaults(fn=cmd_psd)

    s = sp.add_parser("coverage", help="覆盖缺口：参考前景中没有被任何层覆盖的区域")
    s.add_argument("--spec", required=True); s.add_argument("--ref", required=True); s.add_argument("--out", required=True)
    s.set_defaults(fn=cmd_coverage)

    s = sp.add_parser("exposure", help="逐层隐藏后露底的位置")
    s.add_argument("--spec", required=True); s.add_argument("--out", required=True); s.add_argument("--only", action="append")
    s.set_defaults(fn=cmd_exposure)

    s = sp.add_parser("selftest", help="合成数据自检（不需要任何外部图片）")
    s.add_argument("--out", default=None)
    s.set_defaults(fn=cmd_selftest)

    a = p.parse_args(argv)
    a.fn(a)


if __name__ == "__main__":
    main()
