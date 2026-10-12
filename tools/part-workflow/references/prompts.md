# 提示词

模板在 `p2lwf/templates/`：`whole_part.txt`（整部件生成，主路径）与 `repair.txt`（局部修复）。`p2lwf task` / `repair-task` 会填好变量。以下是写法要点和各类部件的 `--desc`。

## 通用写法

- 方向一律用 **viewer's left/right**（画面左右）。PSD 命名再换成角色自己的左右（`-r` 是角色右侧，也就是画面左侧）。
- 用 "ONLY: … no X, no Y" 逐个排除不要的部件。
- **要求补全**："including every portion hidden behind other things … continue it smoothly to its natural root"。
- **防截断**：被遮挡的一端 "rounded or tapered as the anatomy requires, never a straight cut-off line, never fade to transparent"。
- **防污染**：不要画上层对它的影响（投影、勒痕、吊袜带），"draw it as if alone under flat lighting"。
- **风格不迁移**："same art style, line weight, colors, shading and lighting … do not redesign, recolor or restyle"。
- **不要求位置不变**：生成工具做不到像素级对位，反而会因为"必须同位置"而变形。位置与大小由 `ingest` 自动对齐。
- 背景："Plain pure flat WHITE background (#FFFFFF), no shadow, no gradient"。白色为主的部件改用 `task` 建议的颜色。
- 防作弊（局部修复）："the repaired pixels must come from your image generation output"。
- 缺陷具体时把缺陷写成否定词：no lumps, no vertical ridge, no notches, no fragments, no grey ghost。
- 一侧已画好时，把它镜像后作为 `reference` 附上，并写 "match its outline, shading and skin tone"。

## 两种任务模式

- `--mode whole`（默认）：参考是带上下文的母版裁切，让模型自己找出这个部件并画完整。上下文足，但模型会重新设计比例，手臂、腿这类长部件容易出现肩到手、膝到踝的比例漂移，相似变换对不上。
- `--mode complete`（完成式）：主参考是**只含该部件、被遮挡处残缺**的原画抠图，整图裁切只作上下文；提示词要求保持可见部分的姿势、比例和长度不变，只把残缺的一端补完整。仍是一次性整部件生成，不是遮罩补画：没有回贴像素，风格一致性由一次生成保证；可见部分的比例由输入锚定，所以更容易对上。
- 先用 `whole`；出现比例漂移（`POOR_FIT`、肩端或手尖偏位）时改用 `complete` 重做。两种都要跑指标，不要预设哪种更好。

## 各类部件的 `--desc`（英文，按需改）

| 部件 | 描述 | 额外规则（`--rule`） |
| --- | --- | --- |
| 手臂（袖下） | `the character's bare left arm: shoulder, upper arm, forearm and open hand, as one complete arm` | 肩端画成自然圆弧收口，被袖子遮住的部分补全 |
| 袖子 | `the large detached wide sleeve on the character's left arm, complete including its inside` | 不含手臂皮肤 |
| 腿（肤色大腿） | `the bare skin of the character's left thigh, from the hip crease down to just above the knee, one single leg` | 顶端画到大腿根，圆润自然；不要吊袜带、袜口、裙摆投影 |
| 腿（丝袜） | `the black thigh-high stocking on the character's left leg, full length including the top band` | 只含袜子本体，不含吊袜带 |
| 吊袜带/挂饰 | `the garter straps and charms of the left leg, laid out as they sit on the leg` | 不含腿 |
| 靴 | `the character's left boot, complete including the inside hidden by the stocking` | 一只 |
| 后发 | `the hair mass behind the head and shoulders` | 无脸、无耳、无前发；被头和脖子挡住的区域用连续头发补满 |
| 前发 | `only the front hair: bangs and side locks hanging in front of the face` | 完整发束，被脸挡住的部分不需要 |
| 脸底层 | `the bald face skin: forehead, temples, cheeks, chin, complete oval` | 无眼、眉、鼻、嘴、发、耳、脖子 |
| 脖子 | `the neck and collarbone, extended upward as a complete column behind the jaw` | 上缘是被下巴挡住的平直边，不要 V 形缺口；不含衣领、颈饰 |
| 眼白/虹膜/睫毛 | `only the two eye whites` / `only the two irises as COMPLETE circles including the part under the eyelids` / `only the upper eyelashes and eyeliner` | 虹膜补全整圆 |
| 嘴 | `only the closed gentle-smile mouth` / `only an open smiling mouth with teeth and tongue, same size and style` | 闭嘴与张嘴各一次 |
| 耳 | `the fox ear on the viewer's right, including the root hidden under hair` | 含耳饰 |
| 完全看不见的部件 | 先描述在参考里看不见，再说明位置关系 | 尺度要靠摆放校正 |

## 取舍经验

- 一次生成只含**一个部件的一侧**。两条腿同框时模型会把它们画在一起，或在中间画出竖棱。
- 被内容审核拒绝时，不要改写绕过：换另一个生成工具，或改用镜像另一侧、取母版像素，并在报告写明。
- 带上下文的整图参考能让模型理解部件在人物中的位置，也更不容易被审核拒绝；孤立的裁切加遮罩更容易被拒。
- 腮红、半透明阴影不要做成抠图层；白底出图后以正片叠底导入。
