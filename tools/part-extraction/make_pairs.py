"""Assemble (close-up -> one part, in place) training pairs from the PartDatasetTool renders.

Input: one or more PartDatasetTool render folders (samples.jsonl and the PNGs beside it), comma separated. A model
found in more than one collection (same default-pose render) is used once, and never for training when one copy is
held out.
Output: <out>/train_in, <out>/train_tgt (same file names; .txt prompts beside the targets), <out>/test/<name>_{input,<part>}.png.
Held-out characters never appear in training.
"""
import hashlib
import json
import random
import sys
from pathlib import Path

import numpy as np
from PIL import Image

PROMPTS = {
    "iris": "Live2D layer split: keep only the iris layer, the whole iris with its pupil and highlights drawn complete, "
            "including the part hidden under the eyelid, exactly in place, on a flat gray background.",
    "white": "Live2D layer split: keep only the eye white layer, its complete shape including the part under the "
             "eyelashes, exactly in place, on a flat gray background.",
    "lash": "Live2D layer split: keep only the eyelid layers, the eyelashes, lid lines and eyelid skin, exactly in place, "
            "on a flat gray background.",
    "brow": "Live2D layer split: keep only the eyebrow layer, exactly in place, on a flat gray background.",
    "lips": "Live2D layer split: keep only the lips and mouth line layers, exactly in place, on a flat gray background.",
    "inside": "Live2D layer split: keep only the inside of the mouth, the teeth, tongue and mouth interior drawn complete, "
              "including the parts hidden by the lips, exactly in place, on a flat gray background.",
    "front": "Live2D layer split: keep only the front hair layers, drawn complete, exactly in place, on a flat gray background.",
    "side": "Live2D layer split: keep only the side hair layers, drawn complete, exactly in place, on a flat gray background.",
    "back": "Live2D layer split: keep only the back hair layers, drawn complete, including the parts hidden behind the head "
            "and body, exactly in place, on a flat gray background.",
}
GRAY = (128, 128, 128)


def character(model: str) -> str:
    """Costume variants of one character share a face: aria and aria_0100, nene_0001..3, alisa_s1..s3."""
    folder = model.split("/")[-2]
    for sep in ("_s", "_0"):
        if sep in folder:
            folder = folder.split(sep)[0]
    return model.split("/")[0] + "/" + folder.lower()


def hue_shift(img: Image.Image, shift: float) -> Image.Image:
    """Rotate hue of the colour channels, alpha untouched."""
    a = np.asarray(img.convert("RGBA")).astype(np.float32) / 255
    rgb = a[..., :3]
    mx, mn = rgb.max(-1), rgb.min(-1)
    d = mx - mn + 1e-8
    r, g, b = rgb[..., 0], rgb[..., 1], rgb[..., 2]
    h = np.where(mx == r, ((g - b) / d) % 6, np.where(mx == g, (b - r) / d + 2, (r - g) / d + 4)) / 6
    h = (h + shift) % 1.0
    s = np.where(mx > 0, d / (mx + 1e-8), 0)
    v = mx
    i = np.floor(h * 6).astype(int) % 6
    f = h * 6 - np.floor(h * 6)
    p, q, t = v * (1 - s), v * (1 - s * f), v * (1 - s * (1 - f))
    out = np.stack([
        np.choose(i, [v, q, p, p, t, v]), np.choose(i, [t, v, v, q, p, p]), np.choose(i, [p, p, t, v, v, q])], -1)
    out = np.where((d < 1e-6)[..., None], rgb, out)
    a[..., :3] = out
    return Image.fromarray((a * 255 + 0.5).clip(0, 255).astype(np.uint8), "RGBA")


def over(img: Image.Image, color) -> Image.Image:
    bg = Image.new("RGBA", img.size, color + (255,))
    bg.alpha_composite(img)
    return bg.convert("RGB")


def fingerprint(src: Path, s: dict) -> str | None:
    """The model's identity: its default-pose close-up, which renders the same wherever the model is stored."""
    for first in ("0", "m0", "h0"):  # eye renders start at 0, head renders at m0 (mouth) and h0 (hair)
        path = src / f"{s['key']}/{s['side']}_{first}_input.png"
        if path.exists():
            return hashlib.md5(path.read_bytes()).hexdigest()
    return None


def main(srcs: list[Path], out: Path, size: int, min_pixels: int, held: list[str], seed: int = 0):
    rng = random.Random(seed)
    samples = []
    for src in srcs:
        samples += [dict(json.loads(l), src=src) for l in (src / "samples.jsonl").read_text(encoding="utf-8").splitlines() if l.strip()]
    # A model is identified by its default-pose renders; the first collection holding it owns it, and a held-out
    # copy anywhere keeps every copy out of training.
    owner, held_prints = {}, set()
    for s in samples:
        f = fingerprint(s["src"], s)
        s["print"] = f
        if f is None:
            continue
        owner.setdefault(f, (s["src"], s["key"]))
        if any(character(s["model"]).startswith(h) for h in held):
            held_prints.add(f)
    duplicates = 0
    for d in ("train_in", "train_tgt", "test"):
        (out / d).mkdir(parents=True, exist_ok=True)
    n_train = n_test = skipped = 0
    stats = {}
    for s in samples:
        if s["pixels"] < min_pixels:
            skipped += 1
            continue
        src = s["src"]
        if s["print"] is not None and owner[s["print"]] != (src, s["key"]):
            duplicates += 1
            continue
        stem = f"{s['key']}/{s['side']}_{s['variant']}"
        side = min(size, max(192, s["pixels"] // 64 * 64))
        test = any(character(s["model"]).startswith(h) for h in held)
        if not test and s["print"] in held_prints:
            continue
        inp = Image.open(src / f"{stem}_input.png").convert("RGBA")
        bg = tuple(rng.randint(200, 250) for _ in range(3))
        for part in PROMPTS:
            path = src / f"{stem}_{part}.png"
            if not path.exists():
                continue
            tgt = Image.open(path).convert("RGBA")
            if np.asarray(tgt)[..., 3].max() < 32:
                continue
            if test:
                name = f"{s['key']}_{s['side']}_{s['variant']}"
                over(inp, bg).resize((side, side), Image.LANCZOS).save(out / "test" / f"{name}_input.png")
                over(tgt, GRAY).resize((side, side), Image.LANCZOS).save(out / "test" / f"{name}_{part}.png")
                n_test += 1
                continue
            a, b = inp, tgt
            if rng.random() < 0.5:
                a, b = a.transpose(Image.FLIP_LEFT_RIGHT), b.transpose(Image.FLIP_LEFT_RIGHT)
            if rng.random() < 0.5:
                shift = rng.random()
                a, b = hue_shift(a, shift), hue_shift(b, shift)
            name = f"{n_train:05d}"
            over(a, bg).resize((side, side), Image.LANCZOS).save(out / "train_in" / f"{name}.png")
            over(b, GRAY).resize((side, side), Image.LANCZOS).save(out / "train_tgt" / f"{name}.png")
            (out / "train_tgt" / f"{name}.txt").write_text(PROMPTS[part], encoding="utf-8")
            stats[part] = stats.get(part, 0) + 1
            n_train += 1
    print(f"train {n_train} {stats}, test {n_test}, skipped {skipped} small renders, {duplicates} duplicate renders")


if __name__ == "__main__":
    main([Path(p) for p in sys.argv[1].split(",")], Path(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4]), sys.argv[5].split(","))
