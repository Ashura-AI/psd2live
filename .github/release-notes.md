v3.6.5：绘画选区与新工具、手工布点、关键形工具、表情 / 姿势 / Event、点击区域、眉毛透过刘海，以及窗口模式和新的界面样式。

## 主要更新

### 新功能

- **绘画选区与新工具**：选区工具（`Alt+L`）含矩形、椭圆、套索、多边形套索与魔棒；新增渐变、模糊笔、不连续填充，以及防抖、左右对称与保护透明度。
- **手工布点**：`Alt+M` 加点、移点、连线、删点或删边，可左右对称；拓扑菜单增加删除边与重新三角化。
- **内部造型画布手柄**：`Shift+I` 在画布上拖动鼓起、偏压、传动方向，直接画褶皱引导线。
- **关键形工具**：复制粘贴形状到其他键或对象（混合、翻转）、恢复默认、相邻键补间、多键编辑；`Shift+T` 临时变形格子。
- **画布辅助**：栅格、标尺辅助线、洋葱皮与随机姿势。
- **复制粘贴与批量改名**：`Ctrl+C` / `Ctrl+V` 复制网格、变形器、部件与参数；复制网格拓扑；查找替换与批量转换 ID。
- **表情、姿势与 Event**：动画面板创建表情、姿势组与动作 Event，导出 exp3、pose3 与 UserData；生成的模型默认带四个表情。
- **点击区域**：设置 HitArea 并在预览中点击验证，导出写入 model3 与 VTube Studio 包。
- **时间轴辅助与循环**：书签、隐藏、冻结、标记、运动轨迹；一键首尾衔接，参数可循环。
- **眉毛透过刘海**：自动生成时眉毛半透明显示在刘海之上，可调可关。

### 改进

- **编辑器与 Agent 同时编辑**：编辑器的未提交修改只在冲突字段上让步，其余照常提交。
- **滤色混合**：PSD 滤色图层导出为 Cubism 5.3 Screen（更早版本近似为加算）。
- **窗口模式与新界面样式**：设置中可选窗口、最大化、全屏（`F11`）；面板改为圆角卡片，菜单样式统一。

### 修复

- 唇线绘制提示、参数名右键解除绑定、窗口不再跑出屏幕（#55）；大画布上的误报退化与重新生成冲突（#56、#59）。

### 兼容

- 含表情、姿势或点击区域的工程，较早版本会拒绝打开，不会悄悄丢失数据。

<details>
<summary>English</summary>

v3.6.5: paint selections and new tools, mesh point placement, keyform tools, expressions / pose / events, hit areas, brows through the bangs, plus window modes and a new look.

## Highlights

### New

- **Paint selections and tools**: a selection tool (`Alt+L`) with marquee, ellipse, lasso, polygon lasso and magic wand; new gradient, blur brush and global fill, with a stabilizer, mirror symmetry and lock transparency.
- **Mesh points**: `Alt+M` adds, moves, connects and removes points or edges, optionally mirrored; the topology menu adds delete edges and re-triangulate.
- **Interior handles on the canvas**: `Shift+I` drags bulge, pressure and propagation and draws fold guides on the canvas.
- **Keyform tools**: paste shapes onto other keys or objects (blended or flipped), restore defaults, interpolate between keys, edit several keys at once; `Shift+T` temporary lattice.
- **Canvas aids**: grid, ruler guides, onion skin and random pose.
- **Copy, paste and rename**: `Ctrl+C` / `Ctrl+V` for meshes, deformers, parts and parameters; duplicate mesh topology; find and replace and bulk ID conversion.
- **Expressions, pose and events**: make expressions, pose groups and motion events in the animation panel; exports write exp3, pose3 and UserData, and generated models come with four expressions.
- **Hit areas**: set them up and click to test in the preview; exported to model3 and the VTube Studio package.
- **Timeline aids and loops**: bookmarks, shy, freeze, markers, motion trails; close loops in one step and let parameters repeat.
- **Brows through the bangs**: generation shows covered brows semi-transparently above the bangs, adjustable or off.

### Improved

- **Editing alongside the agent**: the editor's pending changes yield only conflicting fields to an agent commit.
- **Screen blend**: PSD Screen layers export as Cubism 5.3 Screen (approximated as Add for earlier versions).
- **Window modes and a new look**: windowed, maximized or full screen (`F11`) in Settings; panels become rounded cards and menus share one style.

### Fixes

- Lip outline painting hint, unbinding a parameter from its right-click menu, window kept on screen (#55); false collapses and regeneration conflicts on large canvases (#56, #59).

### Compatibility

- Projects holding expressions, a pose or hit areas are refused by earlier builds instead of losing that data.

</details>
