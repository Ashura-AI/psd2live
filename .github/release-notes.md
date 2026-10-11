修复版本：柔性骨骼弯曲与拉直不再出现波纹；切换历史版本时不再被迟到的预览重建覆盖。

## 主要更新

### 修复

- **柔性骨骼弯曲与拉直不再出现波纹**：拉直前先把骨链关节拟合到图形中线，拉直后不再残留偏移；关节处改为平滑圆角弯曲，关节前的图形不再朝弯曲反方向甩出。
- **撤销、重做与预览重建冲突**：预览重建尚未完成时切换历史版本，以切换为准，不再被迟到的重建结果覆盖。

<details>
<summary>English</summary>

A fix release: flexible bones bend and straighten without waves, and a history checkout is no longer overwritten by a late preview rebuild.

## Highlights

### Fixes

- **Flexible bones bend and straighten without waves**: straightening first fits the chain's joints to the middle of the art, so no offset is left behind; joints bend as smooth rounded corners, and the art before a joint no longer swings out against the turn.
- **Undo, redo and preview rebuilds**: switching history versions while a preview rebuild is still running now wins, instead of being overwritten by the late rebuild.

</details>
