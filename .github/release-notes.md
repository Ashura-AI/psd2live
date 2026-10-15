> ⚠️ **预览版**：3.5.0 带来较大的界面和工作流程变化，可能仍有问题，建议先备份工程再试用；正式使用请继续用 3.4.5。遇到问题欢迎提交 Issue。

素材库与「先摆放、再生成」的排版工程；开始、帮助与设置成为可停靠的页面；标题栏菜单重新整理；长发歪头时自然弯曲，绘画不再受网格范围限制。

## 主要更新

### 新功能

- **素材库**：导入图片、PSD、CMO3 中的分层图片和 SVG，按文件夹整理、加标签、预览，可把文件夹导出为 PSD；素材随工程保存，修改可撤销。双击素材在独立窗口中查看和绘制，工具与画布的绘画模式相同，所有使用它的图层一起更新。
- **先摆放、再生成**：「排版工程」从空画布开始，把素材拖到画布上摆放后再生成 Rig；之后继续加图、改标签，「重新生成」会合并进来，已有的关键形、变形器、物理、骨骼等编辑保留。
- **开始、帮助与设置成为页面**：三者都是标签栏最前面的页面，面板可像编辑器一样拖动、合并成标签、浮动或关闭；F1 打开帮助，Ctrl+, 打开设置。设置新增「文件与数据」（各类数据的位置与大小、清理缓存）和「MCP」。
- **菜单整理**：标题栏菜单为文件、视图、模型、帮助，「工具」改为「模型」，设置移到标题栏右侧的齿轮。
- **语义标签**：图层表的类型、部件、侧别改为可组合的标签，也可以加自己的标签。
- **MCP**：`psd2live mcp` 无需窗口即可运行；新增参考图、特写裁切、部件剥离等操作。

### 改进

- **长发歪头时弯曲**：下巴以下的头发在歪头时逐渐转回（新调参「长发歪头保持」，默认 80%），不再整束甩出。
- **参考图与特写**：素材可设为参考图叠在画布上，不参与生成与导出；可从参考图裁出特写素材。
- **绘画不受网格范围限制**：整张画布都可以画，网格外的部分在应用时自动拆成新图层。
- **菜单统一**：各面板的右键菜单和下拉菜单外观、动画一致。
- 编辑器的「选择」模式改名为「物体」模式；修改已生成图层的分类改为在下一次「重新生成」时生效。

<details>
<summary>English</summary>

> ⚠️ **Preview**: 3.5.0 brings large changes to the interface and workflow and may still have problems. Back up your projects before trying it, and stay on 3.4.5 for real work. Please report problems as issues.

A material library and Layout projects that place first and generate after; Start, Help and Settings become dockable pages; the title-bar menus are reorganized; long hair bends with a head tilt, and painting is no longer bounded by the mesh.

## Highlights

### New

- **Material library**: import images, PSDs, the layered images inside CMO3 files and SVGs; sort them into folders, tag and preview them, and export a folder as a PSD. Materials are saved with the project and edits can be undone. Double-click one to view and paint it in its own window with the canvas's paint tools; every layer using it updates together.
- **Place first, then generate**: a Layout project starts from an empty canvas; arrange materials on it, then generate the rig. Keep adding images and changing tags afterwards, and "Regenerate" merges them in while your keyforms, deformers, physics, bones and other edits stay.
- **Start, Help and Settings are pages**: all three sit at the head of the tab strip, and their panels drag, tab together, float and close like the editor's; F1 opens Help, Ctrl+, opens Settings. Settings gains "Files and data" (where each kind of data lives and how large it is, clearing caches) and "MCP".
- **Reorganized menus**: the title bar has File, View, Model and Help; Tools becomes Model, and Settings moves to the gear at the right of the title bar.
- **Semantic tags**: the layer table's type, part and side become tags that combine, and you can add your own.
- **MCP**: `psd2live mcp` runs without a window; new operations for reference images, close-up crops and peeling parts.

### Improvements

- **Long hair bends with a head tilt**: hair below the chin turns back as the head tilts (new tuning "Long hair hold on tilt", 80% by default) instead of swinging out whole.
- **Reference image and close-ups**: a material can be laid over the canvas as a reference, kept out of generation and export; close-up materials can be cropped from it.
- **Painting is no longer bounded by the mesh**: paint anywhere on the canvas; what falls outside the mesh becomes new layers when applied.
- **Menus match**: every panel's context menus and dropdowns share one look and animation.
- The editor's "Select" mode is now "Object" mode; changing a generated layer's classification takes effect at the next "Regenerate".

</details>
