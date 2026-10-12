# 补全指标

输入是一对已摆放到同一画布的 RGBA：原画抠图 `O`（母版上该部件可见的部分）与新图层 `N`（补全后的整部件，经 `ingest` 对齐）。可选传入 `--upper`（遮挡它的图层）、`--ref`（母版）、`--lower`（位于它之下的层）。命令：`python -m p2lwf metrics --original O --new N --upper …`，或 `ingest` 自动计算。

阈值都是**初值**，必须用真实部件标定（见末尾），在标定前把 flag 当作提示而不是结论。

## 两项主指标

**`overlap_fidelity`（重叠区域:原画重合度，0..1）**
- `Σ min(αO, αN)·s / Σ αO`，逐像素相似度 `s = exp(−ΔE/12)`，ΔE 为 Lab 色差；比较前两者做同样的小半径模糊，并在 ±2 px 内取最近邻，避免亚像素偏移被重罚。
- 同时返回 `coverage`（原画被新图层覆盖的比例）、`color_similarity`（覆盖处的颜色相似度）。
- **咨询性**：不是越接近 1 越好。原画有缺陷（线条抖动、色块、残留投影）需要修复时，降到 0.7 可能更好。低于 `min_fidelity` 只给 info，要求目视确认。

**`completion_ratio`（新图层总体积:原画，必须 > 1）**
- `Σ αN / Σ αO`（二维下"体积"即 alpha 质量）。
- 有遮挡的部件，补全后应明显大于 1。接近 1 说明只是复述原画。
- 判定 `NOT_COMPLETED`（error，阻止）：有上层信息，且 `hidden_boundary_fraction ≥ hidden_boundary_min`、`hidden_boundary_px ≥ 6`、`completion_ratio ≤ 1 + completion_eps`，且没有 `--fully-visible`。
- 没有 `--upper` 时给 `NO_UPPER_INFO`（warning）：无法判断补全是否必需，不要默认放行。

## 诊断

| 指标 | 含义 |
| --- | --- |
| `hidden_boundary_fraction` / `hidden_boundary_px` | 原画轮廓中紧贴上层图层的比例/像素数。越高说明越应该有补全 |
| `hidden_gain` | 新增面积中落在上层之下的部分 / Σ αO，是合理的补全 |
| `visible_spill` | 新增面积中**没有**被上层覆盖的部分 / Σ αO，会改变合成观感；> 0 时要确认是修复还是漂移 |
| `composite_fidelity` | 把新图层换进合成（`--lower` + 新 + `--upper`）后与母版的相似度，需要 `--ref`。上层缺失时会偏低 |
| `style_drift` | 新增区域 vs 原画区域的风格统计距离：Lab 直方图、梯度直方图、平均梯度的平均。新图层可以更好但风格不能迁移 |
| `seam_score` | 新旧区域交界处两侧平均 Lab 色差 / 30，上限 1 |
| `flags[]` | `{rule, level, detail}`；规则：`NOT_COMPLETED`(error)、`NO_UPPER_INFO`、`SHRUNK`、`VISIBLE_SPILL`、`STYLE_DRIFT`、`SEAM`、`LOW_FIDELITY` |
| `blocked` | 有 error 级 flag |

## 已知局限

- 原画抠图 `O` 的质量决定一切：多边形画错，指标就错。先看 `cutout --preview`。
- `style_drift` 是统计量：新增区域内容（例如袖口 vs 上臂）本来就不同时会偏高；在合成数据上无风格变化也有约 0.4。阈值 0.5 是临时值，可选的图像嵌入距离尚未实现。
- `hidden_boundary_fraction` 对细长部件（手臂、发束）天然很小（遮挡口只是一个截面），所以阈值取得低（0.03）。
- `composite_fidelity` 需要完整的 `--lower/--upper` 才有意义。
- 没有对"姿势是否正确""解剖是否合理"的判断，这些必须看图。

## 标定办法

1. 选 5–10 个真实部件（含手臂、腿、尾巴、发束，各有遮挡与无遮挡的），每个生成多张候选，人工标注"可用 / 不可用 / 需修复"。
2. 对每张跑 `ingest`，记录各指标。
3. 画出可用与不可用在 `completion_ratio`、`hidden_boundary_fraction`、`style_drift`、`seam_score` 上的分布，选使"不可用"被拦下而"可用"基本不误拦的阈值，写回 `p2lwf/metrics.py` 的 `DEFAULTS`。
4. 记录样本数与分布，避免凭少数样本定死阈值。
