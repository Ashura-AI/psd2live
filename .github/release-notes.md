v3.6.0：帮助页改为按 Cubism 目录整理的「手册」与「教程」，参考成为层级树里的一类成员，网格可按纹理结构重布点，关键形可以镜像。

## 主要更新

### 新功能

- **帮助页 → 手册与教程**：按 Cubism 编辑器手册与教程网站的目录重排，每页标出与 Cubism 相同、操作不同、本程序独有或尚未实现，页末列出该页相关的 MCP 操作。
- **参考**：背景、图层中、前景三种参考成为层级树成员，可多张、可锁定、Alt+R 显隐，按关键形固定；不透明度改为连续滑条，参考图与 ArtMesh 可互相转换。
- **按纹理结构重布点**：按纹理里画出的褶皱、明暗分界与图案线条重建网格内侧，顶点只放在线上，关键形、路径、权重与 Glue 照常迁移。
- **关键形镜像**：点/变形菜单可把当前键镜像到对称键、对侧对象或原位翻转，Shift+M 一键镜像。
- **按落点拖入文件**：拖入文件时松手的位置决定做什么（放素材、插入层级、放置图层、设为参考、替换图片或仅导入素材库），CMO3 拖入即打开。
- **素材与 CMO3**：每个图层都有对应素材，关联不再悄悄断开；导入的 CMO3 按当前图层重新打包纹理集。
- **历史树压缩**：连续的线性版本合并为一张卡片，工具栏可切回逐节点视图，双击卡片检出该段最后一步。

### 改进

- **Layout 先编辑、再生成**：未生成的 Layout 工程也能像普通工程一样编辑网格、变形器、参数与层级，并可直接导出。
- **更流畅**：编辑画布单独绘制，参数拖动与不显示姿势的面板不再逐帧重组；PSD 导入的准备移到后台（#50）。
- **素材面板**：显示使用量，悬停列出使用它的图层，右键可编辑标签、设为默认参考图层或选中使用它的图层。
- **内部造型面板**：与其他检查器一致的工具条与分区，试动改为滑条与 2D pad。
- **卡死诊断**：日志写入文件，界面卡住超过 5 秒记录线程栈（#52）。

### 修复

- **MOC3 用户数据**：导出用户数据时写出 `userdata3.json` 并在 `model3.json` 中引用。
- **纹理绑定**：导入图片与参考图层的纹理不再绑到 1×1 的占位图块。
- **参考图层位置**：Layout 的参考图层按自己的画布矩形绘制。

<details>
<summary>English</summary>

v3.6.0: the help page becomes a Cubism-style Manual and Tutorials tree, references become hierarchy members, meshes can be re-pointed along the structure in their texture, and keyforms can be mirrored.

## Highlights

### New

- **Help page → Manual and Tutorials**: laid out like the Cubism Editor manual and tutorial site, each page tagged same as Cubism, different, PSD2Live-only or not yet, and ending with the MCP operations that do its work.
- **References**: background, in-stack and foreground references are hierarchy members — several at once, lockable, Alt+R to show or hide, and pinnable to keyforms; opacity is a continuous slider, and a reference image and an ArtMesh convert into each other.
- **Mesh along texture structure**: rebuilds a mesh's inside along the creases, shading boundaries and pattern lines drawn into its texture, with keyforms, paths, weights and glue migrated.
- **Mirror keyforms**: the deform menu mirrors a key onto its opposite key, the opposite-side object or in place; Shift+M mirrors to the opposite key.
- **Drop where you mean it**: the drop position decides what happens (material, hierarchy insert, placed layer, reference, image replacement or library-only import); a dropped CMO3 opens directly.
- **Materials and CMO3**: every layer has its material and links no longer break silently; an imported CMO3 gets its atlas packed from the current layers.
- **Compressed history tree**: consecutive linear versions share one card, a toolbar toggle restores the per-node view, and double-clicking a card checks out the last step of its run.

### Improved

- **Edit before generating**: a Layout project not generated yet edits meshes, deformers, parameters and the hierarchy like any project, and exports directly.
- **Smoother**: the edit canvas paints on its own, parameter drags and pose-less panels no longer recompose every frame, and PSD import prepares in the background (#50).
- **Materials panel**: shows usage counts, lists the layers using a material on hover, and its menu edits tags, sets the default reference and selects the using layers.
- **Interior panel**: uses the same toolbar and folding sections as the other inspectors, with the audition as sliders and a 2D pad.
- **Freeze diagnostics**: the console goes to a log file and a UI freeze over 5 seconds writes a thread dump (#52).

### Fixes

- **MOC3 user data**: exporting user data writes userdata3.json and references it from model3.json.
- **Texture binding**: imported images and reference layers no longer bind their textures to a placeholder tile.
- **Reference layer placement**: a Layout reference layer draws at its own canvas rectangle.

</details>
