"""Fetch pinned example bundles using only Python's standard library."""

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import tempfile
import urllib.request
import zipfile


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def relative_path(value, case_id):
    path = PurePosixPath(value)
    if (not isinstance(value, str) or "\\" in value or ":" in value
            or path.is_absolute() or ".." in path.parts
            or len(path.parts) < 2 or path.parts[0] != case_id
            or str(path) != value):
        raise ValueError(f"Invalid bundle path: {value}")
    for part in path.parts:
        if part.endswith((".", " ")) or re.match(r"(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])(\.|$)", part):
            raise ValueError(f"Invalid bundle path: {value}")
    return path


def validate_fixture(fixture):
    case_id = fixture["id"]
    if not re.fullmatch(r"[a-z0-9][a-z0-9_-]*", case_id):
        raise ValueError("Invalid case ID")
    archive = fixture["archive"]
    if not archive["url"].startswith("https://"):
        raise ValueError("Asset URL must use HTTPS")
    for record in [archive, *fixture["files"]]:
        if not re.fullmatch(r"[0-9a-f]{64}", record["sha256"]):
            raise ValueError("Invalid SHA-256")
        if type(record["size_bytes"]) is not int or record["size_bytes"] <= 0:
            raise ValueError("Invalid asset size")
    names = [str(relative_path(item["path"], case_id)).casefold() for item in fixture["files"]]
    if not names or len(names) != len(set(names)):
        raise ValueError("Missing or duplicate bundle files")


def fetch_archive(fixture, cache):
    archive = fixture["archive"]
    cache.mkdir(parents=True, exist_ok=True)
    target = cache / (archive["sha256"] + ".zip")
    if target.is_file():
        if target.stat().st_size == archive["size_bytes"] and digest(target) == archive["sha256"]:
            return target
        target.unlink()
    with tempfile.NamedTemporaryFile(dir=cache, suffix=".partial", delete=False) as stream:
        temporary = Path(stream.name)
        try:
            request = urllib.request.Request(archive["url"], headers={"User-Agent": "psd2live-examples"})
            with urllib.request.urlopen(request, timeout=120) as response:
                if not response.geturl().startswith("https://"):
                    raise ValueError("Asset redirect must use HTTPS")
                size = 0
                while chunk := response.read(1024 * 1024):
                    size += len(chunk)
                    if size > archive["size_bytes"]:
                        raise ValueError("Downloaded asset exceeds locked size")
                    stream.write(chunk)
            stream.close()
            if size != archive["size_bytes"] or digest(temporary) != archive["sha256"]:
                raise ValueError("Downloaded asset differs from the lock")
            os.replace(temporary, target)
        finally:
            stream.close()
            temporary.unlink(missing_ok=True)
    return target


def install(fixture, archive_path, destination):
    validate_fixture(fixture)
    archive = fixture["archive"]
    if archive_path.stat().st_size != archive["size_bytes"] or digest(archive_path) != archive["sha256"]:
        raise ValueError("Cached archive differs from the lock")
    destination = destination.resolve()
    destination.mkdir(parents=True, exist_ok=True)
    expected = {record["path"]: record for record in fixture["files"]}
    with tempfile.TemporaryDirectory(prefix=".example-staging-", dir=destination.parent) as staging:
        staging = Path(staging)
        with zipfile.ZipFile(archive_path) as bundle:
            members = bundle.infolist()
            if len(members) != len(expected) or {item.filename for item in members} != set(expected):
                raise ValueError("Archive entries differ from the lock")
            for member in members:
                relative = relative_path(member.filename, fixture["id"])
                mode = member.external_attr >> 16
                if stat.S_ISLNK(mode) or member.is_dir():
                    raise ValueError("Bundle entries must be regular files")
                record = expected[member.filename]
                if member.file_size != record["size_bytes"]:
                    raise ValueError("Bundle file size differs from the lock")
                target = staging.joinpath(*relative.parts)
                target.parent.mkdir(parents=True, exist_ok=True)
                with bundle.open(member) as source, target.open("wb") as output:
                    shutil.copyfileobj(source, output)
                if digest(target) != record["sha256"]:
                    raise ValueError(f"Bundle file checksum differs: {member.filename}")
        # Verify every file before publishing any files into the destination.
        targets = []
        for name in expected:
            target = destination.joinpath(*PurePosixPath(name).parts)
            if not target.resolve().is_relative_to(destination):
                raise ValueError("Destination escapes the example directory")
            targets.append((staging / name, target))
        for source, target in targets:
            target.parent.mkdir(parents=True, exist_ok=True)
            os.replace(source, target)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lock", type=Path, default=Path(__file__).with_name("fixtures.lock.json"))
    parser.add_argument("--case", action="append", dest="cases", help="Case ID; may be repeated (default: tml)")
    parser.add_argument("--all", action="store_true", help="Download all locked cases")
    parser.add_argument("--destination", type=Path, default=Path("examples"))
    parser.add_argument("--cache", type=Path, default=Path("build/example-assets"))
    arguments = parser.parse_args()
    lock = json.loads(arguments.lock.read_text(encoding="utf-8"))
    if lock["schema_version"] != 1:
        raise ValueError("Unsupported fixture lock version")
    fixtures = {fixture["id"]: fixture for fixture in lock["fixtures"]}
    if len(fixtures) != len(lock["fixtures"]):
        raise ValueError("Duplicate fixture IDs")
    selected = list(fixtures) if arguments.all else arguments.cases or ["tml"]
    if set(selected) - fixtures.keys():
        parser.error("Unknown case IDs: " + ", ".join(sorted(set(selected) - fixtures.keys())))
    for case_id in selected:
        fixture = fixtures[case_id]
        validate_fixture(fixture)
        archive = fetch_archive(fixture, arguments.cache)
        install(fixture, archive, arguments.destination)
        print(f"Verified {case_id}@{fixture['version']}: {len(fixture['files'])} files")


if __name__ == "__main__":
    main()
