"""Fail acceptance if any expected example export variant is missing."""

from pathlib import Path


def check(path=Path("build/tools/export-golden/acceptance.txt")):
    lines = path.read_text(encoding="utf-8").splitlines()
    for case in ["tml", "ds"]:
        for variant in ["plain", "skeleton", "clip", "imported"]:
            prefix = f"{case}-{variant} "
            outputs = [line[len(prefix):] for line in lines if line.startswith(prefix)]
            for suffix in [".cmo3", ".moc3", ".model3.json"]:
                if not any(line.split(" ", 1)[0].endswith(suffix) for line in outputs):
                    raise ValueError(f"Missing {case}-{variant} {suffix} export")
            if not any(line.startswith("preview:") for line in outputs):
                raise ValueError(f"Missing {case}-{variant} runtime preview bundle")
    print("Verified reports for two cases and four export/import variants each")


if __name__ == "__main__":
    check()
