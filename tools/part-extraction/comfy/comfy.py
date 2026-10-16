"""A ComfyUI client using only Python's standard library: upload images, queue a workflow, download its images.

The server and its token come from the environment, never from a file in the repository:
PSD2LIVE_COMFY_URL (for example http://127.0.0.1:8188) and, when the server asks for one, PSD2LIVE_COMFY_TOKEN.
"""

from __future__ import annotations

import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path


class ComfyError(RuntimeError):
    pass


class Comfy:
    def __init__(self, url: str | None = None, token: str | None = None, timeout: float = 600):
        self.url = (url or os.environ.get("PSD2LIVE_COMFY_URL") or "").rstrip("/")
        if not self.url:
            raise ComfyError("Set PSD2LIVE_COMFY_URL to the ComfyUI server")
        self.token = token if token is not None else os.environ.get("PSD2LIVE_COMFY_TOKEN")
        self.timeout = timeout

    def _request(self, path: str, data: bytes | None = None, headers: dict[str, str] | None = None, tries: int = 4) -> bytes:
        merged = dict(headers or {})
        if self.token:
            merged["Authorization"] = f"Bearer {self.token}"
        for attempt in range(tries):
            try:
                with urllib.request.urlopen(urllib.request.Request(self.url + path, data=data, headers=merged), timeout=self.timeout) as response:
                    return response.read()
            except urllib.error.HTTPError as error:
                raise ComfyError(f"{path}: HTTP {error.code} {error.read().decode('utf-8', 'replace')[:2000]}") from error
            except (urllib.error.URLError, TimeoutError, OSError) as error:
                # Proxies in front of a home server drop connections now and then.
                if attempt == tries - 1:
                    raise ComfyError(f"{path}: {error}") from error
                time.sleep(2 + 3 * attempt)
        raise AssertionError

    def get(self, path: str) -> object:
        return json.loads(self._request(path))

    def upload(self, path: str | Path, name: str | None = None) -> str:
        """Upload an image to the server's input folder, replacing one of the same name; returns the name to load it by."""
        path = Path(path)
        name = name or path.name
        boundary = "----psd2live" + uuid.uuid4().hex
        body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"image\"; filename=\"{name}\"\r\n"
                f"Content-Type: image/png\r\n\r\n").encode() + path.read_bytes()
        body += f"\r\n--{boundary}\r\nContent-Disposition: form-data; name=\"overwrite\"\r\n\r\ntrue\r\n--{boundary}--\r\n".encode()
        reply = json.loads(self._request("/upload/image", body, {"Content-Type": f"multipart/form-data; boundary={boundary}"}))
        return f"{reply['subfolder']}/{reply['name']}" if reply.get("subfolder") else reply["name"]

    def run(self, workflow: dict, out_dir: str | Path, prefix: str, timeout: float = 1800) -> list[Path]:
        """Queue [workflow] (API format), wait for it, and save the images it wrote as <prefix>_<file> in [out_dir]."""
        queued = json.loads(self._request("/prompt", json.dumps({"prompt": workflow, "client_id": "psd2live"}).encode(),
                                          {"Content-Type": "application/json"}))
        prompt = queued["prompt_id"]
        started = time.time()
        while True:
            history = self.get(f"/history/{prompt}")
            if prompt in history:
                entry = history[prompt]
                status = entry.get("status", {})
                if status.get("status_str") == "error":
                    raise ComfyError(json.dumps(status.get("messages"))[-3000:])
                if status.get("completed"):
                    break
            if time.time() - started > timeout:
                raise ComfyError(f"Workflow {prompt} did not finish in {timeout:.0f} s")
            time.sleep(2)
        out = Path(out_dir)
        out.mkdir(parents=True, exist_ok=True)
        saved = []
        for node in entry["outputs"].values():
            for image in node.get("images", []):
                if image.get("type") != "output":
                    continue
                query = urllib.parse.urlencode({"filename": image["filename"], "subfolder": image["subfolder"], "type": "output"})
                target = out / f"{prefix}_{image['filename']}"
                target.write_bytes(self._request(f"/view?{query}"))
                saved.append(target)
        return saved
