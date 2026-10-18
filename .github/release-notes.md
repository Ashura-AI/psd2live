修复版本：解决部分显卡上窗口卡死或黑屏的问题。

## 修复

- **显卡兼容**：显卡驱动不支持多重采样（如部分 Intel 核显）时，窗口不再卡死或黑屏，改用单重采样绘制；画布 GPU 绘制失败时改用软件绘制（#48、#49）。

<details>
<summary>English</summary>

A fix release for the window freezing or going black on some GPUs.

## Fixes

- **GPU compatibility**: when the driver cannot multisample (some Intel integrated GPUs), the window no longer freezes or goes black and draws single-sampled instead; when a canvas GPU draw fails, the canvas paints in software (#48, #49).

</details>
