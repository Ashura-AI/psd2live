## next

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
