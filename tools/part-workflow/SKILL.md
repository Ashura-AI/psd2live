---
name: live2d-part-workflow
description: 把一张全身（或头部）母版拆成 Live2D 可用的完整部件图层：整部件生成（补全被遮挡部分）→ 白底抠图 → 自动对齐 → 补全指标（原画重合度、补全体积比）→ 对比图 → 装配检查 → 分层 PSD。用于准备交给 PSD2Live 的素材，或评估外部 AI 生成的部件是否合格。
---

# Live2D 部件工作流

目标：得到一组**完整的、风格一致的、已摆到母版坐标上的**部件图层，再交给 PSD2Live（导入 PSD 或后续的 Layout 工作区）。
工具在 `tools/part-workflow/p2lwf`，命令都是 `python -m p2lwf <命令>`（在 `tools/part-workflow` 目录运行），输出 JSON。
自检：`python -m p2lwf selftest`（合成数据，约半分钟，应输出 ALL PASS）。依赖见 `requirements.txt`，写 PSD 才需要 `psd-tools`。

## 铁律

1. **补全是必须的。** 只与原画严格匹配而没有补出被遮挡的部分是严重错误：转头、摆动、隐藏上层时会露底。首要验收是 `completion_ratio > 1`，其次才是与原画像不像。
2. **主路径：让外部 AI 一次性画出整个部件**（含被遮挡部分），不要用"可见像素保持不变、只补遮罩"的做法去拼整个部件。后者会产生接缝、竖棱、风格割裂和反复返工。遮罩补画只用于小范围缺陷（`repair-*`）。
3. **按语义和材质拆部件**，不按遮挡区域拆：腿 = 肤色大腿 / 丝袜 / 吊袜带与挂饰 / 靴，各自一次生成。一次生成里不混多种材质。成对部件每个任务只含一侧。
4. **新图层不必逐像素等于原画。** 可以比原画更好（修复缺陷），但**风格不能迁移**（线宽、明暗层数、色调、质感）。所以 `overlap_fidelity` 只是咨询，不是硬门槛；0.7 的修复结果可能比 0.95 的复述更好。
5. **背景用白色。** 母版本身是白底，工具对白底最稳，不会把绿色混进发丝和阴影。白色前景（白发、白袖、白纱）贴着背景时，改用与前景最远的颜色（`task` 会自动建议）。
6. **不信单一数字。** 对齐置信度、误差、指标都可能误导，最后一定看 `sheet.png` 和隐藏上层后的渲染。指标阈值是初值，需要标定。
7. **只报告看得到的结果。** 汇报时附对比图路径、指标 JSON，写出已知问题；做不到的地方如实写明，不要用模糊或取色填充冒充补画，也不要改写提示词去绕过内容审核（换工具或改方案）。

## 流程

约定：母版 `ref.png`（白底全身，作为画布坐标系）；工作目录 `work/`。

### 0. 前置检查（人工 + 看图）
- 母版严格正面、对称、白底、无地面阴影。头部另出高清特写时，特写**眼线必须水平**（偏差 > 1° 就重做），放回全身只用相似变换且旋转接近 0。
- 先设计图层清单（命名遵循 `docs/zh/spec/PSD_LAYER_SPEC.md`：`back hair`、`handwear-l` 等；左右指角色自己的左右）。同时给 `mouth close` 和 `mouth open`。

### 1. 原画抠图与上层
对每个目标部件，在母版上用多边形圈出它**可见的部分**，得到原画抠图 `O`；把遮挡它的图层（袖子、裙摆、头发…）同样抠出，作为 `uppers`。
```
python -m p2lwf cutout --ref ref.png --include "x,y;x,y;..." --exclude "x,y;..." --output work/arm_l.orig.png \
       --preview work/arm_l.prev.png
python -m p2lwf cutout --ref ref.png --include "<袖子多边形>" --output work/sleeve_l.png
```
**看 `--preview` 图确认多边形对**再继续（可重复调整坐标）。多边形坐标是母版像素坐标；先看母版估计，再用预览修正。

### 2. 生成任务包
```
python -m p2lwf task --ref ref.png --out work/task_arm_l --part arm-l --side left \
       --desc "the character's bare left arm (shoulder to fingertips)" \
       --region x0,y0,x1,y1 --original work/arm_l.orig.png --upper work/sleeve_l.png
```
产出 `reference.png`（带上下文的母版裁切）、`prompt.txt`（整部件提示词）、`part.json`。背景色自动建议（默认白）。
提示词写法见 `references/prompts.md`：用 "viewer's left/right"；用 "ONLY: …no X, no Y" 排除；写明被遮挡的一端要补到根部并自然收口；禁止画上层对它的投影和勒痕。

