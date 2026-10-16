"""One place to grow the part-extraction training set: render a collection, merge every rendered one, upload.

  python build_dataset.py render NAME ROOT PREFIX   render every *.model3.json / *.cmo3 under ROOT with PartDatasetTool
                                                   into build/tools/part-dataset/NAME, keys PREFIX000...; remembered in
                                                   collections.json
  python build_dataset.py list                      the collections and how many close-ups each gave
  python build_dataset.py pairs OUT [SIZE] [MIN]    merge every collection (duplicates once, held-out characters only in
                                                   the test set) into OUT/train_in, OUT/train_tgt, OUT/test
  python build_dataset.py upload OUT REMOTE         copy OUT's training pairs to the GPU box, /root/autodl-tmp/l2d/REMOTE
"""
import json
import os
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).parent
REPO = Path("D:/code/live2d/psd2live")
RENDERS = REPO / "build/tools/part-dataset"
REGISTRY = HERE / "collections.json"  # local, not committed
# The GPU box: L2D_GPU_SSH holds the ssh arguments (key, port, user@host), e.g. "-i KEY -p PORT root@HOST".
GPU_SSH = ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=20"] + os.environ.get("L2D_GPU_SSH", "").split()
# Characters kept out of training everywhere, so the test set measures transfer to unseen characters.
HELD = ["caracal", "mint", "Ergosum/kiharu", "灵魂潮汐国际服-倒闭二游/asuna",
        "booth-6687553-weather-girl", "booth-4499251-same-sister"]  # the last two: high resolution
DEFAULT = {"eyes": {"root": "D:/模型收藏/live2d", "prefix": "m"},
           "eyes_eik": {"root": "D:/模型收藏/live2d_github/Eikanya-Live2d-model", "prefix": "e"},
           "eyes_kis": {"root": "D:/模型收藏/live2d_github/KISGP-model", "prefix": "k"},
           "eyes_col": {"root": "D:/模型收藏/live2d_collected/live2d_models", "prefix": "c"}}


def registry() -> dict:
    return json.loads(REGISTRY.read_text(encoding="utf-8")) if REGISTRY.exists() else dict(DEFAULT)


def render(name: str, root: str, prefix: str, variants: int = 4, size: int = 1024, threads: int = 8) -> None:
    collections = registry()
    taken = {c["prefix"]: n for n, c in collections.items() if n != name}
    if prefix in taken:
        sys.exit(f"prefix {prefix} is already used by {taken[prefix]}")
    env = dict(os.environ, PSD2LIVE_TOOLS="1", PARTS_ROOT=root, PARTS_NAME=name, PARTS_PREFIX=prefix,
               PARTS_VARIANTS=str(variants), PARTS_SIZE=str(size), PARTS_THREADS=str(threads))
    gradle = str(REPO / ("gradlew.bat" if os.name == "nt" else "gradlew"))
    # --rerun: the tool's inputs are environment variables, which Gradle does not see as changes.
    subprocess.run([gradle, "test", "--tests", "*PartDatasetTool.eyes*", "--rerun", f"-Ppsd2live.testHeap={max(10, 3 * threads)}g",
                    "-Ppsd2live.testForks=1"], cwd=REPO, env=env, check=True)
    collections[name] = {"root": root, "prefix": prefix}
    REGISTRY.write_text(json.dumps(collections, ensure_ascii=False, indent=1), encoding="utf-8")
    show()


def show() -> None:
    for name, c in registry().items():
        samples = RENDERS / name / "samples.jsonl"
        count = sum(1 for l in samples.read_text(encoding="utf-8").splitlines() if l.strip()) if samples.exists() else 0
        print(f"{name:12} {c['prefix']:3} {count:6} close-ups  {c['root']}")


def pairs(out: str, size: int = 384, min_pixels: int = 128) -> None:
    sys.path.insert(0, str(HERE))
    import make_pairs
    import shutil
    sources = [RENDERS / n for n in registry() if (RENDERS / n / "samples.jsonl").exists()]
    for d in ("train_in", "train_tgt", "test"):  # numbering restarts at 0, so stale pairs would linger
        shutil.rmtree(Path(out) / d, ignore_errors=True)
    make_pairs.main(sources, Path(out), size, min_pixels, HELD)


def upload(out: str, remote: str) -> None:
    tar = subprocess.Popen(["tar", "-C", out, "-cf", "-", "train_in", "train_tgt"], stdout=subprocess.PIPE)
    target = f"/root/autodl-tmp/l2d/{remote}"
    subprocess.run(GPU_SSH + [f"rm -rf {target} && mkdir -p {target} && tar -C {target} -xf - && "
                    f"ls {target}/train_in | wc -l"], stdin=tar.stdout, check=True)
    tar.wait()


if __name__ == "__main__":
    command, args = sys.argv[1], sys.argv[2:]
    {"render": lambda: render(args[0], args[1], args[2]), "list": show,
     "pairs": lambda: pairs(args[0], *(int(a) for a in args[1:3])),
     "upload": lambda: upload(args[0], args[1])}[command]()
