v3.6.7：新的图形设置页、Linux 画布恢复 GPU 绘制、编辑画布的混合与遮罩与预览一致，以及更快的保存。

## 主要更新

### 新功能

- **图形设置页**：设置新增「图形」：渲染路径（GPU / 软件画布 / 软件）、显卡选择、画质预设、窗口与画布抗锯齿（MSAA、SSAA）、纹理过滤、垂直同步、预览与后台帧率等。
- **MCP 一步完成编辑器的一个手势**：读写标尺辅助线、导入为参考、预览表情、软选择变形、对称笔画等；拒绝原因可区分，并能读回写入的内容。

### 改进

- **设置页排版**：改为平铺的标题与两列行，不再用卡片。
- **GPU 画布更省**：每帧工作更少，拖动时不再反复重组工具栏与状态栏。
- **保存更快**：小工程保存约快三分之一，归档内容不变。

### 修复

- Linux 上编辑画布恢复 GPU 绘制，不再每帧跳过；恢复提示不再空白卡住（#62）。
- 编辑画布的遮罩、正片叠底 / 滤色颜色与各种混合模式与预览一致。

<details>
<summary>English</summary>

v3.6.7: a new graphics settings page, GPU drawing back on the Linux canvas, editing-canvas blends and masks matching the preview, and faster saves.

## Highlights

### New

- **Graphics settings**: a new Settings > Graphics page: render path (GPU / software canvas / software), graphics card, a quality preset, window and canvas antialiasing (MSAA, SSAA), texture filtering, vsync, preview and background frame rates.
- **One call per editor gesture in MCP**: read and set ruler guides, import as reference, preview expressions, soft-selection deforms, symmetric strokes and more; refusals tell their causes apart and writes read back.

### Improved

- **Settings layout**: flat headings and two-column rows instead of cards.
- **Lighter GPU canvas**: less work per frame, and a drag no longer recomposes the toolbars and status bar.
- **Faster saves**: small projects save about a third faster, with identical archives.

### Fixes

- The editing canvas draws on the GPU on Linux again instead of skipping every frame, and the restore prompt no longer opens blank and frozen (#62).
- The editing canvas draws masks, multiply / screen colours and every blend mode as the preview does.

</details>
