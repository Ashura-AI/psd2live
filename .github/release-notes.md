v3.6.6：选区自由变换与快速选择、眼睛整体透过刘海、布点工具合并刀具，以及更简洁的画布显示栏。

## 主要更新

### 新功能

- **自由变换选区**：绘画模式的自由变换（`Alt+T`，绘画模式下 `T`）把选区像素（无选区时为整层内容）放进变换框，可移动、缩放、旋转，Enter 应用为一笔，Esc 放回。
- **快速选择与选区扩展**：选区工具新增快速选择笔刷；绘画菜单可扩展 / 收缩选区，或按透明度选择不透明像素。
- **眼睛整体透过刘海**：眼白、虹膜与高光、睫毛、闭眼线都可透过刘海显示，新工程默认开启；按 3.6.5 规则生成的模型重新生成即可更新。

### 改进

- **布点工具合并刀具**：布点工具分为「点」（`Alt+M`）与「线」（`K`）两种，线模式画折线让网格沿线折叠；已有工程照常重放。
- **内部造型手柄移到物体模式**：`Shift+I` 进入物体模式，手柄下不再画网格点，点其他对象即可切换。
- **Tab 切换模式**：Tab 在物体模式与上次使用的模式之间切换。
- **画布显示栏更简洁**：右下角只显示固定的开关，且只在画布有可显示内容时出现；「视图 ▾」可固定或取消；洋葱皮移到动画编辑器工具栏，随机姿势移到参数面板。

### 修复

- 编辑画布正确显示反转遮罩以及加算、乘算混合。

<details>
<summary>English</summary>

v3.6.6: free transform and quick selection, whole eyes through the bangs, the knife folded into Mesh points, and a shorter canvas display rail.

## Highlights

### New

- **Free transform of selections**: the Paint free transform (`Alt+T`, or `T` in Paint mode) puts the selection's pixels (or the whole layer) in a box to move, scale and rotate; Enter applies it as one stroke, Esc puts it back.
- **Quick selection and resizing selections**: a quick selection brush joins the selection tool; the paint menu expands or contracts the selection and selects opaque pixels.
- **Whole eyes through the bangs**: eye whites, irides with highlights, lashes and closed-eye lines can show through the bangs, on by default for new projects; rigs made under the 3.6.5 rules update when regenerated.

### Improved

- **Knife folded into Mesh points**: the tool has a Points face (`Alt+M`) and a Line face (`K`) whose polyline lets the mesh fold along it; recorded projects replay as before.
- **Interior handles in Object mode**: `Shift+I` switches to Object mode with no mesh points under the handles; clicking another object picks it.
- **Tab switches modes**: Tab toggles between Object mode and the mode last used.
- **A shorter canvas display rail**: only pinned toggles show, and only when the canvas has something for them; pin or unpin from "View ▾". Onion skin moves to the animation editor's toolbar and random pose to the parameters panel.

### Fixes

- The editing canvas shows inverted masks and add / multiply blends correctly.

</details>
