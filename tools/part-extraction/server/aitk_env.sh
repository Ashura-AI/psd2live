# ai-toolkit in its own venv over the system PyTorch, so its pins (transformers 5.x, huggingface_hub 1.x) leave ComfyUI alone.
# scipy is left to the system copy (the 1.12 pin wants numpy < 2.3) and opencv is pinned, so pip does not backtrack.
set -e
cd /root/autodl-tmp
[ -d aitk-venv ] || /root/miniconda3/bin/python -m venv --system-site-packages aitk-venv
P="/root/autodl-tmp/aitk-venv/bin/pip"
I="-i https://pypi.tuna.tsinghua.edu.cn/simple --timeout 120 --retries 10"
grep -v "^torchao\|^torchcodec\|^librosa\|^mutagen\|^gradio\|^git+\|^#\|^\s*$\|^fastapi\|^uvicorn\|^opencv" ai-toolkit/requirements_base.txt | sed "s/ *#.*//" > /tmp/aitk_base.txt
$P install $I -r /tmp/aitk_base.txt opencv-python==4.11.0.86 opencv-python-headless==4.11.0.86
(. /etc/network_turbo >/dev/null; $P install --timeout 120 --retries 10 "git+https://github.com/huggingface/diffusers.git@c943837899b16cbae2f619b8dd4f7bb6f07dd81a")
/root/autodl-tmp/aitk-venv/bin/python -c "import torch, diffusers, transformers, peft, cv2; print('aitk env ok', torch.__version__, diffusers.__version__, transformers.__version__)"
