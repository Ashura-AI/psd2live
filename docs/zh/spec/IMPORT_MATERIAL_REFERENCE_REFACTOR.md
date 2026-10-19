# 导入、素材与参考图重构方案（设计中）

本文是重构设计，尚未实现。现状见 [MATERIAL_LAYOUT_WORKSPACE.md](MATERIAL_LAYOUT_WORKSPACE.md)；落地后把结论并入该文档，本文移出。

## 1. 现状问题与根因

| 问题 | 根因（代码位置） |
|---|---|
| 拖入行为混乱、误报错 | 唯一入口是窗口级 AWT `DesktopDropTarget`，分派写在 `PSD2LiveApp.kt` 的 `onFilesDropped`：先素材面板，再“透明位图 → 图层”，最后按扩展名猜（打开工程 / PSD / 清单 / 输出目录 / 不支持）。意图由文件类型推断，而不是由落点决定；`.cmo3` 拖入直接报 `dialog.unsupportedDrop`；无预览时拖 PNG 报 `error.importLayerBusy`。 |
| 拖动时无法精确定位，只能落到根 | ① `PSD2LiveApp.kt` 的全窗口 65% 黑遮罩盖住图层树；② `dragOver` 不上报坐标，拖动中没有任何落点反馈；③ 松手时屏幕坐标减 `window.locationOnScreen` 得到 AWT 逻辑像素，而 `hierarchyImportHitTest` / `materialsImportHitTest` 用 Compose 物理像素（`uiScale` 开启），非 100% 缩放时命中失败回退根；④ 命中规则只有“挂到哪个变形器”，`importImages` 也只接收父变形器，没有插入位置。 |
| 素材与 ArtMesh 多份副本互相绑定 | 图层 `SourceLayer.raster` 与 `MaterialItem.raster` 各存一份，`layout.layerMaterials` 只是旁路表，“关联”= 像素逐字节相等（`WorkspaceMaterialLinks.shows`）。素材改动逐个推给图层；图层像素一变就被 `reconcile` 静默解绑（镜像、拆分、`fit=contain` 换图都会）。PSD / 清单导入自动登记素材；`project_import_cmo3`、`layer_import_images`、`asset_*` 不登记；`material_import` 读 CMO3 用 CLayeredImage，而工程导入从纹理集反推，同一文件两套像素；CMO3 REPLACE 丢弃整个 `layout`。 |
| 参考图难找、不可选不可调 | 有两套名字相近的机制：`layout.reference`（单张叠加图，只在素材右键菜单“设为参考图”，UI 不能移动/缩放/锁定/置下，只有 MCP 能改 rect）和 `referenceLayerIds`（参考图层，是真实源图层，能被点击穿透选中，铺满画布时反而抢选择）。 |

## 2. 目标

1. **落点即意图**：拖入目标由鼠标所在区域决定，拖动全程实时显示“将要发生什么”；不接受的组合在拖动中就显示，松手后不再报错。
2. **素材是像素的唯一源头**：每个源图层引用一个素材，不再持有自己的像素；PSD、CMO3、图片、绘制溢出等所有来源都在素材库里有对应条目。
3. **参考图是一等对象**：统一成一种“参考”概念，有显眼入口，可选择、变换、锁定、排序、显隐。

## 3. 数据模型：素材唯一源

### 3.1 引用而非副本

- `SourceLayer` 增加 `materialId`（必填于新文档），图层像素由素材解析：`WorkspaceDocument` 构造时把 `source.layers[i].raster` 解析为 `materials.item(materialId).raster` 的同一实例。`raster` 保留为只读派生视图，生成器、纹理集、导出读取方式不变。
- 删除 `layout.layerMaterials` 与 `WorkspaceMaterialLinks.reconcile`；“哪些图层用某素材”由图层反查，三处重复的 `references()` 合并为 `MaterialLibrary` 的一个索引。
- 素材条目增加 `role`：`ART`（普通）/ `REFERENCE`（参考图专用，可选）/ `DERIVED`（绘制溢出、裁切等由编辑产生）。素材库按来源分文件夹：`<文件名>.psd`、`<文件名>.cmo3`、`导入的图片`、`绘制`。
- `generationSource` / `meshSource` 仍是生成器内部的冻结快照，不进素材库、不对用户显示；它们只存 blob 引用，与素材共享内容寻址去重。

### 3.2 编辑语义（不再有静默解绑）

