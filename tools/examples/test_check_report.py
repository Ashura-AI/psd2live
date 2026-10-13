from pathlib import Path
import tempfile
import unittest

from check_report import check


class CheckReportTest(unittest.TestCase):
    def test_missing_variant_fails_acceptance(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "report.txt"
            path.write_text("tml-plain tml.cmo3 readback=abc\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "Missing"):
                check(path)

    def test_complete_report_passes(self):
        lines = []
        for case in ["tml", "ds"]:
            for variant in ["plain", "skeleton", "clip", "imported"]:
                for name in [f"{case}.cmo3", f"{case}.moc3", f"{case}.model3.json", "preview:bundle"]:
                    lines.append(f"{case}-{variant} {name} digest")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "report.txt"
            path.write_text("\n".join(lines), encoding="utf-8")
            check(path)


if __name__ == "__main__":
    unittest.main()
