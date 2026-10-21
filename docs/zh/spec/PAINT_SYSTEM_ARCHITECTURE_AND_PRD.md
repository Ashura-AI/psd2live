# 绘画系统

[文档目录](../../README.md) · [画布编辑](../guide/CANVAS_EDITOR.md) · [运行时与导出边界](RUNTIME_EXPORT_ARCHITECTURE_AND_GAPS.md)

本页说明绘画模式的用户流程、组件分工、坐标与提交方式，以及扩展时需要验证的场景。

## 用户流程

选择图层 → 绘画模式 → （可选）建立选区 → 画笔 / 铅笔 / 橡皮 / 模糊 / 填充 / 渐变 / 吸管 / 形状 → 会话内撤销重做 → 应用或放弃 → 检查模型并保存工程。

像素绘制在图层自身的栅格上（`PaintSpace`，见[逐层尺寸](DOCUMENT_LAYER.md#逐层尺寸)），按同一密度向画布边缘延伸，不受旧网格边界限制。保留网格（默认）时提交按网格裁切（`WorkspacePaintOverflow`）：网格上的像素留在图层，网格外新画的像素拆成紧邻其上的新图层，自带网格并挂在同一父级与部件下，同时作为素材加入素材库并与之关联；网格外的擦除不生效。尚无网格的图层或要求重建网格时，图层按全部像素扩展。

## 组件分工

| 组件 | 职责 |
| --- | --- |
| `core/RasterPaintEngine` | 画笔、橡皮、填充、形状等栅格操作；`ui/LayerPaintEngine` 只做 Compose 颜色与形状枚举的适配 |
| `core/PaintRasterSession` | 草稿栅格与独立笔画历史；`PaintPixelPatch` 记录受影响的 64×64 像素瓦片，恢复或交换以撤销 / 重做，并按选区与保护透明度收束手势（`confine`） |
| `core/PaintSelection` | 选区蒙版（每个栅格像素一字节覆盖度）：矩形、椭圆、多边形（套索）、魔棒，新建 / 添加 / 减去 / 交叉，反选、羽化与蚂蚁线轮廓 |
| `core/PaintStrokeAssist` | 防抖（`PaintStabilizer`）与对称镜像（`PaintSymmetry`），只处理指针位置，不碰像素 |
| `core/PaintSpace` | 草稿像素与画布单位的仿射映射、裁剪与提交像素（`PaintPixels`） |
| `application/WorkspacePaintSessions` | 进程内私有会话（`WorkspacePaintSession`）：捕获起始 `state`，GUI 与 MCP 共用的手势、撤销、跳转、取色和提交 |
| `ui/PaintSession` | 包装一个 `WorkspacePaintSession` 句柄，向 Compose 与 GPU 预览投影草稿和变化区域 |
| `core/RasterPaintCommit` | 提交时的源图、纹理集与网格准备，经 `WorkspaceRasterCommands` 进入文档候选、重建与 CAS |
| `ui/CanvasEditor` | 会话启动、工具交互与提交入口 |

会话所有像素修改应走 `edit` / `willWrite` 等记录入口；绕过它会破坏撤销和增量预览。单笔的覆盖取经过区域的最大值，再乘不透明度，避免同一笔反复经过时不断加深。橡皮使用覆盖去除 Alpha。

## 选区、保护透明度与笔画辅助

- **选区属于会话，不属于笔画历史。** `WorkspacePaintSession.select` 改变选区并推进会话版本，但不追加笔画记录：撤销 / 重做只换像素，选区保持不变（与 Photoshop 把选区记入历史不同，避免撤销一笔时选区跟着跳）。会话结束（应用、放弃、换图层）选区随之清除。选区为空等同于没有选区。
- **一切像素编辑都穿过选区。** 实时笔画在 `Stroke.land` / `landBlur` 中把覆盖度乘以选区值；填充、形状、渐变、清除等整体手势先照常写入，再由 `PaintPixelPatch.confine` 在被捕获的瓦片上按选区值把结果插回原像素（预乘混合，透明处不渗色）。因此选区内的结果与无选区时逐像素一致，选区外原样不动，软边（抗锯齿、羽化）按比例混合。
- **保护透明度**（`lock_alpha`）：笔画只给已有像素上色并保留其 Alpha；整体手势在收束时恢复原 Alpha。保护透明度下橡皮改为用背景色绘画（Photoshop 行为）。
- **模糊笔**与普通笔画同样按覆盖度最大值落笔：每段读取笔画开始时的像素（`PaintPixelPatch.original`，覆盖到卷积半径之外），做预乘盒式模糊后按覆盖度 × 强度混合，所以同一笔来回涂抹不会越涂越糊，分段方式不影响结果。卷积半径为笔尖半径的四分之一（1–16 像素）。
- **防抖**在屏幕坐标上对最近 `等级 + 1` 个指针位置取平均，松手时补齐到松手位置；**对称**以画布中心为轴，在画布坐标中镜像后再换算到图层自身的帧，镜像的各段共用同一笔的覆盖度，交叠处只落一次。两者都只作用于画笔、铅笔、橡皮和模糊。
- **Shift + 单击**从上一笔终点画直线到单击处（同一会话内）。
- MCP：`paint_session_control` 的 `select` 动作、手势 `blur` / `fill` / `gradient`、油漆桶 `contiguous` 与各手势的 `lock_alpha` 与界面共用同一实现；`source_paint_*` 单次手势没有选区概念。

MCP 有两种入口：`source_paint_*` 六种单次手势各为一个后台任务与历史节点；`paint_session_begin / paint_session_list / paint_session_control / paint_session_commit` 使用与 GUI 相同的私有会话，`control` 执行手势、撤销 / 重做 / 跳转、取色与 PNG 渲染，`commit` 作为后台任务一次提交（见 [MCP 接口](../agent/MCP_AUTHORING.md#工程与导出任务)）。

## 坐标与提交

指针、手势点、半径与线宽都是画布单位，经 `PaintSpace` 映射到草稿像素；采样类工具再按像素读取。预览按受影响区域发布，不必每次复制整张画布。

应用时只读取图层原区域与笔触写过的区域，裁剪到非透明像素，栅格保持原密度、不缩回画布分辨率，并更新纹理集。保留网格时按画布位置重新寻址纹理坐标；重建时新网格经实际父级的中性变换放回纹素所在位置，避免绘画后包围盒改变导致父级下的形状被重新缩放。完全擦空的图层使用空透明栅格处理。

成功应用进入工程保存 / 历史链路。独立绘画撤销和工程分支撤销是两个层次，界面与文档应明确当前操作的对象。

## 需要验证的场景

- 半透明笔画往返、自交和橡皮：实时显示与松手结果一致。
- 多次撤销重做：像素和预览同步恢复。
- 修色且保留网格：纹理不漂移。
- 扩大轮廓后重建：父级变形器和已有姿态关系没有意外变化。
- 高密度图层：笔触按图层栅格落笔，提交后密度不变。
- 保存重开、历史恢复和导出：源像素与 UV 仍对应。

- 选区：矩形 / 椭圆 / 套索 / 多边形 / 魔棒各一次，加减交叉组合后画笔、填充、渐变和清除只改选区内；撤销笔画后选区不变。
- 保护透明度：画笔与渐变不在透明处留下像素，已有像素 Alpha 不变。
- 对称与防抖：镜像笔画在轴上交叠处不加深；松手后笔画终点与指针一致。

## 工具对照与差距

以 Photoshop 与 PaintTool SAI 中画师最常用的工具为参照。「已有」为本节写成前已具备，「本次」为此次实现（见上文与[画布编辑](../guide/CANVAS_EDITOR.md#绘画的应用与放弃)），「后续」为尚未实现、记录在 [ROADMAP](../ROADMAP.md) 的方向。

| 类别 | 参照功能 | 状态 | 说明 |
| --- | --- | --- | --- |
| 笔刷 | 大小 / 硬度 / 不透明度，`[` `]` 调大小，Alt + 右键拖动调整 | 已有 | 画笔、铅笔、橡皮各自记住大小；笔尖环即实际笔触范围 |
| 笔刷 | 单笔不叠加（同一笔反复经过不加深） | 已有 | 覆盖度取最大值后乘不透明度 |
| 笔刷 | 防抖（SAI 手ブレ補正 / PS 平滑） | 本次 | 0–30 级，松手时补齐终点 |
| 笔刷 | Shift + 单击画直线 | 本次 | 从上一笔终点开始 |
| 笔刷 | 压感控制大小 / 不透明度 / 流量 | 后续 | 桌面端 Compose / AWT 不提供数位板压力，需要接入 Windows Ink / WinTab 等原生输入后再做 |
| 笔刷 | 流量、间距、喷枪、笔刷纹理与预设 | 后续 | 当前笔画是连续覆盖形状，不是印章序列；流量 / 间距需要另一套累积模型 |
| 混色 | 模糊笔 | 本次 | 强度即不透明度槽位，可用 `Alt+Shift+[` `]` 调整 |
| 混色 | 涂抹 / 指尖、SAI 水彩与混色笔 | 后续 | 需要沿笔画搬运颜色，与「单笔只落一次」的模型冲突，需单独设计 |
| 橡皮 | 软硬橡皮、保护透明度下用背景色涂 | 已有 / 本次 | 后者为本次 |
| 选区 | 矩形、椭圆、套索、多边形套索、魔棒（容差、连续） | 本次 | 同一个选区工具的五个变体，`1`–`5` 切换 |
| 选区 | 新建 / 添加（Shift）/ 减去（Alt）/ 交叉（Shift+Alt） | 本次 | 也可在选项栏固定组合方式 |
| 选区 | 全选、取消选择、反选、羽化、填充选区、清除选区内容 | 本次 | `Ctrl+A`、`Ctrl+D`、`Ctrl+I` / `Ctrl+Shift+I`、右键菜单、`Alt+Backspace`、`Del` |
| 选区 | 按色彩范围选择 | 本次（部分） | 魔棒关闭「连续」即全图按色选择；没有 PS「色彩范围」的取样预览 |
| 选区 | 选区变换、存储 / 载入选区、快速蒙版 | 后续 | |
| 变换 | 自由变换 / 扭曲 / 液化选区像素 | 后续 | 模型层面的图层变换已有（物体模式），像素级变换未做 |
| 填充 | 油漆桶（容差） | 已有 | |
| 填充 | 不连续填充、保护透明度填充 | 本次 | |
| 填充 | 线性 / 径向渐变（前景→背景或→透明） | 本次 | 同样受选区与保护透明度约束 |
| 吸管 | 取当前图层，Alt 临时吸色，按住拖动连续取色 | 已有 | |
| 吸管 | 取合并后的画面 | 后续 | 需要读取画布合成结果 |
| 视图 | 缩放、空格抓手、适配 / 重置 | 已有 | |
| 视图 | 旋转画布（R）、水平翻转视图 | 后续 | 画布视口目前只有缩放与平移，旋转 / 镜像要贯穿所有浮层与命中测试 |
| 对称 | 左右 / 上下 / 四向镜像 | 本次 | 轴固定在画布中心；可调轴位后续 |
| 图层 | 保护透明度（锁定透明像素，`/`） | 本次 | |
| 图层 | 图层不透明度、混合模式 | 已有 | 属于 Live2D 渲染属性（检查器与关键形），不是绘画内的像素合成 |
| 图层 | 剪切蒙版 | 已有（不同语义） | Live2D 遮罩控制显示；绘画时「只画在下层范围内」用保护透明度或选区代替 |
| 图层 | 绘画专用草稿图层 | 后续 | 会话只编辑一个图层 |
| 颜色 | 色域 + 色相条拾色器、HSB / RGB / 十六进制、色板、最近颜色、前景 / 背景互换（X）、默认色 | 已有 | 默认色另有 `Alt+D` |
| 颜色 | HSV 色轮 | 后续 | 现有为 Photoshop 式方形色域 |
| 撤销 | 每笔一步、跳到任意一笔、应用为一个工程历史节点 | 已有 | |

本次之后仍然最影响「不换软件」的是压感、旋转 / 翻转视图与涂抹类混色；三者分别受限于输入平台、视口架构和笔画模型，需要先做设计再实现。

源码：[RasterPaintEngine](../../../src/main/kotlin/io/github/psd2live/core/RasterPaintEngine.kt) · [PaintSelection](../../../src/main/kotlin/io/github/psd2live/core/PaintSelection.kt) · [PaintStrokeAssist](../../../src/main/kotlin/io/github/psd2live/core/PaintStrokeAssist.kt) · [PaintRasterSession](../../../src/main/kotlin/io/github/psd2live/core/PaintRasterSession.kt) · [PaintSpace](../../../src/main/kotlin/io/github/psd2live/core/PaintSpace.kt) · [RasterPaintCommit](../../../src/main/kotlin/io/github/psd2live/core/RasterPaintCommit.kt) · [WorkspacePaintSessions](../../../src/main/kotlin/io/github/psd2live/application/WorkspacePaintSessions.kt) · [PaintSession](../../../src/main/kotlin/io/github/psd2live/ui/PaintSession.kt) · [CanvasEditor](../../../src/main/kotlin/io/github/psd2live/ui/CanvasEditor.kt)。