| 操作 | 新语义 |
|---|---|
| 在图层上绘画、调整、镜像像素、按路径换图 | 写它引用的素材，所有引用该素材的图层同步。 |
| 素材被多个图层引用时只想改其中一个 | 显式“设为独立素材”（`material_fork`）：以确定 ID `mat_<hash(源素材ID, 图层ID)>` 复制一份并改引用，再编辑。GUI 在绘画开始时若素材被多处引用，提示一次“改全部 / 仅此图层”。 |
| 拆分图层 | 每个拆出部分生成 `DERIVED` 素材，放在原素材旁边。 |
| `layer_replace_image`（文件 / png） | 先把图片登记为素材，再改引用；`fit=contain` 的补边结果就是新素材的像素。 |
| `layer_replace_image material_id` | 只改引用。 |
| 删除素材 | 仍被图层或参考引用时禁止（与现有参考图规则一致），提示引用者。 |

素材改动对已生成图层的副作用保持现有规则（钉住 `generationSource`、加入 `refreshLayerIds`），但集中在一个函数里，由“素材 → 引用图层”的扇出一次完成，不再分散在 `WorkspaceMaterialPixels`、`WorkspaceMaterialEdits.resolution`、`WorkspaceDrawingEdits` 三处。

### 3.3 各导入路径统一

所有导入走同一个 `WorkspaceMaterialIngest`：读文件 → 生成素材条目（确定 ID）→ 可选地创建引用它的图层。

| 入口 | 产出 |
|---|---|
| 打开 / 导入 PSD、Layout 清单 | 每层一个素材 + 引用它的图层（行为同现在，只是改为引用）。 |
| 导入 / 打开 CMO3 | `Cmo3ModelImport.layers` 从纹理集反推的每个 ArtMesh 图块登记为素材（文件夹 `<文件名>.cmo3`），图层引用它们；`material_import` 读 CMO3 改用同一个提取器，同一文件只有一套像素。REPLACE 模式保留现有 `layout`（素材库、参考），只替换模型来源。CMO3 纹理回写尚未支持前，CMO3 素材标为只读，编辑时提示先“设为独立素材”并转为普通图层。 |
| 拖入/导入图片为图层（`layer_import_images`） | 先登记到 `导入的图片`，图层引用之。 |
| 绘制溢出（`WorkspacePaintOverflow.addLayer`） | 新素材放 `绘制` 文件夹，`role = DERIVED`。 |
| 旧 `asset_*` | 冻结为只读兼容层，新流程不再使用；在 ROADMAP 记录移除。 |

### 3.4 兼容与迁移

- 遵守“读取不改写”：旧文档（v1、v2 中无 `materialId` 的图层）在读取时生成**派生视图**——为每个没有链接的图层合成虚拟素材 `mat_layer_<hash(layerId, rasterDigest)>`，有 `layerMaterials` 且像素相等的沿用原素材。虚拟素材在素材库显示、可被引用，存储不变；第一次新编辑提交时才把它们物化写入新字段。
- `ProjectFormatV2` 的图层节点增加 `material` 字段，素材节点不变；补 `ProjectFormatV2Test` 拆分/拼回用例。
- MCP：`material_unlink` 保留为 `material_fork` 的别名并在描述中标注弃用；`workspace_inspect scope=layers` 的 `material_id` 改为必有。

## 4. 拖放：落点路由

### 4.1 架构

```
DesktopDropTarget (AWT, 窗口级)
  dragEnter/dragOver/drop ──► DropSession(files, kinds, windowPx)  坐标统一换算为 Compose 窗口像素（× AWT scale）
                                  │
                                  ▼
                       DropRouter（ui/state，单例）
                         zones: 各面板注册的 DropZone(bounds, accepts, resolve)
                         hover: 当前命中的 zone + resolve 出的 DropIntent
                                  │ drop
                                  ▼
                       DropIntent ──► ViewModel 已有命令（importMaterials / insertLayers / setReference / replaceImage / openProject…）
```

- `DesktopDropTarget.dragOver` 上报位置，换算成 Compose 窗口像素（修正缩放 bug）；`DropRouter` 每帧命中测试，状态以 `StateFlow` 发布给各面板绘制高亮。
- `DropZone` 由面板用 `Modifier.dropZone(id, accepts) { localPos, files -> DropIntent? }` 注册（`onGloballyPositioned` 维护 bounds），替换现有的 `materialsImportHitTest` / `hierarchyImportHitTest` 两个回调。
- `DropIntent` 是密封类：`OpenProject`、`OpenSource(psd/cmo3/清单)`、`ImportMaterials(folderId)`、`InsertLayers(parent, before/after)`、`PlaceOnCanvas(canvasPoint)`、`SetReference(rect?)`、`ReplaceMeshImage(layerId)`、`Reject(reasonKey)`。意图由 zone 决定，文件类型只用于判断 zone 是否接受。
- 移除全窗口遮罩。拖动中：所有可接收区域画一圈淡强调色描边；当前区域实心高亮；鼠标旁跟随一个 `BarChip` 标签说明意图（“插入到 头部 ▸ 前发 之上”“导入到素材库 / 服装”“设为参考图”“替换 ArtMesh：左眼”）；不接受时标签显示原因（“此处只接受图片”）。开始页保留现有 `DropHighlight`。
- 松在任何区域之外：什么也不做，状态栏提示“拖到 … 即可导入”，不弹错误。

