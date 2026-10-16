# 部件提取 LoRA 实验工具

用 Live2D 模型渲染"特写 → 单个部件（原位、完整、灰底）"训练对，在 FLUX.2 klein 4B 上训练编辑 LoRA。开发工具，不属于产品代码。

## 组成

- `src/test/kotlin/io/github/psd2live/tools/PartDatasetTool.kt`（`PSD2LIVE_TOOLS=1`）：
  - `eyes`：眼部特写，输出虹膜（去裁剪，完整）/ 眼白 / 睫毛眼睑 / 眉毛。
  - `head`：嘴部特写，输出嘴唇线条 / 口腔内部（去裁剪）；头部取景，输出前发 / 侧发 / 后发。
  - 读 `*.model3.json` 和 `*.cmo3`。部件按网格对标准参数的响应识别，不依赖命名。
  - 环境变量：`PARTS_ROOT`、`PARTS_NAME`、`PARTS_PREFIX`、`PARTS_VARIANTS`、`PARTS_SIZE`、`PARTS_THREADS`。
- `build_dataset.py`：`render NAME ROOT PREFIX` 渲染一个收藏；`pairs OUT SIZE MIN` 合并全部收藏，去重并排除留出角色；`upload OUT REMOTE` 上传。上传需要环境变量 `L2D_GPU_SSH`（ssh 参数）。
- `make_pairs.py`：生成训练对；`comfy/metrics.py`：在留出集上按部件打分（IoU、遮挡补全、位置偏移、颜色误差）；`comfy/evaluate.py`：出对照图。
- `server/eyes_aitk*.yaml`：ai-toolkit 训练配置；`server/*.sh`：GPU 机环境（ModelScope 下载模型，ai-toolkit 独立 venv）。

## 结论（截至 v5 开训）

- ComfyUI 0.41 自带的 TrainLoraNode 在 Flux2 编辑训练上会把模型训坏，改用 ai-toolkit（`arch: flux2_klein_4b`，`control_path` 指向输入特写）。
- 在 Base 上训练，在蒸馏版上 4 步推理可用。ComfyUI 里在 Base 上加 CFG 推理会让背景偏色。
- v1（1860 对，384px，第 1500 步）在留出集上：虹膜 IoU 0.84、眼白 0.82、睫毛 0.49、眉毛 0.24（眉毛位置偏移约 81px）。1024 推理会退化。
- v5：10009 对，最大 768px，留出集新增两个高分辨率角色，共 6000 步，每 500 步存检查点并出采样图。

## 后续

1. 按采样图和 `metrics.py` 选 v5 检查点，补一个 AI 插画评测集。
2. 用 `head` 渲染嘴和头发，检查标签后加进训练集（提示词见 `make_pairs.py` 的 `PROMPTS`，需要补 lips / inside / front / side / back）。
3. 泛化：对输入做风格增强（模糊、噪点、低强度重绘），目标保持图层渲染。
