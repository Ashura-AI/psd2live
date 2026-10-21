# Deformer and parameter reference

[Documentation](../../README.md) · [中文](../../zh/spec/DEFORMER_AND_PARAMETER_SPEC.md) · [日本語](../../ja/spec/DEFORMER_AND_PARAMETER_SPEC.md) · [PSD preparation](PSD_LAYER_SPEC.md)

This page describes generated defaults and editing conventions. Actual objects depend on artwork, configuration and edits. Read the hierarchy, parameter panel or MCP `workspace_inspect` output for the current project.

## Structure

The body's root is `DeformBodyXY`, which spans only the body's own parts (torso, clothes, arms, skirt, tail) and the neck the head turns on, not the hair or the legs. Body X turns the upper body above the waist as one solid about its centre line, the waist as far as the chest, easing out over the hips: the front of the chest moves toward the turn, the shoulder coming forward is drawn a little larger and lower, and the neck, near the axis, hardly moves, so the head stays in the middle of the body. Body Y opens the shoulders a little going down and straightens the back going up. The whole body also moves with the hips: a little sideways on Body X, down onto knees turned in on Body Y down, and a little higher on straightened legs on Body Y up. Below it come `DeformBodyLean` (two parameters. Body Lean: the body bows in three dimensions as a solid with depth and is drawn in perspective, the pelvis tilting a share of the lean about the hip joints and carrying the skirt, the spine bending along an arc across the waist to the full lean, so the waist is not squeezed on its own; leaning in, the chest foreshortens and comes down, a little larger, and the head rides the neck down keeping its shape while the face turns down; leaning back, the body is drawn smaller and the face turns up a little. Proportion (Chibi): at positive values the head grows from the neck as one piece, the chest widens, the torso, the arms, the legs and the skirt shorten and the upper body comes down onto them, toward a chibi; at negative ones the other way, toward a taller figure; the feet stay. Without a skeleton each arm also gets `DeformArmHang_L/R` on Body X and both of these, so it moves and scales with its shoulder as one hanging piece instead of bending with the body; on Body X the arm is not drawn larger and lower on the side the body turns toward but swings about 4° the other way about its shoulder, the hand trailing the body. With a skeleton the upper arm rotation deformers are keyed on Body X too, taking out the turn they inherit and swinging the same way) and `DeformBodyZBreath` (body Z / breath), with head rotation and the head-follow container below those. A standing full-body figure has a second root beside it, `DeformLegs` (on body X, body Y and Proportion), which carries only the legs and shoes; the feet never move under any body parameter. Facial lattice, contour and feature displacement organize eyes, brows, nose, mouth and ears. Front/back hair have independent follow and physics branches. Region groups, iris preservation/gaze and optional lips add further nodes; the live hierarchy is authoritative. How far the generated rig moves each part at the parameters' full values is set in the model presets under Rig Values or through MCP `settings.rigTuning`: the head turn and the face, the features (eyes, iris, gaze, blink, brows, nose, mouth, ears), the hair's follow and sway, the face turning down and up with the lean, and the body parameters (the turn, the arm swing, the hip shift, sink and rise, the knees, the back and shoulders, the lean and the pelvis share, each proportion change, Body Z and breath, the torso depth and the view distances); the values seldom changed fold under Advanced there. The defaults are as described above, and Head range and Body range scale the head's and the body's as a whole.

## Coordinates and keyforms

Canvas coordinates start at the top left, with X right and Y down. Geometry is evaluated in parent-local space; normalized Warp coordinates and Rotation-local coordinates differ. Rest meshes and keyforms also have conversion-specific conventions, handled by restMeshesToCanvasSpace and format conversion.

Direct bindings define an object’s own axes; ancestor motion is inherited. A keyform is a shape at parameter coordinates, not an animation time frame. Motions and physics drive parameter values. A newly created parameter needs bindings and shapes to produce motion.

## Generated deformation

Head X/Y endpoint and midpoint combinations form nine poses, with estimated initial head tilt as a local reference. A facial lattice and regional corrections handle features, iris preservation, masks and mouth/ear behavior. The legs bend by two-bone IK through hip, knee and ankle, the knee bending toward the viewer so a front-facing thigh shortens instead of swinging out, so legs drawn on one layer still bend at their own knees. The head rotation and skeleton rotations hung straight from the body take Body Lean and Proportion as axes and scale with them. Legs split and skinned to leg bones get a stance warp on body X/Y under each leg bone that carries meshes, placing them where the legs warp puts the legs: a rotation deformer passes on only its pivot and angle, so the legs warp's bend never reaches below it. Tilt and breath are separated: body Z leans everything above the waist about it, each row only shifting so it keeps its width; breath is placed by the drawn shoulder and waist lines, lifting the shoulders with the head and arms they carry and widening the chest a little, with nothing below the waist moving. Hair follows the head independently of strong facial deformation; blink physics may drive iris form.

