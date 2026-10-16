"""Runs the part-extraction prompts on test close-ups with and without a LoRA and lays the results out in sheets.

usage: python evaluate.py LORA_NAME OUT_DIR IMAGE[:WxH] ...   (LORA_NAME relative to the server's loras folder)
"""
import os
import sys
from pathlib import Path

from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).parent))
import l2d
from env import client

PROMPTS = {
    "iris": "Live2D layer split: keep only the iris layer, the whole iris with its pupil and highlights drawn complete, "
            "including the part hidden under the eyelid, exactly in place, on a flat gray background.",
    "white": "Live2D layer split: keep only the eye white layer, its complete shape including the part under the "
             "eyelashes, exactly in place, on a flat gray background.",
    "lash": "Live2D layer split: keep only the eyelid layers, the eyelashes, lid lines and eyelid skin, exactly in place, "
            "on a flat gray background.",
    "brow": "Live2D layer split: keep only the eyebrow layer, exactly in place, on a flat gray background.",
}


BASE = os.environ.get("L2D_BASE", "flux-2-klein-base-4b-fp8.safetensors")


def run(lora: str | None, image: Path, size: tuple[int, int], out: Path, tag: str, steps: int = 4, seed: int = 1,
        unet: str = l2d.UNET, cfg: float = 1.0) -> dict:
    c = client(timeout=900)
    src = out / f"{image.stem}_{size[0]}x{size[1]}.png"
    if not src.exists():
        Image.open(image).convert("RGB").resize(size, Image.LANCZOS).save(src)
    name = c.upload(src, f"l2d_eval_{src.name}")
    results = {}
    for part, prompt in PROMPTS.items():
        files = c.run(l2d.edit_graph(name, prompt, size[0], size[1], lora=lora, steps=steps, seed=seed, unet=unet, cfg=cfg,
                                     name=f"eval_{tag}_{image.stem}_{part}"), out, f"{tag}_{src.stem}_{part}")
        results[part] = files[0]
    return {"input": src, **results}


def sheet(rows: list[tuple[str, dict]], path: Path, cell: int = 300) -> None:
    kinds = ["input", "iris", "white", "lash", "brow"]
    out = Image.new("RGB", (len(kinds) * (cell + 4) + 120, len(rows) * (cell + 4)), (255, 255, 255))
    draw = ImageDraw.Draw(out)
    for r, (label, files) in enumerate(rows):
        draw.text((4, r * (cell + 4) + cell // 2), label, fill=(0, 0, 0))
        for k, kind in enumerate(kinds):
            if kind in files:
                im = Image.open(files[kind]).convert("RGB")
                im.thumbnail((cell, cell), Image.LANCZOS)
                out.paste(im, (120 + k * (cell + 4), r * (cell + 4)))
    out.save(path)


if __name__ == "__main__":
    lora, out = sys.argv[1], Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    rows = []
    for arg in sys.argv[3:]:
        path, _, wh = arg.rpartition(":")
        if not wh.replace("x", "").isdigit():
            path, wh = arg, ""
        image = Path(path)
        size = tuple(int(v) for v in wh.split("x")) if wh else Image.open(image).size
        rows.append((f"{image.stem[:14]}\nLoRA 4 steps", run(lora, image, size, out, "lora")))
        rows.append((f"{image.stem[:14]}\nLoRA on base", run(lora, image, size, out, "lorabase", steps=24, unet=BASE, cfg=4.0)))
        rows.append((f"{image.stem[:14]}\nno LoRA", run(None, image, size, out, "base")))
        if image.stem.endswith("_input"):
            truth = {part: image.with_name(image.stem[:-len("input")] + part + ".png") for part in PROMPTS}
            rows.append((f"{image.stem[:14]}\ntruth", {"input": image, **{k: v for k, v in truth.items() if v.exists()}}))
    sheet(rows, out / "sheet.png")
    print(out / "sheet.png")