### 4.2 落点与意图

| 区域 | 接受 | 意图 |
|---|---|---|
| 开始页 / 无工程的工作区 | `.psd2live` `.psd` `.cmo3` 清单 文件夹 | 打开工程 / 以该源新建工程（cmo3 补上拖入支持） |
| 标题栏工程名区域（有工程时） | 同上 | 打开（先走 `withSavedChanges`） |
| 素材库面板 | 图片 / PSD / CMO3 / SVG / 文件夹 | 导入为素材，落到悬停的文件夹（不创建图层） |
| 图层树 | 图片 / PSD / CMO3 | 先登记素材，再按插入线插入图层：行上部 = 之前，下部 = 之后，变形器行中部 = 嵌套，空白 / ROOT 横幅 = 根末尾。复用 `ParametersPanelView` 的 `ParamDropTarget`（Before / Append / Nest / Root）语义与让位动画，抽成共享的树拖动组件，内部重排也改用它。 |
| 编辑画布主体 | 图片 / 素材库条目 | 在落点放置为图层（新图层中心对齐落点，进生成视图的 pending） |
| 画布顶部拖放槽（拖动时出现，`FloatingBar` 样式） | 图片 / 素材 | “设为参考图”“替换 ArtMesh 图片”（悬停在网格上时出现，显示网格名）“仅导入素材库” |
| 检查器的纹理/图片槽 | 单张图片 / 素材 | 替换该 ArtMesh 图片 |

素材库条目拖到图层树、画布、拖放槽，走同一套 zone（素材拖动已有 `materialDrag`，改为向 `DropRouter` 提交 `DropSession`），保证外部文件与内部素材行为一致。

### 4.3 应用层改动

- `WorkspaceSourcePort.importImages` 增加 `insertion: LayerInsertion(parentDeformerId, anchorLayerId?, before|after)`；`WorkspaceImageLayerCommands` 与 `layer_import_images` 同步增加 `insert_before` / `insert_after`，并在 schema 中声明。
- 新图层的绘制顺序取锚点相邻值，确定 ID 不变（`placed_<hash>`）。
- 拖入 = 导入素材 + 插入图层，作为**一个原子批量**提交（`WorkspaceDocumentCommands`），只产生一个历史节点。

## 5. 参考图：统一为“参考”对象

### 5.1 模型

`WorkspaceLayout.references: List<ReferenceEntry>`（按绘制顺序）：

```
ReferenceEntry(
  id,                 // ref_<hash(materialId, 序号)>
  materialId,         // 像素来自素材库（3 节）
  transform,          // 画布矩形 + 旋转（替代只有 rect 的 LayoutReference）
  opacity, visible, locked,
  placement: UNDER | OVER,   // 模型之下 / 之上
  pins: Map<paramId, Float>, // 固定到关键形（并入现 referencePins）
)
```

- 旧 `layout.reference` 读为一个 `OVER` 条目；旧 `referenceLayerIds` 读为 `UNDER` 条目（像素取其源图层引用的素材，位置取图层四角）。都是派生视图，按 3.4 节规则读取不改写；新编辑只写 `references`。
- 参考与生成、导出完全隔离（现有排除点 `WorkspaceGenerationEdits`、`ArtworkDrawList`、`RigCanvasSupport` 改为读 `references`）。
- 显隐：文档 `visible` 是“这张参考是否显示”，画布 `TabViewOptions.showReference` 是“这块画布是否显示全部参考”，两者在 UI 上分别对应条目眼睛与显示栏开关。

### 5.2 入口