**Brows through the bangs** (`SeeThroughLayers`): each eyebrow the front hair covers gets a copy `<original id>_SeeThrough` that shares its original's parent deformer, draws just above the bangs that cover it and below the next mesh, shows at partial opacity (`seeThroughOpacity`, 50% by default) and is clipped by those bang meshes, so it appears only where the hair is. This is the usual setup in anime-style Live2D models: duplicate the brow layer, raise it above the bangs, lower its opacity and clip it with the bangs. The copy lists first in the bangs' part and cannot be picked on the canvas. Its masks, draw order and opacity belong to the base rig and edit like any generated object's; its parent, mesh, geometry keyforms, blend shapes, colors, visibility and texture are taken from the original after the generated overrides on every replay (the original's opacity keyforms scaled by the copy's opacity), so the copy needs no keyforms of its own and follows every deformation, canvas edit and generated override of its original. A copy whose original or every mask is gone is removed. Eye lines (lashes and closed-eye lines) can be copied too. Brows are on by default and eye lines off: brows carry expression and are commonly hidden by the bangs in anime art, so showing them through is the common practice, while eyes are covered less and showing eye lines through is a matter of style that costs a clipping mask per copy. A brow already drawn above its bangs is not copied. The settings `seeThroughBrows`, `seeThroughEyes` and `seeThroughOpacity` are generation settings and merge by regeneration when changed; a project saved before them reads them as off and reopens with the same rig.

These are rigging presets, not general 3D reconstruction. Layering, anchor estimates and extreme angles need inspection. Exact curve constants and lattice divisions belong to the implementation and should be checked there rather than copied into an unversioned mathematical promise.

## Default parameters

The table follows StandardParameters. Mesh-only mode, disabled deformers or missing parts may change the actual set. User parameters and variants can extend it.

| ID | Range | Default |
| --- | --- | --- |
| `ParamAngleX` | -45…45 | 0 |
| `ParamAngleY`, `ParamAngleZ` | -30…30 | 0 |
| `ParamBodyAngleX`, `ParamBodyAngleY`, `ParamBodyAngleZ`, `ParamBodyLean`, `ParamProportion` | -10…10 | 0 |
| `ParamEyeLOpen`, `ParamEyeROpen` | 0…1 | 1 |
| `ParamEyeBallX`, `ParamEyeBallY`, `ParamEyeBallForm` | -1…1 | 0 |
| `ParamBrowLY`, `ParamBrowRY` | -1…1 | 0 |
| `ParamMouthForm` | -1…1 | 0 |
| `ParamMouthOpenY`, `ParamBreath` | 0…1 | 0 |
| `ParamHairFront`, `ParamHairBack` | -1…1 | 0 |

## Tool comparison and gaps

The table compares the deformation and keyform editing tools with the public features of Cubism Editor, item by item (the Cubism column uses the generic names of its manual). Status: **Existing** was implemented before; **New** was added in this round; **Later** is tracked in the [roadmap](../../zh/ROADMAP.md). Mesh topology editing (adding and removing points, connecting) and the canvas handles of interior shaping are tracked separately and left out here.

