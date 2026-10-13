"""Regression tests for pinned download and extraction integrity."""

import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile

import fetch


class FetchTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def bundle(self, contents):
        archive = self.root / "bundle.zip"
        with zipfile.ZipFile(archive, "w") as output:
            for name, data in contents.items():
                output.writestr(name, data)
        fixture = {
            "id": "sample", "version": "1.0.0",
            "archive": {"url": "https://example.org/sample.zip", "size_bytes": archive.stat().st_size,
                        "sha256": fetch.digest(archive)},
            "files": [{"path": name, "size_bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
                      for name, data in contents.items()],
        }
        return fixture, archive

    def test_verified_cache_can_be_used_without_network(self):
        fixture, archive = self.bundle({"sample/input.psd": b"input"})
        cache = self.root / "cache"
        cache.mkdir()
        archive.replace(cache / (fixture["archive"]["sha256"] + ".zip"))
        with patch("urllib.request.urlopen", side_effect=AssertionError("Unexpected network")):
            cached = fetch.fetch_archive(fixture, cache)
            fetch.install(fixture, cached, self.root / "examples")
        self.assertEqual(b"input", (self.root / "examples/sample/input.psd").read_bytes())

    def test_corrupt_archive_is_rejected(self):
        fixture, archive = self.bundle({"sample/input.psd": b"input"})
        archive.write_bytes(b"corrupt")
        with self.assertRaisesRegex(ValueError, "archive differs"):
            fetch.install(fixture, archive, self.root / "examples")
        self.assertFalse((self.root / "examples/sample/input.psd").exists())

    def test_bad_file_checksum_does_not_publish_any_files(self):
        fixture, archive = self.bundle({"sample/first.psd": b"first", "sample/second.psd": b"second"})
        fixture["files"][1]["sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "checksum differs"):
            fetch.install(fixture, archive, self.root / "examples")
        self.assertFalse((self.root / "examples/sample/first.psd").exists())

    def test_traversal_paths_are_rejected(self):
        for name in ["sample/../escape", "sample/../../escape", "/sample/file", "sample\\file", "other/file", "sample/CON"]:
            with self.subTest(name=name), self.assertRaises(ValueError):
                fetch.relative_path(name, "sample")

    def test_extra_entries_are_rejected(self):
        fixture, archive = self.bundle({"sample/input.psd": b"input"})
        with zipfile.ZipFile(archive, "a") as output:
            output.writestr("sample/extra.psd", b"extra")
        fixture["archive"].update(size_bytes=archive.stat().st_size, sha256=fetch.digest(archive))
        with self.assertRaisesRegex(ValueError, "entries differ"):
            fetch.install(fixture, archive, self.root / "examples")

    def test_duplicate_entries_are_rejected(self):
        fixture, archive = self.bundle({"sample/input.psd": b"input"})
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(archive, "a") as output:
                output.writestr("sample/input.psd", b"input")
        fixture["archive"].update(size_bytes=archive.stat().st_size, sha256=fetch.digest(archive))
        with self.assertRaisesRegex(ValueError, "entries differ"):
            fetch.install(fixture, archive, self.root / "examples")

    def test_symlink_destination_is_rejected(self):
        fixture, archive = self.bundle({"sample/input.psd": b"input"})
        destination = self.root / "examples"
        outside = self.root / "outside"
        destination.mkdir()
        outside.mkdir()
        try:
            (destination / "sample").symlink_to(outside, target_is_directory=True)
        except OSError:
            self.skipTest("Symlink creation unavailable")
        with self.assertRaisesRegex(ValueError, "Destination escapes"):
            fetch.install(fixture, archive, destination)
        self.assertFalse((outside / "input.psd").exists())


if __name__ == "__main__":
    unittest.main()
