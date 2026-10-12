"""公共函数：图像读写、颜色空间、仿射变换、几何辅助。"""
from __future__ import annotations

import json
import math
import re

import cv2
import numpy as np
from PIL import Image


def load_rgba(path) -> np.ndarray:
    return np.array(Image.open(path).convert("RGBA"), dtype=np.uint8)


def save_rgba(arr: np.ndarray, path) -> None:
    Image.fromarray(np.ascontiguousarray(arr.astype(np.uint8)), "RGBA").save(path)


def save_rgb(arr: np.ndarray, path) -> None:
    Image.fromarray(np.ascontiguousarray(arr.astype(np.uint8)), "RGB").save(path)


def alpha01(rgba: np.ndarray) -> np.ndarray:
    return rgba[..., 3].astype(np.float32) / 255.0


def over(rgba: np.ndarray, bg=(255, 255, 255)) -> np.ndarray:
    """RGBA 合成到纯色背景，返回 RGB uint8。"""
    a = rgba[..., 3:4].astype(np.float32) / 255.0
    rgb = rgba[..., :3].astype(np.float32) * a + np.array(bg, np.float32) * (1 - a)
    return np.clip(rgb + 0.5, 0, 255).astype(np.uint8)


def to_lab(rgb_u8: np.ndarray) -> np.ndarray:
    """RGB uint8 → Lab float32（L 0..100）。"""
    return cv2.cvtColor(rgb_u8.astype(np.float32) / 255.0, cv2.COLOR_RGB2LAB)


def premult_blur(rgba: np.ndarray, sigma: float) -> np.ndarray:
    """按 alpha 加权的颜色模糊，避免透明像素的颜色污染边缘。返回 RGB float32。"""
    a = alpha01(rgba)
    rgb = rgba[..., :3].astype(np.float32)
    num = cv2.GaussianBlur(rgb * a[..., None], (0, 0), sigma)
    den = cv2.GaussianBlur(a, (0, 0), sigma)[..., None]
    return np.where(den > 1e-4, num / np.maximum(den, 1e-4), rgb)


def hex_to_rgb(s: str) -> tuple[int, int, int]:
    s = s.strip().lstrip("#")
    if not re.fullmatch(r"[0-9a-fA-F]{6}", s):
        raise ValueError(f"颜色应为 #RRGGBB: {s}")
    return int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16)


def rgb_to_hex(c) -> str:
    return "#%02X%02X%02X" % tuple(int(round(v)) for v in c)


def parse_box(s: str) -> tuple[int, int, int, int]:
    x0, y0, x1, y1 = [int(round(float(v))) for v in re.split(r"[,\s]+", s.strip())]
    return x0, y0, x1, y1


def parse_polygon(s: str) -> np.ndarray:
    """'x,y;x,y;...' 或 'x,y x,y ...'。"""
    pts = []
    for tok in re.split(r"[;\s]+", s.strip()):
        if tok:
            x, y = tok.split(",")
            pts.append((float(x), float(y)))
    if len(pts) < 3:
        raise ValueError("多边形至少 3 个顶点")
    return np.array(pts, np.float32)


def polygon_mask(size_wh, poly: np.ndarray) -> np.ndarray:
    """多边形光栅化为 float32 0/1，抗锯齿（4 倍超采样）。"""
    W, H = size_wh
    k = 4
    m = np.zeros((H * k, W * k), np.uint8)
    cv2.fillPoly(m, [np.round(poly * k).astype(np.int32)], 255)
    return cv2.resize(m, (W, H), interpolation=cv2.INTER_AREA).astype(np.float32) / 255.0


def bbox_of(mask: np.ndarray, thr: float = 0.5):
    ys, xs = np.where(mask > thr)
    if len(xs) == 0:
        return None
    return int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1


def affine3(M2x3: np.ndarray) -> np.ndarray:
    return np.vstack([M2x3, [0, 0, 1]]).astype(np.float64)


def scale3(s: float) -> np.ndarray:
    return np.array([[s, 0, 0], [0, s, 0], [0, 0, 1]], np.float64)


def trans3(tx: float, ty: float) -> np.ndarray:
    return np.array([[1, 0, tx], [0, 1, ty], [0, 0, 1]], np.float64)


def sim_matrix(scale: float, deg: float, cx: float, cy: float, pivot) -> np.ndarray:
    """候选图像素 p → 画布 dst = s·R(p − pivot) + (cx, cy)。返回 2x3。"""
    r = math.radians(deg)
    c, s_ = math.cos(r) * scale, math.sin(r) * scale
    px, py = pivot
    return np.array([[c, -s_, cx - (c * px - s_ * py)], [s_, c, cy - (s_ * px + c * py)]], np.float64)


def warp_rgba(rgba: np.ndarray, M2x3: np.ndarray, size_wh, interp=cv2.INTER_CUBIC) -> np.ndarray:
    """预乘 alpha 的仿射变换（避免边缘颜色污染）。M 为 源→目标。"""
    W, H = size_wh
    a = rgba[..., 3:4].astype(np.float32) / 255.0
    pm = np.concatenate([rgba[..., :3].astype(np.float32) * a, a * 255.0], axis=2)
    out = cv2.warpAffine(pm, np.asarray(M2x3, np.float64), (W, H), flags=interp,
                         borderMode=cv2.BORDER_CONSTANT, borderValue=0)
    oa = np.clip(out[..., 3:4], 0, 255)
    rgb = out[..., :3] / np.maximum(oa / 255.0, 1e-6)
    rgb = np.where(oa > 0.5, np.clip(rgb, 0, 255), 0)
    return np.concatenate([rgb, oa], axis=2).round().astype(np.uint8)


def jsonable(o):
    if isinstance(o, dict):
        return {str(k): jsonable(v) for k, v in o.items()}
    if isinstance(o, (list, tuple)):
        return [jsonable(v) for v in o]
    if isinstance(o, np.ndarray):
        return jsonable(o.tolist())
    if isinstance(o, (np.floating,)):
        v = float(o)
        return None if math.isnan(v) or math.isinf(v) else v
    if isinstance(o, float):
        return None if math.isnan(o) or math.isinf(o) else o
    if isinstance(o, (np.integer,)):
        return int(o)
    if isinstance(o, (np.bool_,)):
        return bool(o)
    return o


def dump(o, path=None) -> str:
    s = json.dumps(jsonable(o), ensure_ascii=False, indent=2)
    if path:
        with open(path, "w", encoding="utf-8") as f:
            f.write(s)
    return s
