自动骨架更准确：沿网格识别肢体，尾巴和翅膀可在分叉处分支，肩、髋、肘、膝按人体结构校正；下载文件统一命名。

## 主要更新

### 改进

- **自动骨架更准确**：尾巴和翅膀在持续分叉处分支（如鱼尾、双尾），主干沿较粗的分支走；身体框架取自实际网格，忽略空的模板图层；肩膀落在手臂末端，隐藏的髋部按脊柱对称补出；肩、髋、肘、膝的位置与比例按人体结构校正。
- **下载文件统一命名**：`PSD2Live-<版本>-<系统>-<架构>[-<类型>][-ffmpeg]`，安装包带 `setup`，架构统一为 `x64` / `arm64`。

<details>
<summary>English</summary>

The auto skeleton is more accurate: it reads limbs through the mesh, forks tails and wings at a branch, and holds shoulders, hips, elbows and knees to human placement. Download files share one naming scheme.

## Highlights

### Improvements

- **More accurate auto skeleton**: tails and wings fork at a persistent branch (flukes, second tails), the trunk following the heavier branch; the body frame comes from the meshes and empty template layers are ignored; shoulders sit at the arm's end, hidden hips are mirrored about the spine; shoulders, hips, elbows and knees are held to human placement and proportion.
- **One naming scheme for downloads**: `PSD2Live-<version>-<os>-<arch>[-<kind>][-ffmpeg]`, installers marked `setup`, architectures `x64` / `arm64`.

</details>
