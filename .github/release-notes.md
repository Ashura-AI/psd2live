新增柔性骨骼，尾巴、触手、飘带等可平滑弯曲、分叉并一键拉直；骨骼编辑重新整理，限制、混合带与对称轴直接在画布上编辑；Linux 新增免安装的 tar.gz 包；修复 moc3 导出中带混合形的网格飞出画布等问题。

## 主要更新

### 新增

- **柔性骨骼**：柔性骨链沿关节曲线平滑弯曲蒙皮，卷起时不会粘连，骨链可分叉、各分支分别弯曲；“拉直”把画出的弯曲网格展开为参数 0 时的直线状态，原形状保存为姿势；多关节弯曲的网格弯曲时保持长度。旧工程的尾巴骨链打开时自动转换。
- **Linux tar.gz 包**：解压即可运行，自带 Java 运行时，适用于没有 Deb 的发行版（amd64 与 arm64）。
- **导出对话框的 can3 选项**：导出 CMO3 时可选择是否同时导出 Cubism Animator 工程。

### 改进

- **骨骼编辑**：骨骼树只列骨骼，设置集中在检查器；角度限制、混合带与对称轴直接在画布上拖动编辑，精确数值在“高级”中或控柄旁输入；各模式使用同一套骨骼选择。
- **骨骼外形与 IK 显示**：不同种类骨骼外形可一眼区分，颜色按角色固定；IK 在画布上显示可转动的骨骼链、枢轴和弯曲方向，检查器中有图例。
- **从源码运行无需 Rust**：没有 cargo 时自动下载校验过的预编译运行时库。

### 修复

- **moc3 导出**：Warp 下无关键形、带混合形的网格（如布料烘焙后的裙子）在参数变化时不再飞出画布（#29）。
- **快速连续撤销**不再丢掉其中一次。
- **旋转变形器**：部分字段的关键形不再被丢弃，MCP 创建时网格不再跳出画布，Warp 下导出不再偏移数像素。
- 删除拆分出的部件不再报错；缩小参数范围或删除参数后动作仍可编辑；调整设置时提交姿势不再让滑块弹回。

<details>
<summary>English</summary>

Flexible bones arrive: tails, tentacles, ribbons and the like bend smoothly, fork and straighten with one click. Bone editing is reorganized, with limits, blend bands and the symmetry axis edited on the canvas. Linux gets an extract-and-run tar.gz. Meshes with blend shapes no longer fly off in moc3 exports, among other fixes.

## Highlights

### New

- **Flexible bones**: a flexible chain bends its skin along a curve through its joints, so touching coils stay apart, and a chain can branch with each branch bending on its own; Straighten unrolls the drawn mesh so parameter 0 is straight and keeps the drawing as a pose; meshes bent by several joints keep their length. Tail chains of older projects convert when opened.
- **Linux tar.gz**: extract and run, with its own Java runtime, for distributions without Deb (amd64 and arm64).
- **can3 option in the export dialog**: choose whether exporting CMO3 also writes the Cubism Animator project.

### Improvements

- **Bone editing**: the bone tree lists only bones and settings live in the Inspector; angle limits, the blend band and the symmetry axis are dragged on the canvas, with exact numbers under Advanced or beside the handles; every mode shares one bone selection.
- **Bone shapes and IK guide**: bone kinds read apart by silhouette and keep their colors by role; IK shows the bones it may turn, its pivot and bend direction on the canvas, with a legend in the Inspector.
- **Source builds without Rust**: without cargo, a checked prebuilt runtime library is downloaded.

### Fixes

- **moc3 export**: an unkeyed mesh with blend shapes under a warp (such as a skirt after a cloth bake) no longer flies off the canvas when the parameter moves (#29).
- **Quick repeated undo** no longer loses a step.
- **Rotation deformers**: partial keyforms are no longer dropped, meshes no longer jump off the canvas when MCP creates one, and exports under a warp no longer drift a few pixels.
- Deleting a split piece no longer fails; motions stay editable after narrowing or deleting a parameter; committing a pose while adjusting settings no longer snaps the slider back.

</details>
