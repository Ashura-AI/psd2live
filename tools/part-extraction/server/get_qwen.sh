# Qwen3-4B (Hugging Face layout) for ai-toolkit's Klein text encoder, from ModelScope, three shards at once.
D=/root/autodl-tmp/ai-toolkit/Qwen/Qwen3-4B
H=https://modelscope.cn/models/Qwen/Qwen3-4B/resolve/master
mkdir -p "$D"
for f in config.json generation_config.json merges.txt model.safetensors.index.json tokenizer.json tokenizer_config.json vocab.json; do
  curl -sSL --retry 10 -o "$D/$f" "$H/$f"
done
for f in model-00001-of-00003.safetensors model-00002-of-00003.safetensors model-00003-of-00003.safetensors; do
  curl -sSL --retry 10 -C - -o "$D/$f" "$H/$f" && echo "done $f $(stat -c %s "$D/$f")" &
done
wait
echo all-done
