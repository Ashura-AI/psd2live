### 修复

- **保存与关闭更可靠**：修复长时间编辑后无法保存或关闭的问题；保存期间继续编辑会保留未保存状态，放弃修改与崩溃恢复保持一致。
- **撤销与重做**：先提交正在输入的字段再切换历史，修复连续编辑时历史状态不一致的问题。
- **导入与变形器**：修复 16 位 PSD、相对图层遮罩和 TIFF 方向的读取；删除变形器时保留子对象的默认姿势与旋转继承。
- **导出与动作**：导出失败时恢复上一套文件，重新导出较短的帧序列会清理旧尾帧；修复 GIF 帧时长、贝塞尔动作裁剪及部分运行时和 Web 导出问题。
- **稳定性**：加强损坏文件的检查，修复清除用户数据、预览异常恢复和后台任务取消的问题，减少 MCP 连接日志噪声。

<details>
<summary>English</summary>

### Fixes

- **Reliable saving and closing**: fixed failures after long editing sessions. Edits made during a save remain marked as unsaved, and discarding changes stays consistent with crash recovery.
- **Undo and redo**: commit the field being edited before switching history, and keep history state consistent during consecutive edits.
- **Import and deformers**: corrected 16-bit PSD, relative layer masks, and TIFF orientation. Deleting deformers preserves child objects' default poses and rotation inheritance.
- **Export and animation**: restore the previous file set if export fails and remove stale tail frames when exporting a shorter sequence. Fixed GIF frame timing, Bezier motion trimming, and runtime and Web export issues.
- **Stability**: improved malformed-file checks, user-data clearing, preview failure recovery, and background-job cancellation; reduced MCP connection log noise.

</details>
