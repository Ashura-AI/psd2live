"""Reads the user's ComfyUI address and token from the local memory note into a Comfy client; never prints the token."""
import os, re, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
from comfy import Comfy

NOTE = Path(r"C:\Users\whx12\.claude\projects\D--code-live2d-psd2live\memory\comfyui-server.md")

def client(timeout: float = 600) -> Comfy:
    if os.environ.get("L2D_COMFY_URL"):  # the rented GPU, reached through an SSH tunnel, needs no token
        return Comfy(url=os.environ["L2D_COMFY_URL"], token="", timeout=timeout)
    text = NOTE.read_text(encoding="utf-8")
    url = re.search(r"Address:\s*(\S+)", text).group(1)
    token = re.search(r"Bearer\s+([^`\s]+)", text).group(1)
    return Comfy(url=url, token=token, timeout=timeout)
