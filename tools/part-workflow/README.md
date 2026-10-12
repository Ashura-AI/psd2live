# part-workflow

整部件生成流程的 skill 与 Python 工具套组，设计依据见 [素材与 Layout 工作区](../../docs/zh/spec/MATERIAL_LAYOUT_WORKSPACE.md)。这是工作区实现之前的验证工具，不属于产品代码。

- [SKILL.md](SKILL.md)：给 AI agent 的操作手册（流程、铁律、命令、判读）。
- `p2lwf/`：工具套组，`python -m p2lwf --help` 看命令。
- `references/`：提示词、指标、坑点、可行性测试方案。

```bash
pip install -r requirements.txt
python -m p2lwf selftest
```

命令一览：`matte`（白底抠图）、`suggest-bg`、`cutout`（原画与上层抠图）、`task`（任务包）、`ingest`（抠图 → 对齐 → 指标 → 对比图）、`align`、`metrics`、`compare`、`repair-task` / `repair-paste`（局部修复）、`composite`、`psd`、`coverage`、`exposure`、`selftest`。