| Area | Cubism feature | PSD2Live | Status |
| --- | --- | --- | --- |
| Selection | Click, box, lasso and brush selection; select all / invert / linked | Select (V), Lasso (L), Brush select (W), `Ctrl+A` / `Ctrl+I` / `Shift+L`, hold Z for temporary select; meshes select by vertex / edge / face | Existing |
| Transform | Transform box: move, scale, rotate, anchor | Transform tool (T): drag the body to move, corners / edges to scale, the ring to rotate, a draggable anchor; Alt from the center, Shift proportional or 15°, X / Y axis constraint; precise numeric transform in Tool details | Existing |
| Soft selection | No direct counterpart (the deform brush is used instead) | **Soft selection** (O): dragging or transforming selected points carries the points around them by a falloff of their distance, with an adjustable radius and curve, shown as red rings; committed as an ordinary geometry edit | New |
| Deform brush | Deform brush (push, smooth, inflate / shrink) | Push (B), Smooth (Shift+B), Inflate / shrink (I); circle / line / rectangle tips, radius, hardness, strength, 8 falloffs, connected only | Existing |
| Temporary deform tool | Disposable path / lattice / free transform | **Temporary deform (lattice)** (Shift+T): a disposable 1×1–4×4 lattice over the selected points or the whole object; dragging its points carries them by bilinear interpolation, one history step per release; scale and rotate with the Transform tool, bend along a path with deform paths | New |
| Warp deformer | Create, transform box, grid division, Bezier editing | Create Warp (C, sized from the selection or the keyform envelope, with an attach point); vertex / Bezier levels; `warp_set_topology` changes the divisions and migrates keyforms | Existing |
| Rotation deformer | Create, drag the origin, turn by the handle, scale | Create rotation (R); drag the origin to move, the end to turn (Shift 15°), Alt + end to scale | Existing |
| Deform path | Deform path | Deform path (P / D): Edit mode rebinds, Deform mode writes shapes, baked into mesh keyforms on export | Existing |
| Glue | Glue and its weights | Glue (G): glue brush, weight brush, re-merge by distance | Existing |
| Keyform copy / paste | Copy and paste form | Deform context menu **Keyform → Copy / Paste** (`Ctrl+Alt+C` / `Ctrl+Alt+V`); copying fixes every selected object's shape and channels at once; a paste writes moves (a mesh's from its rest, a Warp's or rotation's from its default key) onto the same object or one of its kind with the same triangles (a Warp: rows and columns) under the same parent, only onto the selected points when there are some; MCP `keyform_apply op=paste` (`op=copy` copies a whole key with channels) | New |
| Blend form, paste special | Blend forms by a ratio; paste only checked properties, flipped | **Paste with options…**: any of shape, opacity, draw order, multiply and screen color, a blend ratio, a horizontal / vertical flip (a Warp's columns / rows reversed, a symmetric mesh's vertices paired by their mirrored rest, a rotation's angle change reversed); MCP `paste` `channels`, `ratio`, `flip` | New |
| Targets for pasting several forms | Confirm and change source-to-target pairs | **Paste with options…** lists source → target rows to change or exclude; a plain paste pairs automatically | New |
| Keyform reset | Reset form | **Reset to the default-key shape** (`Ctrl+Alt+X`), for meshes also **Reset to the mesh rest shape**, optionally on the selected points; MCP `op=reset` | New |
| Keyform interpolation | Make a middle shape from the end keys | **Interpolate from neighbouring keys of (parameter)** (`Ctrl+Alt+I`) remakes the current key between the keys on either side; MCP `op=interpolate` | New |
| Key from the current shape | Add a key at the current value | Add a key on an empty spot of the Parameters panel, taking the interpolated shape there; MCP `keyform_apply op=seed` samples the interpolated shape at a key | Existing |
| Flip / mirror | Flip form, symmetric copy | **Mirror keyform** (`Shift+M`): to the opposite key, to the opposite object, in place, all keys | Existing |
| Symmetric editing | Live symmetry while editing | Only mirroring after the edit | Later |
| Multi-key editing | Change draw order, opacity and colors of many keyforms at once | **Multi-key editing…**: pick a range by parameter key values and overwrite / add / multiply / screen opacity, draw order, multiply and screen color; MCP `keyform_apply op=multi_key`. Editing geometry on several keys at once is still missing | New |
| Corner synthesis | Generate the four corner shapes | Corner shapes are made one by one | Later |
| Interpolation types | Extended interpolation and others | Linear between keys | Later |
| Restore deformer shape | Revert to the recorded original shape | **Restore every key to the default-key shape**: each key of the object back to its default-key shape (no separate original is recorded); MCP `op=reset all_keys=true` | New |
| Locking / hiding points | Lock and hide objects | Layers can be hidden and the canvas can show only the selection; locking or hiding single points is missing | Later |
| Skeleton | Bones (baked where Cubism has no counterpart) | Skeleton pose (Shift+W, auto / FK / IK), Edit skeleton (Alt+W, edit / new / extrude / bind / weights), baked into parameters and deformers on export | Existing |
| Simulation weights | No direct counterpart | Weight brush (Shift+V), weight gradient (Alt+V) | Existing |
| Parameter keys | Add, delete and move keys | Add, drag, retype, delete and distribute keys in the Parameters panel; unbind the selection from a parameter | Existing |

Keyform copy / paste, reset, interpolation and multi-key editing expand into ordinary `set` entries on commit, as the mirror does: replay never reads another key, the GUI and MCP share the one `keyform_apply` command, and a geometry `set` on keyforms a generator owns is recorded as a generated override, while channel `set`s are ordinary channel keyforms. Soft selection and the temporary lattice only decide where the points of one drag go; what is committed is that canvas geometry edit.

## Validation

Inspect neutral, endpoints, combined angles and intermediate values, including parent/local interactions. Pipeline geometry diagnostics and export readback are checks, not proof of all poses or identical editor behavior. Target versions may require feature reduction.

[RigBuilder / StandardParameters](../../../src/main/kotlin/io/github/psd2live/core/RigBuilder.kt) · [Pipeline](../../../src/main/kotlin/io/github/psd2live/core/PSD2LivePipeline.kt) · [PuppetModel](../../../umamo/src/main/kotlin/org/umamo/runtime/model/PuppetModel.kt) · [Architecture (中文)](../../zh/spec/RUNTIME_EXPORT_ARCHITECTURE_AND_GAPS.md)
