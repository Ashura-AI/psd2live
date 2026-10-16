# diffusers at ai-toolkit's pinned commit: fetched through the GitHub proxy, built without it (the proxy blocks the pip index).
set -e
cd /root/autodl-tmp/dl
rm -rf diffusers
(. /etc/network_turbo >/dev/null; git init -q diffusers && cd diffusers && git remote add origin https://github.com/huggingface/diffusers.git && git fetch -q --depth 1 origin c943837899b16cbae2f619b8dd4f7bb6f07dd81a && git checkout -q FETCH_HEAD)
/root/autodl-tmp/aitk-venv/bin/pip install -i https://pypi.tuna.tsinghua.edu.cn/simple --timeout 120 --retries 10 ./diffusers
/root/autodl-tmp/aitk-venv/bin/python -c "import torch, diffusers, transformers, peft, cv2; print('aitk env ok', torch.__version__, diffusers.__version__, transformers.__version__)"
