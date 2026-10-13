### 修复

- **保存与恢复**：完善保存、关闭与崩溃恢复的状态协调，恢复检查点保留完整工程状态，连续姿势编辑按顺序提交。
- **保留导入源文件**：工程保留导入时的 PSD / CMO3 原始内容，后续读取不再依赖外部源文件。
- **空图层**：含空图层的 PSD 导入后可正常保存和重新打开。
- **独立网格检查**：检查失败会显示错误；超过 60 秒会停止，关闭检查后迟到的结果不再重新弹出对话框。内存不足和调用栈溢出也有明确提示。

### 改进

- **示例素材独立分发**：示例素材、工程和输出迁移到[独立示例仓库](https://github.com/tsunehimatoi/psd2live-examples)，可按需下载；主仓库保留说明与固定版本清单，并欢迎投稿。

<details>
<summary>English</summary>

### Fixes

- **Saving and recovery**: improved coordination between saving, closing, and crash recovery. Recovery checkpoints preserve complete project state, and consecutive pose edits commit in order.
- **Retained import sources**: projects retain the original imported PSD / CMO3 content, so later reads no longer depend on the external source file.
- **Empty layers**: imported PSDs containing empty layers can be saved and reopened correctly.
- **Independent mesh checks**: failures show an error and checks stop after 60 seconds. Late results no longer reopen a dismissed dialog. Out-of-memory and stack-overflow failures also have clear messages.

### Improvements

- **Separate example distribution**: example assets, projects, and outputs now live in the [dedicated examples repository](https://github.com/tsunehimatoi/psd2live-examples) and can be downloaded on demand. The main repository retains documentation and a pinned catalog; contributions are welcome.

</details>
