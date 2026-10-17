3.5 系列的首个正式版：在 3.5.0 预览版（素材库、排版工程、页面化的开始/帮助/设置）基础上，新增内部造型、Unity 预制体导出、参考图层与 Layout 清单，并修复预览版中的问题。

> 从 3.4.x 升级的用户请参阅 [3.5.0 的更新说明](https://github.com/tsunehimatoi/psd2live/releases/tag/v3.5.0)，界面与工作流程有较大变化，建议先备份工程。

## 主要更新

### 新功能

- **内部造型**：为单个网格生成保持轮廓的内部曲面变化（鼓起、偏压、褶皱，可加局部褶皱），可跟随鼠标试动，并可加缓动与传动；随工程保存并参与导出。MCP 新增 `interior_put` / `interior_delete`。
- **Unity 预制体导出**：把参数关键形烘焙为混合形状、动作烘焙为 Animator，角色文件夹可直接放进 Unity 工程。
- **参考图层**：图层可设为参考图层（Ctrl+Alt+R），留在画布上但不进入 Rig 和导出；参考图层单独绘制，编辑更流畅。
- **Layout 清单**：可从 psd2live-layout 清单打开 Layout 工程或导入为素材；尚未生成的图层可置顶、置底、上移、下移。

### 改进

- **绘制顺序留有间隔**：新工程的图层绘制顺序以 10 为间隔编号，只接受整数；旧工程保持原编号。
- **英文文档**：用户指南、格式参考与 MCP 参考新增英文版。

### 修复

- **CMO3 导出**：小数绘制顺序按原叠放次序转为整数，不再丢失关键形或导出失败（#39）。
- **CMO3 导入**：部分模型导入卡死已修复且更快；导入后的绘制顺序与透明度与 Cubism 一致；重复 ID 的物理设置全部保留。
- **3.5.0 预览版的问题**：素材绘画、素材关联、删除图层后的排版与生成队列、连续修改分类、部件剥离的半透明边缘（#42）；动画工作区崩溃；开始页预设应用期间的点击（#47）。

<details>
<summary>English</summary>

The first full release of the 3.5 series: on top of the 3.5.0 preview (material library, Layout projects, Start / Help / Settings as pages) it adds interior shaping, Unity prefab export, reference layers and layout manifests, and fixes the preview's problems.

> Upgrading from 3.4.x? See the [3.5.0 notes](https://github.com/tsunehimatoi/psd2live/releases/tag/v3.5.0): the interface and workflow changed a lot, so back up your projects first.

## Highlights

### New

- **Interior shaping**: give a mesh outline-preserving surface changes inside it (bulge, press, folds, local folds), try them with the pointer, with easing and transmission; saved with the project and exported. MCP adds `interior_put` / `interior_delete`.
- **Unity prefab export**: parameter keyforms bake into blend shapes and motions into an Animator, so a character folder drops into a Unity project.
- **Reference layers**: keep a layer as reference (Ctrl+Alt+R): it stays on the canvas but out of the rig and exports, and draws on its own so editing stays smooth.
- **Layout manifests**: open a Layout project from a psd2live-layout manifest or import it as materials; layers not generated yet move to the front or back, or a step forward or backward.

### Improvements

- **Draw orders leave gaps**: a new project numbers layers 10 apart and takes whole numbers only; older projects keep their numbering.
- **English docs**: the user guides, format references and MCP reference now have English versions.

### Fixes

- **CMO3 export**: fractional draw orders become whole numbers in the same stacking, so keyforms are no longer lost and the export no longer fails (#39).
- **CMO3 import**: imports that hung are fixed and faster; imported draw order and opacity match Cubism; physics settings sharing an ID are all kept.
- **3.5.0 preview problems**: material painting and links, the Layout and generation queues after deleting a layer, consecutive classification edits, translucent edges of peeled parts (#42); a crash in the Animation workspace; clicks while start presets apply (#47).

</details>