- 图层树顶部固定的 **“参考”分组**（独立于 Rig 树，可折叠），列出全部参考条目：眼睛、锁、不透明度滑条、上/下切换、拖动排序；分组标题旁 `+` 按钮“添加参考图…”（文件或素材）。
- 显示栏 `CanvasOptionsRail` 的参考开关常驻（无参考时点击即“添加参考图…”）。
- 画布右键、主菜单“视图 / 插入”增加“添加参考图…”；拖放槽“设为参考图”（4.2 节）；素材右键菜单保留。
- 快捷键在 `ShortcutRegistry` 登记：切换参考显示、锁定/解锁选中参考；`Ctrl+Alt+R`（图层设为参考）改为“把选中的待生成图层转为参考条目”。

### 5.3 选择与变换

- 参考条目在“参考”分组中点击即选中，画布出现与图层相同的变换框（移动、等比/自由缩放、旋转），提交走 `layout_set_reference` 扩展后的命令（新增 `id`、`transform`、`locked`、`placement`），连续拖动只记一条历史。
- **默认锁定**：新加的参考锁定，画布点击穿透它，不再抢图层选择；解锁后才能在画布上直接点选。`RigCanvasSupport.hitLayers` / `CanvasEditor.layerCandidates` 改为排除锁定参考。
- 编辑画布与预览画布都可显示参考（预览默认关）；渲染统一到 `ReferenceLayerPainter`（支持旋转矩阵），MCP 软件渲染 `WorkspaceViewRenderer` 读同一列表。

## 6. MCP 契约变化

| 操作 | 变化 |
|---|---|
| `layer_import_images` | 增加 `insert_before` / `insert_after`；返回新建的 `material_ids`。 |
| `material_fork` | 新增；`material_unlink` 为别名并弃用。 |
| `layout_set_reference` | 增加 `id`（缺省作用于第一条，保持兼容）、`transform.rotation`、`locked`、`placement`；新增 `layout_add_reference` / `layout_remove_reference` / `layout_order_references`。 |
| `layer_mark_reference` / `layer_pin_reference` | 改为生成/修改参考条目；保留旧名。 |
| `project_import_cmo3` | 结果中返回生成的素材文件夹。 |

所有新增或变更操作补严格 `resultSchema`，并更新 `docs/zh/agent/MCP_AUTHORING.md` 与英文版。

## 7. 分阶段落地

| 阶段 | 内容 | 验收 |
|---|---|---|
| P0 立即修复 | 拖放坐标换算乘 AWT 缩放；无预览拖 PNG、拖 CMO3 不再报错（走打开 / 提示）；CMO3 REPLACE 保留 `layout` | 150% 缩放下拖入命中正确行；回归测试覆盖坐标换算与 `resolveDropAction` |
| P1 拖放路由 | `DropRouter` + `DropZone`；去掉全窗口遮罩；跟随标签；素材库、图层树（含插入线）、画布、拖放槽四类区域；`importImages` 插入位置；原子批量 | 每种区域 × 文件类型矩阵有单测（纯 `resolve` 函数，不起 UI）；插入顺序经保存/重开不变 |
| P2 素材唯一源 | `SourceLayer.materialId`；派生视图迁移；删除 `reconcile` 与 `layerMaterials`；统一 `WorkspaceMaterialIngest`；`material_fork`；CMO3 素材 | 旧工程打开后素材库列出全部图层且存储字节不变；绘画/镜像/拆分后引用不丢；`ProjectFormatV2Test`、历史重放、导出逐字节对比（`ExportGoldenTool`）不变 |
| P3 参考统一 | `references` 列表；“参考”分组；变换框与锁定；入口与快捷键；MCP 扩展 | 旧 `reference` / `referenceLayerIds` 工程显示一致；参考不进生成与导出；锁定参考不抢选择 |
| P4 收尾 | 移除 `asset_*` 新入口；文档（本文并入 `MATERIAL_LAYOUT_WORKSPACE.md`、`guide/LAYOUT.md` 中英、应用内手册、whatsnew）；四语 i18n | `MessageBundleParityTest` 通过；手册与 `ShortcutRegistry` 一致 |

P1 与 P2 互不依赖可并行；P3 依赖 P2（参考像素来自素材）。

## 8. 待决问题

1. 绘画时素材被多处引用：默认“改全部”还是“仅此图层”？建议默认改全部（与“素材是唯一源”一致），提示可一键改为独立。
2. 画布主体拖入是否默认“放置为图层”，还是默认进素材库、需拖到拖放槽才放置？建议放置，并在跟随标签上写明。
3. CMO3 素材回写纹理集（使 CMO3 图层可直接绘画）是否纳入本次范围，还是保持只读到后续版本。
4. 现“参考图层”（固定到关键形的逐姿态参考）全部并入参考条目后，是否还需要“参考图层参与图层树排序”的能力。
