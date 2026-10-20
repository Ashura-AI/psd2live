# 界面操作一致性待办

本清单覆盖层级树、画布右键菜单、参考图菜单、图层属性表及共享菜单组件。以下为代码检查确认、留待下一轮处理的 **6 类问题**（高优先级 2 类，中优先级 4 类），按问题类别计数，不是控件总数，也不代表已完成实机验证。

## 后续修复清单

| 编号 | 优先级 | 已确认的差异与代码位置 | 下一轮目标与验收 |
| --- | --- | --- | --- |
| UI-01 | 中 | 层级树的变形器菜单仍用 `TreeContextMenu` / `CompactMenuItem`，ArtMesh 与参考图菜单用 `PressMenu` / `GlidingColumn` / `FloatingMenuRow`。见 `ui/views/WorkspaceView.kt` 的变形器菜单与 `ui/views/ArtMeshMenu.kt` | 共用菜单行、高亮、标题、宽度和滚动规则；相同指针轨迹下只出现一条滑动高亮，窗口边缘仍可操作 |
| UI-02 | 高 | 参考图层在 `DrawableTreeItem` 中提前返回独立的 `ReferenceTreeRow`；其单击、按键修饰、多选、拖放登记、树枝连接线及悬停反馈未复用普通图层的处理。见 `ui/views/WorkspaceView.kt`、`ui/views/ReferenceImagesView.kt` | 复用树节点交互与布局，只替换类型图标和专属附加信息；覆盖 Ctrl/Shift 多选、父节点下的拖放、转换前后选取和悬停 |
| UI-03 | 中 | 图层属性表中的参考图徽标点击即恢复成模型图层，工具栏则是批量切换，常规图层缺少对称徽标入口；标签仍使用“转换为参考”的固定文字。见 `ui/views/InspectorView.kt`、`layers.reference.toggle`、`layers.reference.tip` | 区分状态标识与操作按钮；明确单项及批量目标，复用转换动作的文案、图标和可用性；同时校对快捷键名称 |
| UI-04 | 中 | 删除操作的危险色规则不同：`CompactMenuItem(danger=true)` 会改变文字和悬停背景，`FloatingMenuRow` 的调用处通常只把图标染红。见 `ui/components/CompactWidgets.kt`、`ui/views/texture/AtlasToolbar.kt`、`ui/views/ReferenceImagesView.kt` | 在共享菜单行中定义危险操作样式，统一普通、悬停、按下和禁用状态；检查删除图层、参考图及工具菜单 |
| UI-05 | 中 | 菜单动作的收起与焦点顺序不同：ArtMesh / 参考图先收起再执行；`ToolOptionViews.MenuOption` 的 `ActionOption` 先执行，再回焦点及收起。文件选择器等动作可能跨越这一过程。见 `ui/views/ArtMeshMenu.kt`、`ui/views/ReferenceImagesView.kt`、`ui/views/tooloptions/ToolOptionViews.kt` | 明确普通动作、打开对话框、连续动作三种生命周期；统一收起及回焦点时机，验证对话框不会被菜单或画布抢焦点 |
| UI-06 | 高 | 同一图层的转换可用性不一致：`InspectorView` 把转换按钮的 `enabled` 绑定到 `reorderableSelection`，后者不包含已生成的用户创建网格；`PSD2LiveViewModel.referenceSelection` 与菜单的转换判断却包含 `createdLayerIds`。因此导入的 ArtMesh 可以从右键菜单转为参考图，属性表按钮仍可能禁用 | 把转换和上下移层的可用性分开，统一使用转换规则；覆盖导入图层、参考图层恢复成 ArtMesh、普通已生成图层及混合多选 |

代码位置均相对 `src/main/kotlin/io/github/psd2live/`，消息键位于 `src/main/resources/i18n/`。本轮转换菜单的统一不包含这些待办的完整修复。

## 验收方式

- 对同一类操作比较层级树、画布和属性面板：措辞、目标图标、分组位置、行高、间距、可用性、关闭和焦点行为。
- 对相互转换的操作检查两条路径：一次点击执行、选中对象保持、历史只增加一次；不因切换对象类型丢失后续操作入口。
- 检查四种语言、明暗主题、界面及字体缩放、窗口四边的位置，并保留截图；涉及动效时另保留录屏。
- 各项修复后补充针对实际交互的回归，再从本清单移除已完成的条目。