### 3. 外部生成（工具无关）
把 `reference.png` 与 `prompt.txt` 交给图像生成工具（Codex 内置生图、网页版等），存为 `result.png`。
- 每个部件先试 1–2 张；看图确认：姿势与母版一致、风格一致、被遮挡的一端补全且没有直线截断、背景纯色、没有别的部件。
- 姿势不对：改用另一侧的图并 `--mirror`。完全看不见的部件（如被挡住的第三条尾巴）只能靠生成，尺度靠自动摆放加人工校正。
- 被内容审核拒绝：不要改写提示词绕过；换工具，或改方案（镜像另一侧、取母版像素），并在报告里写明。

### 4. 导回：抠图 → 对齐 → 指标 → 对比图
```
python -m p2lwf ingest --task work/task_arm_l --result work/task_arm_l/result.png [--mirror] [--lower …]
```
产出到 `task_dir/out/`：`candidate.png`（抠好的候选）、`placed.png`（摆到画布坐标的图层）、`sheet.png`（参考 | 候选 | 单色叠加 | 误差热力）、`report.json`。
- 对齐只用相似变换，默认旋转锁为 0（`--rot-limit` 才放开）。`ambiguous: true`（可区分度低）时不要直接采用：看 `sheet.png`，用 `--init scale,cx,cy` 手工给初值或缩小 `--scale-min/--scale-max`。
- 抠图警告（前景接触边框、外包框占满整图）说明背景色不匹配或生成图有渐变背景，重新生成或指定 `--bg`。

### 5. 读指标并决定
| 结果 | 含义 | 处理 |
| --- | --- | --- |
| `NOT_COMPLETED`（error） | 原画轮廓紧贴上层，但新图层没有补出被遮挡部分 | 重新生成，强调补全；不得放行。部件确实没有遮挡时用 `--fully-visible` |
| `completion_ratio` ≈ 1 且无上层信息 | 无法判断补全 | 补充 `--upper`，不要默认放行 |
| `overlap_fidelity` 低（info） | 与原画差异大 | 看对比图：是修复了原画缺陷（可接受）还是画歪/改了设计（重做） |
| `visible_spill` > 0（warning） | 新增面积落在没被上层覆盖的地方，合成观感会变 | 确认是修复还是漂移；漂移则重做或手工裁掉 |
| `style_drift` 高（warning） | 新增区域与原画风格统计差异大 | 看新增区域是否换了线宽、阴影层数、色调 |
| `seam_score` 高（warning） | 新旧区域交界处颜色突变 | 局部修复或重生成 |
指标含义和标定办法见 `references/metrics.md`。

### 6. 局部修复（仅小范围缺陷）
```
python -m p2lwf repair-task --ref work/placed_composite.png --mask mask.png --box x0,y0,x1,y1 --out work/rep1
# 外部生成 → work/rep1/result.png
python -m p2lwf repair-paste --task work/rep1 --result work/rep1/result.png --output work/rep1/final.png
```
`repair-paste` 先在遮罩外标定配准，只取遮罩内新像素，遮罩外严格不变，并报告被改动的像素数和残留保留色。不要用它补整个部件。

### 7. 装配与检查
准备 `spec.json`（`canvas` + 从下到上的 `layers: [{name, file}]`，文件为画布大小的 `placed.png`）：
```
python -m p2lwf composite --spec spec.json --output comp.png [--hide sleeve-l]
python -m p2lwf coverage  --spec spec.json --ref ref.png --out work/cov      # 参考前景里没被任何层覆盖的缺口
python -m p2lwf exposure  --spec spec.json --out work/exp                    # 逐层隐藏后露底的洞
python -m p2lwf psd       --spec spec.json --output model_layers.psd         # 需要 psd-tools
```
重点看：隐藏上层（裙子、袖子）后被遮挡部件是否完整（无截断、渐隐、竖棱、碎片）；脖子、袖口、袜口、两腿之间是否露底。

### 8. 交给 PSD2Live
导入 PSD（`project_import_psd`）后检查：没有 Unknown、左右正确、层序正确（眼睛可放在前发之上）；`workspace_inspect` 查网格名后再写 swing 目标（不带 `mesh:` 前缀）；渲染检查转头、摆动、张嘴、闭眼。关闭 `mouthOutlineEnabled` 避免闭嘴双线。

## 汇报格式
每个部件一行：部件、生成次数、`overlap_fidelity`、`completion_ratio`、flags、是否采用、对比图路径。再列：没做到的、需要人决定的、工具问题（附复现命令）。

## 参考
- `references/prompts.md` 提示词模板与各类部件写法
- `references/metrics.md` 指标定义、阈值与标定
- `references/pitfalls.md` 实测踩过的坑
- `references/feasibility_test.md` 小规模可行性测试（由 Codex 执行）
