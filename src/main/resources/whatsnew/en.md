## v3.6.0 · 2026-10-20

### New

- **Manual and tutorials**: the help page is laid out like the Cubism manual and tutorial site, each page tagged its relation to Cubism, linked to its Cubism page and ending with its MCP operations.
- **References are hierarchy members**: background, in-stack and foreground references coexist in any number, lock, pin to keyforms, with a continuous opacity slider and Alt+R to show or hide; a reference image and an ArtMesh convert into each other.
- **Mesh along texture structure**: rebuild a mesh's inside along the creases, shading boundaries and pattern lines drawn into its texture, migrating keyforms, paths, weights and glue.
- **Mirror keyforms**: the deform menu mirrors a key onto its opposite key, the opposite-side object or in place; Shift+M mirrors in one step.
- **Drop where you mean it**: the drop position decides the action (material, hierarchy insert, placed layer, reference, image replacement or library-only import); a dropped CMO3 opens directly.
- **Materials and CMO3**: every layer has its material and links no longer break silently; an imported CMO3 gets its atlas packed from the current layers.
- **Compressed history tree**: consecutive linear versions share one card, a toolbar toggle restores the per-node view, and double-clicking a card checks out the last step of its run.

### Improved

- **Edit before generating**: a Layout project not generated yet edits meshes, deformers, parameters and the hierarchy like any project, and exports directly.
- **Smoother**: the edit canvas paints on its own, parameter drags and pose-less panels no longer recompose every frame, and PSD import prepares in the background (#50).
- **Materials panel**: shows usage counts, lists the layers using a material on hover, and its menu edits tags, sets the default reference and selects the using layers.
- **Interior panel**: uses the same toolbar and folding sections as the other inspectors, with the audition as sliders and a 2D pad.
- **Freeze diagnostics**: the console goes to a log file and a UI freeze over 5 seconds writes a thread dump (#52).

### Fixes

- **MOC3 user data**: exporting user data writes `userdata3.json` and references it from `model3.json`.
- **Texture binding**: imported images and reference layers no longer bind their textures to a placeholder tile.
- **Reference layer placement**: a Layout reference layer draws at its own canvas rectangle.

## v3.5.2 · 2026-10-18

### Fixes

- The window no longer freezes or goes black when the GPU cannot multisample or a canvas GPU draw fails; it falls back to a compatible way of drawing (#48).

## v3.5.1 · 2026-10-17

### New

- **Interior shaping**: give a mesh outline-preserving surface changes inside it (bulge, press, folds), try them with the pointer; saved with the project and exported.
- **Unity prefab export**: Export → Unity Prefab… bakes parameters and motions into blend shapes and an Animator, so the character folder drops into a Unity project.
- **Reference layers**: keep a layer as reference (Ctrl+Alt+R): it stays on the canvas but out of the rig and exports.
- **Layout manifest**: open a Layout project from a psd2live-layout manifest; layers not generated yet move to the front or back, or a step forward or backward.

### Fixed

- Fractional draw orders in CMO3 export, CMO3 imports that hung or drifted in draw order, 3.5.0 material and generation regressions, and a crash in the Animation workspace.

## v3.5.0 · 2026-10-15

### New

- **Start, Help and Settings are pages**: all three are pages at the head of the tab strip instead of dialogs. Their panels drag, tab together, float and close like the editor's, the arrangement is remembered, and "Reset layout" restores it. F1 opens Help, Ctrl+, opens Settings.
- **Material library**: import images, PSDs, the layered images inside CMO3 files and SVGs; sort them into folders, tag and preview them, and export a folder as a PSD. Materials are saved with the project. {tutorial=LAYOUT}
- **Place first, then generate**: a Layout project starts from an empty canvas; drag materials onto it and arrange them before generating the rig. Afterwards keep adding images and changing tags, and "Regenerate" merges them in while your edits stay. {tutorial=LAYOUT}
- **Material window**: double-click a material to view and paint it in its own window, with the same tools as the canvas's paint mode; every layer using it updates together. {tutorial=PAINT_MODE}
- **Semantic tags**: the layer table's type, part and side become tags that combine, and you can add your own.

### Improved

- **Long hair bends with a head tilt**: hair below the chin turns back as the head tilts (new tuning "Long hair hold on tilt", 80% by default) instead of swinging out whole.
- **Reference image and close-ups**: a material can be laid over the edit canvas as a reference, kept out of generation and export; close-up materials can be cropped from it.
- **Painting is no longer bounded by the mesh**: paint anywhere on the canvas; what falls outside the mesh becomes new layers when applied.
- **Context menus and dropdowns match**: every panel's menus share one look and animation, opening from where you clicked.
- The editor's "Select" mode is now called "Object" mode.

## v3.4.5 · 2026-10-13

### Fixed

- **Saving and recovery**: saving, closing and crash recovery agree on the project's state, and consecutive pose edits commit in order.
- **Import sources kept**: projects keep the imported PSD / CMO3 content and no longer depend on the outside file.
- **Empty layers**: PSDs with empty layers save and reopen normally.
- **Separate-mesh check**: a failed check shows its error, and the check stops after 60 seconds.

### Improved

- **Examples ship separately**: example materials, projects and outputs moved to their own repository, downloaded when needed.

## v3.4.4 · 2026-10-13

### Fixed

- **Saving and closing**: projects save and close again after long editing sessions.
- **Undo and redo**: a field being typed in is committed before the history moves. {tutorial=PROJECT_HISTORY}
- **Import and deformers**: 16-bit PSDs, relative layer masks and TIFF orientation read correctly.
- **Export and motions**: a failed export restores the previous files; GIF frame timing and Bézier motion trimming are fixed. {tutorial=LIVE2D_BRIDGE}
