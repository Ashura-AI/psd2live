@@ new-features | Introducing new features
@@ new-feature-introduction-title | Release History
Release notes for each version appear directly under **What's new** on the Start page, listing the changes you will notice version by version, newest first.
## Viewing the release notes
1. Open the Start page (**File → Start Page**, or **Go to Start** on the canvas when no project is open).
2. In the "Learn" group of the left-hand list, select **What's new**.
3. The middle lists new features, improvements and fixes by version; the details panel on the right lists the versions, and clicking one jumps to it.
4. Entries that have a matching interactive tutorial carry **Show me in a tutorial**, which opens that tutorial chapter directly.
5. Click **Full changelog** to open the releases page with the complete notes for every version.
## Differences from Cubism
- Cubism's release history is on the manual pages of the official website, listing features and fixes by version.
- PSD2Live's release notes ship with the program, appear on the Start page and can open interactive tutorials directly from an entry; the full log is on the GitHub releases page.
*> For where the Start page is and what else it contains, see [[home]].

@@ before | Before you begin
@@ workflow | Production Flow
PSD2Live automatically generates a first working model from a layered PSD, then lets you refine shapes, rig, add physics and simulation and animate in the same workspace, and finally export to Cubism or other formats. Unlike Cubism, where you build everything by hand before it moves, the very first step here already gives you complete meshes, deformers, parameters, motions and physics.
## Basic flow
1. **Prepare the PSD**: split parts into layers and give layers recognizable names where possible (see [[precautions-for-psd-data]]).
2. **Choose presets and generate**: under **Generate from PSD** on the Start page, pick a preset (Minimal / Default / Full), then drop the PSD onto the window or press {key:OPEN_PSD}. After generation you enter the editing workspace automatically (see [[psd-import]], [[model-presets]]).
3. **Check the recognition results**: in the **Layers** panel, check each layer's tags (part, side) and variant settings, and correct any that are wrong (see [[layers-palette]]).
4. **Preview**: choose **Preview** from the canvas mode menu, or switch to the "Preview" workspace, and check motions such as idle and blinking, plus physics (see [[preview-canvas]]).
5. **Edit as needed**: on the edit canvas, change keyforms, meshes and pixels with the Deform, Edit, Skeleton, Paint and other modes; adjust sway in the Physics and Simulation panels (see [[canvas-modes]]).
6. **Save the project**: {key:SAVE_PROJECT} saves a `.psd2live`, which contains the source image, all edits and the branching history.
7. **Export**: {key:GENERATE} opens **Export Live2D model…** to export `.moc3` / `.cmo3`; other formats are under **File → Export as**.
{tutorial=BASIC}
## Two other starting points
- **Layout project**: start from an empty canvas, import image, PSD or SVG materials, place and tag them, then generate (see [[layout-project]]).
- **Import from CMO3**: open an existing Cubism model and keep editing it; no presets are generated (see [[cmo3-import]]).
## Regeneration and edits
Changes made on the canvas are saved as replayable edits. When you later change layer tags or model presets, or upgrade the program, the model is regenerated from the source image and your edits are replayed in order, so regeneration never loses your edits. To adopt the results of a newer generator, use **Model → Update Generated Rig** (see [[update-generated-rig]]).
## Differences from Cubism
- Cubism: split the material → create meshes, deformers and parameters by hand in the Editor and build keyforms → animate in the Animator → export.
- PSD2Live: after the material is split, the generator produces a first version in one go; modeling, physics and animation happen in one program and one project, and what is saved is the replayable edits and history rather than the final model itself.
- When delivering to Cubism, export `.cmo3` to keep refining in Cubism Editor; if you only need the runtime, export the `.moc3` file set.
*> If you have Cubism experience, start with the terminology chapter of the interactive tutorials.
{tutorial=LIVE2D_BRIDGE}

@@ cubism-editor-activation | Launch Cubism Editor
PSD2Live is open-source software: once downloaded and installed, every feature is available. There is no license activation and no split between free and pro editions.
## Installing and launching
1. Download the package for your platform from the GitHub releases page (**Help → On the web → Releases**).
2. The Windows installer and portable build bundle a Java runtime, so just install or unpack and run; Linux packages are provided as Deb and tar.gz.
3. The first launch shows the Start page; press {key:OPEN_HELP} to open the tutorials on the Help page.
## Cubism SDK preview
Editing, previewing and exporting `.moc3` / `.cmo3` do not depend on the Live2D SDK. The preview canvas can additionally play the exported model through the Cubism SDK, to compare rendering and physics with the official runtime; this needs the Cubism SDK native libraries, and when no usable library is available (for example on Windows ARM64) the preview uses the PSD2Live runtime.
## Differences from Cubism
- Cubism Editor needs you to sign in or enter a license to activate it, and the FREE edition has feature limits.
- PSD2Live has no activation step and no feature tiers; the Cubism SDK is only an optional way to preview.
!> Live2D, Cubism, .cmo3 and .moc3 are trademarks of Live2D Inc. PSD2Live is not affiliated with Live2D Inc. **About** on the Help page shows the version and runtime environment.

@@ file-type-and-extension | File Types and Extensions
The main files PSD2Live reads and writes are listed below. The project itself is `.psd2live`; the rest are mostly imported materials or exported deliverables.
## Projects and reports
| Extension | Description |
|---|---|
| `.psd2live` | PSD2Live project. An unencrypted ZIP containing the source PSD or CMO3, materials, settings, all edits and the branching history; it does not depend on the original PSD path |
| `.psd2live.json` | Report written alongside an export (can be turned off in the export window) recording losses and warnings; it is not a project |
## Import
| Extension | Description |
|---|---|
| `.psd` | Layered PSD, the source image for generating a model |
| `.cmo3` | Cubism Editor model project; can create a new project or replace the current model |
| `.png` `.webp` `.tiff` `.bmp` | Added as a single layer by dropping onto the hierarchy or with **Import Layer…** |
| `.svg` and other images | Imported into a Layout project's material library |
## Export
| Extension | Description |
|---|---|
| `.cmo3` / `.can3` | Cubism Editor model project; when there are motions, a `.can3` (Animator project) with the same name is also written, and the two must stay in the same folder |
| `.moc3` `.model3.json` etc. | Cubism runtime file set: model, settings, physics, motions, display info and textures, loaded from `.model3.json` |
| `.vtube.json` | Exported together with the `.moc3` file set for VTube Studio |
| `.p2lrt` | PSD2Live runtime model, also used by the web player |
| `.p2lunity` | Model data exported for a Unity prefab |
| `.psd` | Layered PSD in a given pose, or the source PSD with generated layers |
| `.png` `.gif` `.apng` `.webp` `.mp4` `.webm` `.mov` | Image sequences, sprite sheets, animated images and video |
## Differences from Cubism
- In Cubism the model project is `.cmo3` and the animation project is `.can3`; PSD2Live saves projects in its own `.psd2live`, and `.cmo3` / `.can3` are only import or export formats.
- Exporting `.cmo3` does not write generator settings or the edit history; to keep editing in PSD2Live, save a `.psd2live`.
*> For the contents and limits of each export format, see [[export-moc3-motion3-files]] and [[exporting-images]].

@@ fault-map | What to Check When the Editor/Viewer Does Not Start Properly
If PSD2Live fails to start, shows a broken display or errors while running, work through the table below.
## Common cases
| Symptom | What to check |
|---|---|
| The edit canvas is black, flickers or draws incorrectly | In **Settings → Canvas Interaction**, tick "Draw the editing canvas and the atlas page in software"; the same place shows whether GPU or software rendering is in use. If the graphics card does not support multisampling or GPU drawing fails, the program switches to a compatible path automatically |
| The preview canvas cannot use the Cubism SDK | Without usable Cubism SDK native libraries, switch to the PSD2Live runtime under **Preview runtime** on the preview canvas; on Linux, pure Wayland without XWayland, or musl systems, fall back automatically |
| A panel has disappeared | Show it again from the **Windows** menu at the right of the workspace tab bar, or choose **Reset layout** |
| The model is not visible on the canvas | Press {key:FRAME_VIEW} to frame the selection or {key:RESET_CAMERA} to reset the camera, then check layer visibility |
| The program quit unexpectedly | On the next launch it asks whether to restore unsaved edits (see [[autosave]]) |
| Settings or interface state look wrong | Click **Reset Defaults** at the bottom of the Settings list; or, with the program closed, run it once with the `--clear-user-data` argument to clear settings and local data |
## Collecting information
1. Open the **Logs** panel, filter errors by level and source, and click **Copy Log**.
2. In **Settings → Files & data**, find the location of the **Error logs**; here you can also clean up leftovers that earlier crashes left in the system temp folder.
3. **About** on the Help page shows the version and runtime environment.
4. Report the problem via **Help → On the web → Report an issue**, including the version, operating system, steps to reproduce and relevant materials.
## Differences from Cubism
- Cubism's checklist is mainly about licenses, graphics drivers and OpenGL settings.
- PSD2Live has no license; display problems are diagnosed with the software rendering option, and if the Cubism SDK preview is unavailable you can use the bundled runtime instead.

@@ convert-data-cubism-2-to-3 | Convert Data From Up to Cubism 2.1 to Cubism 3 or Later Format

@@ performance | Performance When Working With Models in the SDK
The runtime cost of an exported model comes mainly from mesh vertices, the number of keyforms, texture size and masks. PSD2Live offers several places to inspect and control these factors during generation and export.
## Checking and adjusting
| Factor | Where to see or adjust it |
|---|---|
| Mesh vertex count | The **Mesh** panel shows the vertex count and edge length of the selected layer and lets you change the global defaults or per-layer mesh overrides; **Layers** info also shows the vertex count |
| Texture size and video memory | "Texture" workspace: with no layer selected, the Texture panel lists each page's size, tile count, usage and video memory (RGBA); set the atlas budget (page size, maximum pages and padding) at the top right, and adjust texture density per layer (see [[texture-atlas-edit]]) |
| Number of keyforms | When baked simulation modes are written as keyform axes, a mesh's keyform count is multiplied by the number of key points in the mode; choosing "Blend shape" (Cubism 4.2+) avoids this, and "Auto" uses blend shapes when there would be more than 64 keyforms (see [[simulation]]) |
| Target version | On export, features the chosen SDK target does not support are stripped and the downgrades are listed (see [[target-version-selection]]) |
## Preview cost inside the editor
The preview canvas can limit its frame rate under **Frame rate cap** (or follow the display); "Advanced mode" in the preview menu applies only to the PSD2Live runtime and runs cloth and hair simulation live, which costs more.
## Differences from Cubism
- Cubism's manual explains the factors that affect performance in the SDK, and authors control meshes and textures themselves in the Editor.
- In PSD2Live, meshes, atlases and simulation bakes are produced by the generator from settings, and the performance-related numbers are gathered in the Mesh panel, the Texture workspace and the Simulation panel; the export report `.psd2live.json` records downgrades and losses.

@@ editor | About the Editor
@@ glossary | Live2D Glossary
PSD2Live keeps most Live2D terms, but some names differ in the interface and there are a few concepts unique to this program. The tables below list the common correspondences.
## Cubism terms
| Cubism | PSD2Live | Description |
|---|---|---|
| ArtMesh | Drawable / layer | A PSD layer becomes a drawable with a mesh on import |
| Warp Deformer | Warp deformer / Warp | Deforms its children with a grid of control points |
| Rotation Deformer | Rotation deformer / Rotation | Rotates its children around an origin |
| Parameter | Parameter | A value that drives deformation and variants |
| Key | Parameter key | A position on a parameter where a shape is recorded, shown as a dot in the Parameters panel |
| Form (shape at a key) | Keyform | The shape recorded at one key; shapes between keys are interpolated |
| Blend shape | Blend shape key | Shown as a square dot in the Parameters panel; adds a shape difference on top of the base shape |
| Glue | Glue | Points shared by two meshes |
| Parts / Deformer palette | Hierarchy | The parent-child structure of deformers and drawables |
| Model project (.cmo3) | Project (.psd2live) | `.cmo3` is only an import and export format |
## Concepts unique to PSD2Live
| Term | Meaning |
|---|---|
| Model presets | Settings that control the generator's deformation ranges, mouth shapes, motions, physics, simulation and more (see [[model-presets]]) |
| Tags | A layer's part, side and so on, which the generator uses to recognize parts |
| Variant | None, Toggle Variant (one parameter shows or hides it) or Switch Variant (one of several on the same parameter) (see [[variants]]) |
| Generated rig | The model generated automatically from the source image and presets; your edits are replayed on top of it |
| Canvas modes | Object, Deform, Edit, Simulate, Skeleton, Paint, Preview (see [[canvas-modes]]) |
| Workspace | A tab at the top, each with its own canvas and panel layout (see [[workspace]]) |
| Deform path | A tool that deforms meshes along a curve, baked into mesh keyforms on export |
| Skeleton / simulation | Bones and cloth and hair simulation, baked into parameters, keyforms and pendulums on export |
| Bake | Converting features Cubism lacks into native deformers, keyforms or pendulums |
| Atlas | Texture pages packing each layer's pixels, with texture density settable per layer |
| Reference layer | A layer shown on the canvas that takes no part in generation or export |
{tutorial=LIVE2D_BRIDGE}

@@ shortcut | List of shortcut keys
The table below lists every shortcut and its current binding. The default is Photoshop-style; under "Presets" in **Settings → Shortcuts** you can switch to Blender-style or Live2D Cubism-style, or rebind items one by one.
## Changing shortcuts
1. Press {key:OPEN_SETTINGS} to open Settings and select **Shortcuts**.
2. Click a key cell and press the new key combination; you can also scroll the wheel or press a mouse button over the cell. {kbd:Esc} cancels recording.
3. If another action already uses it, a conflict is shown; you can add an alternate shortcut for the same action, or restore it with "Reset this shortcut".
!> Switching presets replaces all custom bindings.
## Differences from Cubism
- Cubism's shortcuts are customized in "Shortcut settings".
- PSD2Live provides three presets; the Live2D Cubism-style one adds Cubism Editor's keys on top of the Photoshop layout (such as A for the select tool and Ctrl+E for mesh editing); the wheel with modifiers, the middle mouse button and side buttons can also be bound.
*> Keys shown on every page of this manual follow your current bindings.

@@ home | Home
The Start page is shown at launch and is used to create and open projects and see what's new. After you open, import or create a project, the corresponding workspace opens and the "Start" tab folds away; reopen it from **File → Start Page** or **Go to Start** on an empty canvas.
## Page layout
The Start page is laid out like the Help and Settings pages: a list on the left, the selected content in the middle, and a details panel on the right explaining what the current action will do. The action bar at the bottom of the content holds the main button, which {kbd:Enter} also triggers; the arrow keys move through the list. These panels can be dragged, merged or closed like the editor's panels, and **Reset layout** in the **Windows** menu restores the default.
## List
| Group | Item | What it does |
|---|---|---|
| Open | Open Project… | Opens a `.psd2live` ({key:OPEN_PROJECT}) |
| Open | Recent | Lists recent projects and PSDs as a grid or list, with filtering and search; double-click to open |
| New | Generate from PSD | Choose a preset (Minimal / Default / Full) in the details panel and adjust as needed, then drop a PSD onto the window or click **Generate…** to choose a file; under **Recent PSDs** below, click to preview and double-click to generate |
| New | Layout project | Pick a common size or enter width and height, then click **Create Layout Project** for an empty canvas |
| New | Import CMO3 | Clears the current model and creates one from a `.cmo3`; when a model is open you can also **Replace with a CMO3…** |
| Learn | What's new | Release notes for each version (see [[new-feature-introduction-title]]) |
| Learn | Get started, Help, Settings | Open the interactive tutorial list, the Help page ({key:OPEN_HELP}) or the Settings page ({key:OPEN_SETTINGS}) |
*> The chosen preset is remembered, and PSDs imported from the menu, recent files or by dropping are all generated with it.
## Differences from Cubism
- Cubism's Home is the window shown at launch, used to create or open files, choose Modeler / Animator and read news.
- PSD2Live's Start page is the first page in the tab bar and is not saved with the project; you choose the model presets here before generating from a PSD.

@@ target-version-selection | Select Target Version
The target version decides which Cubism features the exported `.moc3` / `.cmo3` may use. In PSD2Live you choose it in the export window, not when creating the model.
## How to choose
1. Press {key:GENERATE} to open **Export Live2D model…**.
2. Under **SDK target**, choose Cubism 3.0, 3.3, 4.0, 4.2, 5.0 or 5.3 (default 5.0).
3. When the current model uses features the target does not support, the window shows "Used features downgraded on export:" with the format, feature and object for each item.
## Features that may be downgraded (examples)
| Feature | Description |
|---|---|
| Four-corner deformation | Older targets do not support the newer warp deformation method |
| Inverted mask | Needs a newer target |
| Blend shapes | Blend-shape features are tiered by version |
| Multiply color / screen color | Not supported by older targets |
| Extended blend modes, part offscreen compositing | Need a newer target |
The target version also affects generation: for example, when the target has no blend shapes, simulation modes are written as keyform axes.
## Differences from Cubism
- Cubism chooses the target version in a dialog when creating a model or later, and features the target does not support cannot be used while editing.
- PSD2Live chooses it at export, strips unsupported features for the target and lists them in the report; the same project can be exported to different versions.

@@ environment-settings | Environment settings
The Settings page stores local preferences that do not belong to a project. Open it with {key:OPEN_SETTINGS} or the gear at the right of the title bar; changes take effect immediately.
## List
| Item | Contents |
|---|---|
| Scale & Display | Interface scale and font size; one click applies the scale recommended for the display, and the current display's resolution and system scale are shown |
| Theme | Choose a color theme, or duplicate one and adjust its colors |
| Language | Interface language (中文, English, 日本語, 한국어) |
| Canvas Interaction | Whether the edit canvas and atlas page use software rendering, and the current rendering method |
| Prompts | The list of windows with "Don't show again"; re-enable them one by one or **Show all prompts again** |
| Shortcuts | Presets and per-item customization (see [[shortcut]]) |
| Files & data | Recent files and the last export folder, plus the location and size of the install folder, workspace storage, downloaded runtimes, upscale cache, error logs and more |
| MCP | Status and settings of the local MCP service; ports, tokens and the like need **Apply and restart** |
**Reset Defaults** at the bottom of the list resets all settings.
## Differences from Cubism
- Cubism's environment settings are a dialog with categories such as interface, performance and automatic backup.
- PSD2Live's settings are a page at the front of the tab bar whose panels can be docked; display problems are diagnosed with the software rendering option under "Canvas Interaction".
*> Settings, layouts and shortcuts are stored in local preferences and are not saved with the project.

@@ menus | Menu
@@ file-menu | File menu
The **File** menu gathers creating, opening, saving and exporting projects.
## Menu items
| Item | What it does |
|---|---|
| Start Page | Opens the Start page (see [[home]]) |
| Open Project | Opens a `.psd2live` ({key:OPEN_PROJECT}) |
| Open Recent | Recent projects and PSDs; **Clear Recent Files** at the bottom |
| Save project | {key:SAVE_PROJECT} |
| Save project as | {key:SAVE_PROJECT_AS} |
| New → New project from PSD… | Generates with the preset chosen on the Start page ({key:OPEN_PSD}) |
| New → New project from CMO3… | Clears the current model and imports a Cubism model (see [[cmo3-import]]) |
| New → New Layout project… | Creates a Layout project (see [[layout-project]]) |
| Export Live2D model… | Exports `.moc3` / `.cmo3` ({key:GENERATE}) |
| Export as | Runtimes and players (VTube Studio, PSD2Live runtime model, web player, Unity prefab), images and video, layered PSD, and **Source PSD…** ({key:REEXPORT_PSD}) |
| Open folder | The folders of the project, source files and output, plus workspace storage and app data |
| Exit | Closes the program (Alt+F4) |
## Differences from Cubism
- Cubism's File menu also contains loading PSDs, model settings, exporting embedded files and so on; motions are exported from the Animator side.
- In PSD2Live, importing is generating: the New submenu generates the model directly; every export format is in the File menu, and motions are exported with the model.

@@ edit-menu | Edit menu
PSD2Live has no separate **Edit** menu. Undo, redo and history operations come from shortcuts and the **History** panel, and object operations such as copy and delete are in the context menus of the canvas and panels.
## Where things are
| Cubism Edit menu | Where it is in PSD2Live |
|---|---|
| Undo / Redo | {key:UNDO} / {key:REDO}; "Undo" and "Redo / choose branch" in the History panel |
| Undo history | **History** panel (see [[history-palette]]) |
| Select all | {key:SELECT_ALL} on the canvas |
| Delete | {key:DELETE_SELECTION} on the canvas, or **Delete Layer** in the hierarchy context menu |
| Environment settings, shortcut settings | The gear at the right of the title bar opens the Settings page (see [[environment-settings]]) |
## Differences from Cubism
- Cubism gathers undo, copy and paste and settings in the Edit menu.
- PSD2Live's history branches: editing after an undo creates a new branch, and the old branch can still be checked out in the History panel.
*> Painting and atlas adjustments have their own in-session undo and enter the project history as one step only when applied.

@@ display-menu | Show menu
The **View** menu in the title bar corresponds to Cubism's View menu and manages workspaces and interface zoom. Display options for the canvas itself are set on each canvas.
## Menu items
| Group | Item | What it does |
|---|---|---|
| Workspaces | New Workspace | {key:NEW_EDIT_TAB} |
| Workspaces | Add Preview Canvas | {key:NEW_PREVIEW_TAB} |
| Workspaces | Show History | {key:OPEN_HISTORY_TAB} |
| Workspaces | Duplicate Workspace / Close Workspace | {key:DUPLICATE_TAB} / {key:CLOSE_TAB} |
| Workspaces | Next Workspace / Previous Workspace | {key:NEXT_TAB} / {key:PREV_TAB} |
| Interface & Zoom | Zoom in interface / Zoom out interface / Reset zoom | {key:ZOOM_IN} / {key:ZOOM_OUT} / {key:ZOOM_RESET}, scaling the whole interface |
## Canvas display
Toggles for mesh wireframe, deformers, rotation deformers, deform paths, skeleton, texture density, source pixels, reference images and more are in the display bar at the bottom right of the canvas; the background (checkerboard / solid color / transparent) is under "View ▾" in the canvas title bar. Each canvas keeps its own (see [[about-viewarea]]).
## Differences from Cubism
- Cubism's View menu toggles global display items such as the mesh, guides and grid.
- PSD2Live saves display items per canvas, so two canvases in the same workspace can show different things.

@@ modeling-menu | Modeling Menu
The **Model** menu in the title bar gathers operations on the whole model. Modeling operations on single objects are in the canvas modes, the hierarchy and the canvas context menu.
## Menu items
| Item | What it does |
|---|---|
| Reanalyze PSD | Reads the source PSD again and generates with the current settings ({key:REANALYZE}) |
| Replace the model with a CMO3… | Replaces objects in the current model with those of a CMO3 by ID, adds new objects, and keeps what the file does not contain |
| Split Layers by Mesh… | Splits separate parts drawn on one layer (such as left and right legs) into their own layers |
| Texture Upscale… | Upscales layer pixels 2× / 4× ({key:TEXTURE_UPSCALE}, see [[texture-upscale]]) |
| Update Generated Rig | Adopts the results of the current generator version and merges your edits (see [[update-generated-rig]]) |
| Preview Generated Rig Update | Only checks what an update would change; the project stays as is |
## Differences from Cubism
- Cubism's Modeling menu contains many object-level commands for meshes, deformers, parameters, keys and so on.
- PSD2Live puts object-level commands on the canvas: deformers are created from the Object mode toolbar and "Create" in the context menu, mesh topology in Edit mode, and keyforms in Deform mode (see [[canvas-modes]]).

@@ animation-menu | Animation menu
PSD2Live has no separate **Animation** menu and no separate Animator. Motions are made in the same project with the **Animation** panel and the **Animation Editor**.
## Where things are
| Purpose | Where it is in PSD2Live |
|---|---|
| Motion list, new and duplicate motions | **Animation** panel: preset motions (idle, nod, head shake and so on) and custom motions |
| Timeline, keyframes, curves | **Animation Editor**: dope sheet and curve view (see [[timelinepalatte]]) |
| Play / set a keyframe | {key:MOTION_PLAY_PAUSE} / {key:MOTION_KEY_POSE} (when the timeline has focus) |
| Video and animated image output | Images and video under **File → Export as** (see [[exporting-images]]) |
Switch to the "Animation" workspace to see the motion list, a preview canvas and the Animation Editor together.
{tutorial=ANIMATION}
## Differences from Cubism
- Cubism edits scenes in the Animator, and its Animation menu manages scenes, tracks and export.
- PSD2Live's motions are saved with the model in the project; exporting `.cmo3` also writes a `.can3`, and exporting `.moc3` writes `.motion3.json`.

@@ formanimation-menu | Form Animation (FA) menu

@@ window-menu | Window Menu
The **Windows** menu at the right of the workspace tab bar shows or hides panels and canvases. When the Start, Help or Settings page is open, the menu lists only that page's panels.
## Menu items
| Item | What it does |
|---|---|
| Panel list | Hierarchy, Skeleton, History, Log, Animation Editor, Model presets, Layers, Parameters, Tool Details, Mesh, Inspector, Animation, Physics, Simulation, Atlas, Texture, Materials; a tick means it is shown in the current workspace |
| Canvases | Lists this workspace's canvases, each of which can be shown or hidden |
| Add Edit Canvas / Add Preview Canvas | Adds a canvas to this workspace (see [[multiview]]) |
| Reset layout | Restores the current workspace's preset layout without touching the model |
## Differences from Cubism
- Cubism's Window menu toggles each palette and saves or loads window layouts.
- PSD2Live saves layouts per workspace: each workspace has its own set of panels, and **Reset layout** restores only the current workspace (see [[workspace]]).
*> The sidebar buttons at the top right of the title bar fold or unfold all panels on one side at once.

@@ help-menu | Help Menu
The **Help** menu in the title bar opens the Help page and online resources.
## Menu items
| Item | What it does |
|---|---|
| Help | Opens the Help page ({key:OPEN_HELP}; press again to return to the workspace) |
| On the web → GitHub repository | The source repository |
| On the web → Documentation | The online documentation |
| On the web → Example repository | Example PSDs and projects |
| On the web → Releases | Downloads for each version |
| On the web → Report an issue | Submit an issue |
## Help page
The Help page lists **Manual**, **Tutorials**, Examples, community and links, third-party licenses and About. The Manual and Tutorials follow the structure of Cubism Editor's manual and tutorial site, and each page is marked as same as Cubism, works differently, unique to this program, or not implemented. The step-by-step tutorials highlight controls in the current workspace and prompt with your own shortcuts.
## Differences from Cubism
- Cubism's Help menu opens the online manual, checks for updates and shows license information.
- PSD2Live's manual and interactive tutorials are built into the program and can be read offline.

@@ mesh-edit-menu | Mesh Edit mode menu
Cubism's mesh edit mode has its own menu; in PSD2Live, mesh operations are in the canvas's **Edit** mode, the "Mesh" submenu of the canvas context menu and the **Mesh** panel.
## Where things are
| Operation | Where |
|---|---|
| Subdivide, cut, merge, delete vertices | Edit mode toolbar and context menu (Subdivide {key:TOOL_SUBDIVIDE}, Knife {key:TOOL_KNIFE}, delete {key:DELETE_SELECTION}) |
| Vertex / edge / face selection | The Edit mode mode bar ({key:PICK_OPTION_1}–{key:PICK_OPTION_3}) |
| Rebuild from pixels | The "Mesh" submenu of the context menu, or the Mesh panel |
| Split disconnected meshes… | The "Mesh" submenu of the context menu, or the hierarchy context menu |
| Front/back layers | The "Mesh" submenu of the context menu (when available) |
| Automatic mesh outline, vertex spacing, topology, etc. | **Mesh** panel: global defaults or per-layer overrides |
{tutorial=EDIT_MODE}
## Differences from Cubism
- In Cubism, you enter mesh edit mode and generate meshes manually or automatically with its dedicated menu and toolbar.
- In PSD2Live, meshes are built automatically at generation and Edit mode only changes topology; a topology change also migrates keyforms, blend shapes and glue references (see [[mesh-edit-manual]]).

@@ workspace | Workspace
Workspaces are the tabs at the top of the interface; each tab has its own canvas and panel layout and they share one project. Switch workspaces by task rather than rearranging panels over and over.
## Workspace presets
Click **+** at the right of the tab bar to create a workspace and choose one of these presets; a preview of the layout and its purpose appears on the right:
| Preset | Purpose |
|---|---|
| Edit | One edit canvas for deformers, keyforms and bones, with the full set of property panels |
| Mesh | Hierarchy, edit canvas and Mesh panel for editing mesh topology |
| Rigging | Edit canvas and live preview side by side, with the Parameters panel always open |
| Animation | Motion list, preview canvas and Animation Editor |
| Preview | A large preview with only the motion list |
| Physics | A preview canvas plus the Parameters and Physics panels |
| Texture | Atlas page, edit canvas and Texture panel |
| Layout | Material browser, an edit canvas in Object mode, hierarchy and layer table, for placing first and generating later |
| History | Only the history tree and the operation list |
| Blank | An empty dock area; add canvases and panels from the **Windows** menu |
## Operations
- Click a tab to switch, drag to reorder, middle-click to close; {key:JUMP_TAB_1} and so on jump to the workspace with that number.
- Right-click a tab to **Rename**, **Duplicate**, **Close**, **Close others** or **Close to the right**.
- Panel sets, split ratios, floating state, canvas modes and view options are all saved per workspace; **Reset layout** restores only the current workspace's preset.
{tutorial=WORKSPACE}
## Differences from Cubism
- Cubism's workspaces switch between Modeler and Animator and can save window layouts.
- PSD2Live can have several workspace tabs at once, each saving its own canvases and panels; creating, deleting and reordering workspaces is saved with the project.

@@ view-area | View area
@@ about-viewarea | About the View Area
The canvas is the area where the model is shown and edited. PSD2Live has edit canvases and preview canvases; a workspace can hold several canvases, each saving its own camera and display settings.
## Elements on the canvas
| Position | Contents |
|---|---|
| Top left | The canvas mode menu and mode bar (confirm, cancel and so on), with the current mode's toolbar below it (see [[canvas-modes]], [[toolbar]]) |
| Bottom left | Tool options bar: values such as radius and strength for the current tool |
| Bottom right | Display bar: toggles for texture, mesh wireframe, deformers, rotation deformers, deform paths, skeleton, texture density, source pixels, reference images and more; expands on hover |
| Title bar | "View ▾": canvas background (checkerboard / solid color / transparent) and more, plus resetting the current canvas's view options |
## View operations
| Operation | How |
|---|---|
| Zoom | Mouse wheel; or hold {key:ZOOM_DRAG} and drag up and down |
| Pan | Hold {key:PAN_VIEW} and drag, or {kbd:Space} + left-drag |
| Frame selection | {key:FRAME_VIEW} |
| Reset camera | {key:RESET_CAMERA} |
| Quick preview | {key:QUICK_PREVIEW} switches quickly between editing and preview |
{tutorial=WORKSPACE}
## Differences from Cubism
- Cubism's view area has a single view (with a separate multiview), and display items are toggled globally in the View menu.
- PSD2Live saves display items per canvas, and the canvas's mode and tool settings sit in floating bars inside the canvas instead of taking up panels.

@@ canvas-modes | Canvas modes
The canvas mode decides what you change on the canvas. The mode menu at the top left of each canvas lists every mode, and while the menu is open you can press 1–7 to pick one directly; switching modes shows that mode's tools.
## Modes at a glance
| Mode | What it changes | Shortcut |
|---|---|---|
| Object | Select objects; create Warps, rotation deformers and deform paths | {key:MODE_SELECT} |
| Deform | The base shape, or the keyform at the current parameter key | {key:MODE_DEFORM} |
| Edit | Mesh topology and path bindings, Glue | {key:MODE_EDIT} |
| Simulate | Vertex weight groups read by cloth and hair simulation | {key:MODE_SIMULATE} |
| Skeleton | Posing and editing the skeleton | {key:MODE_SKELETON} |
| Paint | Layer pixels | {key:MODE_PAINT} |
| Preview | No changes; play back and inspect | {key:MODE_PREVIEW} |
## How to use
1. Select the target (drawable, deformer or layer) in the hierarchy or on the canvas.
2. Choose a mode from the mode menu; without a target you are asked to select one first.
3. Edit with the tools in the left toolbar; "Confirm" on the mode bar or {kbd:Enter} completes steps such as placing or cutting, and {key:CANCEL} cancels.
Hold {key:TEMPORARY_SELECT} to switch to selection temporarily; release to go back.
{tutorial=HIERARCHY}
## Relation to Cubism
- Cubism switches what you edit through toolbar buttons and dedicated modes such as "Mesh Edit mode" and "Deform Path".
- PSD2Live switches everything from one mode menu, similar to Blender's mode dropdown; Simulate, Skeleton and Paint are modes Cubism does not have, and their results are baked or written into textures on export.
*> Deform versus Edit: Deform changes shapes, Edit changes mesh structure; Paint changes pixels and uses its own apply / discard session.

@@ preview-canvas | Preview canvas
The preview canvas plays the exported model to check motions, physics and simulation, without changing the model. Choosing "Preview" from an edit canvas's mode menu turns it into a preview canvas; choosing another mode returns to editing.
## How to open
- Choose **Preview** from the canvas mode menu ({key:MODE_PREVIEW}).
- **View → Add Preview Canvas** ({key:NEW_PREVIEW_TAB}), or **Windows → Add Preview Canvas**.
- Switch to a workspace with a preview canvas, such as "Preview", "Animation" or "Physics".
## Preview controls
| Item | What it does |
|---|---|
| Idle animation | Plays or stops the idle motion |
| Tracking | Head and gaze follow the mouse; "Smooth + body Y" can be turned on |
| Physics | Turns physics on or off |
| Preview runtime | Cubism SDK (the exported moc3, played in the official Cubism 5 runtime) or the PSD2Live runtime (the exported .p2lrt) |
| Advanced mode | PSD2Live runtime only: skinning along arcs, exact links, live cloth and hair simulation |
| Frame rate cap | Follow display or a fixed FPS |
The bottom of the canvas shows the current runtime, physics state and zoom; it warns when simulation has not been baked.
## Relation to Cubism
- Cubism previews the model directly in the Editor, previews physics in the "Physics Settings" window, and confirms the final result in the Viewer.
- PSD2Live's preview canvas plays the output of the same compilation as the export and can switch directly between the official SDK and the bundled runtime, much like Cubism Viewer.
!> Reference simulation runs only in the editor and is not exported; to export it, bake it in the Simulation panel.

@@ guide-function | Guide line function

@@ solo | Solo function
Solo shows only the selected layer and temporarily hides the others, which helps when checking parts that are covered.
## How to use
1. Right-click a layer in the hierarchy.
2. In the "View" group, choose **Solo this layer**; a `SOLO` badge appears next to the menu item.
3. Right-click again and choose **Exit solo**; the other layers return to the visibility they had before.
Solo applies to the current canvas; other canvases in the same workspace are not affected.
*> Solo is temporary visibility for editing and is not the same as variants that switch with parameters; to show or hide a layer with a parameter, set up a variant or opacity keyforms (see [[variants]]). The Interior shape panel also has its own "Solo / frame".
## Differences from Cubism
- Cubism: turn on Solo for the selected objects in the view area; several objects can be soloed at once.
- PSD2Live: solo a single layer from the hierarchy context menu; it affects only the current canvas.

@@ grid | Grid Function

@@ snapshot | Snapshot Function

@@ onion-skin | Onion Skin

@@ multiview | Multiview
A workspace can hold several canvases, for example an edit canvas next to a preview canvas, or two edit canvases viewing the same model with different cameras.
## Adding a canvas
1. Open the **Windows** menu at the right of the workspace tab bar.
2. Choose **Add Edit Canvas** or **Add Preview Canvas**; the new canvas docks in the workspace, and you can drag its title to move it.
3. When it is no longer needed, untick it in the "Canvases" group of the **Windows** menu, or close the canvas. At least one canvas always remains.
## What each canvas keeps and what is shared
| Per canvas | Shared |
|---|---|
| Selection, tool, camera, display settings, canvas mode | Parameter pose, parameter locks and playback controls |
So adjusting a parameter on the edit canvas is reflected on the preview canvas at once; switching focus or hiding a canvas does not swap in a different pose. Different workspaces keep their own poses.
## Differences from Cubism
- Cubism's multiview shows several viewpoints side by side in one view, and each view can have different parameter values.
- PSD2Live's canvases share one parameter pose and each saves its own camera and display items; use different workspaces when you need different poses.

@@ random-pose | Random Pose

@@ toolbar-folder | Toolbar
@@ toolbar | About the Toolbar
The toolbar sits at the top left of each canvas, below the mode menu, and lists the tools of the current canvas mode. Each tool takes one row, grouped by category with separators; hovering expands it to show names and shortcuts.
## Tools in each mode
| Mode | Tools |
|---|---|
| Object | Select, Transform, Lasso Select; Create Warp, Create Rotation, Deform Path |
| Deform | Select, Transform, Lasso Select, Brush Select; Deform Brush, Smooth Brush, Inflate Brush |
| Edit | The Deform mode tools plus Subdivide, Knife and Glue (available when two meshes are selected) |
| Simulate | Weight paint, Weight gradient, with the weight groups to paint listed below |
| Skeleton | Pose Bones, Edit Bones |
| Paint | Brush, Pencil, Eraser; Paint Bucket, Eyedropper; Shape |
## Tool variants
When you select a tool with variants, they expand below it: brush shapes, the sub-tools of glue and bones, weight group kinds and paint shapes. Pick them directly with {key:PICK_OPTION_1}–{key:PICK_OPTION_5}.
## Where settings are
| Where | Contents |
|---|---|
| Mode bar (top of the canvas) | Vertex / edge / face selection, confirming and cancelling the current step |
| Tool options bar (bottom left of the canvas) | Values such as radius, strength, hardness and opacity; drag left and right to adjust, click to pop up a slider |
| Canvas context menu | The same settings as the options bar, plus the tool's operations |
| Tool Details panel | Advanced settings that need more room (see [[detailed-toolpalatte]]) |
{tutorial=TOOL_DETAILS}
## Differences from Cubism
- Cubism's toolbar is at the top of the window, with one row of buttons shared by all modes.
- PSD2Live's toolbar is inside each canvas and changes with the canvas mode; numeric settings are gathered in the tool options bar and the context menu.

@@ edit-level | Level edit
The edit level decides which layer of control points you edit on a warp deformer. In Deform mode, PSD2Live offers two levels for warp deformers: "Vertices" and "Bezier".
## How to use
1. Select a warp deformer and enter Deform mode.
2. On the mode bar, choose **Vertices** ({key:PICK_OPTION_1}) or **Bezier** ({key:PICK_OPTION_2}).
3. Vertices: edit every control point of the grid directly. Bezier: only the cyan anchors and amber handles can be dragged to bend the whole grid; the detailed grid fades and cannot be edited.
When the target is not a warp deformer, the two are the same and the mode bar shows no level.
## Related settings
**Bezier Division** (1–16) in the Inspector and Tool Details panel sets the number of segments at the Bezier level, and **Reset Bezier Control Points** returns the handles to their default positions.
{tutorial=DEFORM_MODE}
## Differences from Cubism
- Cubism's edit level has levels 1–3, switched on the toolbar, controlling how dense the shown and editable points on a deformer are.
- PSD2Live has only the Vertices and Bezier levels, and only for warp deformers; a drawable's vertices are always edited by vertex, edge and face selection.

@@ palettes | Palette
@@ palatte-basic-operation | Basic Palette Operations
Canvases and panels are all dockable modules that can be combined, split, floated or closed freely. Layouts are saved per workspace.
## Basic operations
| Operation | How |
|---|---|
| Reorder / merge into a tab group | Drag a module tab to another position in the same column, or between the tabs of another column |
| Split | Drag a module to the top, bottom, left or right edge of the target area and release to create a new split; drag the divider to change the ratio |
| Move a whole group | Drag the empty part of the tab bar or the faint handle at its right end |
| Float | Drag it out of the main window to become a separate window; the floating title bar offers **Dock in workspace** |
| Close | Middle-click the tab; right-click a tab for **Close Other Tabs** or **Close All Tabs** |
| Show again | Tick it in the **Windows** menu, or **Reset layout** |
The sidebar buttons at the top right of the title bar fold or unfold all panels on one side, and hovering shows the modules inside.
{tutorial=WORKSPACE}
## Differences from Cubism
- Cubism's palettes can be docked and floated, and the layout is saved as a whole.
- In PSD2Live each workspace has its own layout and the canvas is one of the modules; panels on the Start, Help and Settings pages can be arranged the same way.

@@ detailed-toolpalatte | Tool Details palette
The **Tool Details** panel shows advanced settings of the current tool that need more room. Common values (radius, strength, hardness and so on) are in the tool options bar at the bottom left of the canvas, not here.
## Contents
| Tool | Settings |
|---|---|
| Transform | Precise numeric transforms |
| Create Warp / Create Rotation | Defaults used when creating, such as Bezier division |
| Deform Path | The path list |
| Skeleton | Bone binding and weights |
| Paint | Palette: the foreground color's color field and hue bar, swatches and recently used colors; "Discard" and "Apply" for the paint session |
{tutorial=TOOL_DETAILS}
## Differences from Cubism
- Cubism's Tool Details palette shows all settings of the current tool, such as brush size and falloff.
- PSD2Live puts common values in the tool options bar and context menu inside the canvas, and the Tool Details panel keeps only advanced settings.

@@ inspector-palette | Inspector palette
The **Inspector** shows and edits the properties of the selected object according to its type. With nothing selected, it shows a hint.
## Main properties
| Object | Properties |
|---|---|
| Drawable | Name, clipping mask, draw order, opacity, multiply color, screen color, blend mode, culling, user data, vertex info |
| Warp deformer | Bezier division, edit level settings, deformer conversion method |
| Rotation deformer, deform path | Their own fields (for a path: curve, width, hardness, closed) |
Draw order in the Inspector, **Draw order…** in the hierarchy context menu and the draw order ruler beside the hierarchy all change the same property.
{tutorial=INSPECTOR}
*> When editing keyforms owned by a generator, "Generated overrides" in the Inspector lists items that could not be merged cleanly.

@@ partspalatte | Parts palette
PSD2Live has no separate Parts panel. The object hierarchy is viewed and edited in the **Hierarchy**, and recognition and variants of PSD layers are set in the **Layers** panel.
## Hierarchy
- Lists deformers and drawables by parent-child relation; the root node "Model Hierarchy" shows counts.
- The top bar searches deformers and layers, expands or collapses everything, and sorts by model order, height, horizontal position, draw order or name.
- The eye icon controls visibility, selection syncs with the canvas, dragging a node changes its parent, and dropping on "Drop here to move it to the root" moves it to the root.
- Right-click a layer: solo, draw order, mesh, add a parent deformer or deform path, import layer, delete layer.
- The draw order ruler beside the tree zooms with the wheel, reorders by dragging and takes a typed value on double-click.
{tutorial=HIERARCHY}
## Differences from Cubism
- Cubism's Parts palette organizes ArtMeshes and deformers into parts (folders) and manages part visibility, locking and draw order groups.
- PSD2Live's hierarchy is organized directly by the deformer parent-child structure; parts are written when exporting `.cmo3`. Layers are classified by tags rather than part folders (see [[layers-palette]]).

@@ deformerpalatte | Deformer palette
The deformer parent-child structure is edited in the **Hierarchy**, which plays the role of both Cubism's Parts palette and Deformer palette.
## How to use
1. View the parent-child relations of deformers and drawables in the hierarchy; selection syncs with highlighting on the canvas.
2. Drag a node under another deformer to change its parent.
3. Right-click a deformer: add a parent warp, child warp or parent rotation deformer, import a layer, move to root, expand / collapse the branch, **Delete (children up)**.
4. A new deformer starts a placement session; adjust it on the canvas and confirm (see [[making-and-placement-of-warp-deformer]]).
{tutorial=HIERARCHY}
## Differences from Cubism
- Cubism's Deformer palette lists only the deformer structure, with ArtMeshes in the Parts palette.
- PSD2Live lists deformers and drawables in one tree; changed parent-child relations can be reverted with **Reset Hierarchy** or **Reset Hierarchy for This Item** in the context menu.

@@ palametorpalatte | Parameter palette
The **Parameters** panel lists the model's parameters and is used to adjust the pose and to add or delete parameter keys.
## Basic operations
- Left-drag a parameter bar to change its current value; right-click while hovering a key marker to snap to that key.
- Parameters can be searched, locked, reset and organized into folders.
- Parameters linked in two dimensions are operated with an XY pad; right-click to snap to the intersection of both parameters' keys.
- Key markers: a round dot is a normal key, a square dot is a blend shape key.
{tutorial=PARAMETERS}
*> Keyforms on parameter keys are modeling data, not the keyframes of the animation timeline; dragging a parameter on the edit canvas pauses animation playback.

@@ layers-palette | Layers panel
The **Layers** panel lists PSD layers as a table, together with the tags and variant settings the generator recognized for them. The generator identifies parts by the tags here; correct them directly when recognition is wrong.
## Table columns
| Column | Contents |
|---|---|
| # | Index |
| PSD layer / component | The layer name; the summary at the top shows how many are visible, recognized and unrecognized |
| Tags | Part, side and custom tags (not read by the generator); click to choose or type words such as "hair" or "front" |
| Variant | None, Toggle Variant, Switch Variant |
| Parameter | The parameter ID the variant uses |
## Other operations
- Right-click to show all, hide all or invert visibility.
- Deleted layers can be brought back with **Restore Deleted Layers**.
- A layer can be made a reference layer ({key:LAYER_TOGGLE_REFERENCE}): it stays on the canvas but takes no part in generation or export.
- Layers not yet generated can be reordered with {key:LAYER_TO_FRONT}, {key:LAYER_FORWARD}, {key:LAYER_BACKWARD} and {key:LAYER_TO_BACK}.
Generated layers take on their new tags after regeneration.
{tutorial=BASIC}
## Relation to Cubism
- Cubism has no such panel: after a PSD is loaded, the author builds meshes and deformers by hand.
- PSD2Live's generator reads layer tags to decide how to rig, so this table is the main control over automatic generation (see [[precautions-for-psd-data]], [[variants]]).

@@ timelinepalatte | Timeline palette
The **Animation Editor** corresponds to Cubism's Timeline palette and edits a motion's parameter tracks and keyframes; the motion list is in the **Animation** panel.
## Basic operations
1. Select or create a motion in the Animation panel.
2. Add parameter tracks in the Animation Editor; double-click a track to add a keyframe.
3. The dope sheet suits timing and the curve view suits easing; keyframes use Bezier interpolation by default.
4. With "Auto Key" on in the toolbar, adjusting a parameter slider, 2D point or canvas pose at the playhead sets keyframes automatically; to key by hand, pose first and then use "Key Pose" to write it at the playhead.
## Shortcuts (when the timeline has focus)
| Operation | Shortcut |
|---|---|
| Play / pause | {key:MOTION_PLAY_PAUSE} |
| Set keyframe | {key:MOTION_KEY_POSE} |
| Auto Key | {key:MOTION_TOGGLE_AUTO_KEY} |
| Previous frame / next frame | {key:MOTION_PREV_FRAME} / {key:MOTION_NEXT_FRAME} |
| Fit view | {key:MOTION_FIT_VIEW} |
{tutorial=ANIMATION}
## Differences from Cubism
- Cubism's timeline manages several models and tracks in an Animator scene.
- PSD2Live's motions belong to the current model and there are no scenes; preset motions have their own tuning controls, and after you edit their keyframes by hand you must restore the defaults to use those controls again.

@@ scene-palette | Scene palette

@@ project-palette | Project palette

@@ history-palette | History panel
The **History** panel shows every version of the project as a tree. Continuing to edit from an old version creates a new branch; the original branch is kept and can be checked out at any time.
## How to open
- {key:OPEN_HISTORY_TAB}, or **View → Show History**.
- Create a workspace from the "History" preset, which shows only the history tree and **Operations**.
## Operations
| Operation | How |
|---|---|
| Check out a version | Double-click a node card, or select it and click **Checkout**; **Latest** checks out the newest version |
| Find | **Search history…** at the top; the branch leading to HEAD is highlighted |
| Annotate | Fill in **Node title** and **Notes**, then click **Apply annotation** |
| Hide a branch | **Hide branch** (no data is deleted); **Show hidden branches** brings it back |
| Undo / redo | "Undo" and "Redo / choose branch", or {key:UNDO} / {key:REDO} |
| Zoom the view | Zoom in, zoom out, reset to 100%, fit to view |
Node details show the node ID, parent node ID, revision and creation time, and offer **Copy ID**.
{tutorial=PROJECT_HISTORY}
## Relation to Cubism
- Cubism's undo is linear: editing after an undo discards the later steps.
- PSD2Live's history is saved in the `.psd2live`, and branches can still be checked out after closing; changes made over MCP and in the interface go into the same history.

@@ logpalatte | Log Palette
The **Logs** panel shows records from the program, editing operations and AI clients, including errors, warnings and export reports.
## Operations
- Filter by source: All, System, User, AI, Images; filter by level: Debug, Info, Warning, Error.
- **Filter…** filters by text; **Auto Scroll**, **Clear**, **Copy Log**.
- Columns are time, source, tag and message, with **Reset Columns**; repeated messages are merged with a count.
- Records with images can be clicked to view them full size.
*> When reporting a problem you can attach the copied log (see [[fault-map]]).

@@ convert-id | ID Conversion

@@ autosave | Automatic Backup Function
While you edit, PSD2Live keeps writing unsaved changes to local workspace storage. After the program quits unexpectedly, the next launch can restore these changes as an unsaved project.
## Restoring
1. After the program closed unexpectedly before you saved or discarded changes, start PSD2Live again.
2. When the "Restore unsaved edits" window appears, choose **Restore**.
3. The project opens unsaved, including history, parameter pose, snapshots and workspaces; press {key:SAVE_PROJECT} to keep them.
Choose **Discard** to not restore. Exiting after a normal save or discard clears the recovery data.
## Storage location
**Settings → Files & data** shows the location and size of workspace storage, which holds the history of opened projects; you can also clean up leftovers that earlier crashes left in the system temp folder.
## Differences from Cubism
- Cubism saves a copy of the model as a backup file at a set interval, and you open the backup by hand when needed.
- PSD2Live creates no backup files; it keeps recovery checkpoints and asks at the next launch whether to restore them. Background task records are restored but not rerun automatically.
!> Restored content is still unsaved; save it as a `.psd2live` soon.
@@ material | Material division / PSD creation
@@ divide-the-material | About Material Separation
To make an illustration move, the parts that move separately or overlap one another must be drawn on separate layers; this step is called material separation. PSD2Live reads the separated layered PSD, recognizes parts by layer name and automatically generates meshes, deformers and parameters, so how finely you separate directly determines the result.
## Basic rules for separation
- Put each part on its own layer: eye whites, irises, upper lashes, eyebrows, nose, mouth, front hair, back hair, face, neck, top and so on.
- Draw the iris as a full circle, including the part hidden by the eyelids; the eye white limits where the iris shows.
- Draw the mouth fully open; the program generates the closed mouth. The mouth interior can be one piece, or split into upper teeth, lower teeth and tongue.
- Keep front hair and back hair apart, and paint extra into covered areas so nothing shows through when moving.
- Paired parts (both eyes, both ears, both arms) are best split into left and right layers, with the side written in the name.
## When the separation is not fine enough
Left and right parts drawn on one layer are not split on import and are generated as one part. After import you can split them into separate layers with [[split-by-mesh]]; when one layer has to sit both in front of and behind another part (such as a collar wrapping around the neck), use [[depth-split]].
## Differences from Cubism
- Cubism: how to separate is entirely up to the author, and after loading you build meshes, deformers and parameters by hand one by one.
- PSD2Live: after separation, a full rig is generated automatically from layer names (or the tags you change in the layer table after import), so naming and layering should follow the conventions in [[precautions-for-psd-data]].
- PSD2Live can also skip the PSD: place individual images on the canvas in a [[layout-project]] and then generate.
*> Layers whose names are not recognized are not discarded: they still get meshes and a fallback assignment, and you can fix their tags in the layer table after import.

@@ reimport-psd | How to create PSDs to import
This page describes the format and structure requirements when preparing PSD files for PSD2Live. A PSD that meets them can be imported directly and a model generated automatically.
## File format
- Use a layered RGB, 8-bit PSD with a transparent background.
- Rasterize text, vector shapes, layer styles and adjustment effects first, or merge them into the corresponding pixel layers.
- Keep the body basically upright; the head may tilt slightly, but check the automatic reference estimate in the preview.
## Layer naming
The generator recognizes parts by layer name, and English, Chinese, Japanese and Korean names all work, such as `front hair`, `前发`, `eyelash-l`, `左睫毛`. For the name table, see [[precautions-for-psd-data]]. Recognition results are only initial values and can be changed at any time after import in the "Tags" column of the "Layers" panel.
## Sides and numbers
- Left and right refer to the character's own sides: the character's left is usually on the right of the image.
- Numbers in names (such as `front hair 1`) tell similar materials apart; some Photoshop copy suffixes are cleaned up.
- Numbers alone do not set up expression switching. For layers that should switch with a parameter, set up [[variants]] after import.
## Canvas size
Mesh settings default to "Scale with document size": lengths are computed as if the document's long side were scaled to a fixed size, so a large PSD gets an equally dense mesh. Sharpness comes from the layers' own pixels.
## Differences from Cubism
- Cubism: the PSD's layer and folder structure becomes ArtMeshes and parts directly, and names are only for display.
- PSD2Live: the layer name decides which part the generator treats it as, and parts are built automatically by the generator according to body part, not by PSD folder.
*> Without a PSD, you can also write images separated in an external tool into a Layout manifest (JSON or zip) and open it directly; see [[layout-project]].

@@ precautions-for-psd-data | Notes on PSD creation
Automatic generation depends on layer names and how layers are separated. Below are the common pitfalls when drawing, and the table of layer names the generator can recognize automatically.
## When drawing
- Draw only the upper lashes on the lash layer; do not mix in the lower lashes or the lower eyelid line, or closing the eye may drag the lower eyelid.
- Draw the mouth fully open; if you need a particular closed-mouth look, draw a separate `mouth_close`. A closed-mouth drawing cannot recover a mouth interior that is not there by deforming.
- Keep front hair and back hair apart, with enough overlap where they are covered.
- A closed eye can be drawn in advance as `eye_close`, or left to the lashes' eye-closing curve.
!> A recognized name does not mean the shape is right. After import, check neutral, eye and mouth open/close, head turns and hair motion, and verify layer order, left and right, masks and anything showing through.
*> Recognition results can be changed in the "Tags" column of the "Layers" panel; see [[psd-import]].

@@ split-by-mesh | Split layers by mesh
When a layer contains several unconnected pieces (for example both eyes drawn on one layer), you can split it into separate layers by the connected regions of its mesh, so each piece gets its own mesh, deformation and tags.
## How to open
- **Model → Split Layers by Mesh…**: scans all layers and lists those containing separate meshes; use **Select all** / **Deselect all**, then click **Split selected**.
- Right-click a layer in the hierarchy → **Split disconnected meshes…**, or choose it under **Mesh** in the canvas context menu in Object, Edit or Paint mode, to handle just that one layer.
- After importing layers, the program can offer to split them; tick don't-show-again in the dialog, or toggle "Offer mesh splits when importing layers" in **Settings › Prompts**.
## Splitting
1. Open the **Split layers by mesh** dialog and check the preview of each mesh region.
2. Choose a naming style: for two pieces, `L / R`, `T / B`, `1 / 2` or **Custom names**; for more pieces, numbers or custom.
3. Click **Split layers**. If you do not want to split, click **Keep as one layer**; in a batch you can click **Keep all as single layers**.
`L / R` naming follows the character's own left and right: the piece on the left of the image gets the `-r` suffix and is tagged as the right side, and the other gets `-l`.
## After splitting
The pieces replace the original layer, their meshes are taken from the matching parts of the original mesh, and the original layer's keyforms, blend shapes, paths, weights and glue are carried over to them. If you later change a piece's mesh settings, its mesh is regenerated from that piece's own pixels and these bindings are migrated.
!> Models imported from CMO3 cannot be split by mesh.
## Relation to Cubism
In Cubism, layers are separated in the PSD before loading, and ArtMeshes cannot be split after loading; you have to go back to the drawing software, change the PSD and load it again. PSD2Live can split directly in the editor without touching the PSD. To separate by a custom shape rather than by connected regions, or to interleave front and back, see [[depth-split]].

@@ depth-split | Front/back layers
Different parts of one layer sometimes need to sit in front of and behind another part, for example the back of a collar hidden behind the neck while the front flaps cover it. **Front/back layers** duplicates the layer into a back and a front copy sandwiching the middle object, sets the draw order automatically and glues the two so they deform together; all you do is erase from the front copy the parts that should go behind.
## Steps
1. Switch the edit canvas to **Object** mode, select the layer to split (such as the top), and hold {kbd:Shift} to add the object that goes in between (such as the neck).
2. Right-click the layer to split and choose **Split "layer name" into front/back layers**. The name in the menu is the object that will be duplicated; check it before clicking.
3. The program creates "`layer name · back`" and "`layer name · front`" to replace the original layer, selects the front copy and switches to the **Eraser** in **Paint** mode.
4. Erase the parts of the front copy that should be covered, along the edge of the middle object. If you erase too much, use undo in the paint session.
5. Click **Apply**. The front copy keeps the original mesh and deformation, so no mesh needs regenerating.
6. Check poses such as head turns and body turns in the Parameters panel, and go back to touch up the front copy if needed.
When only one layer is selected, right-click it in the hierarchy and choose **Front/back layers…**, pick the middle object under **Intervening object** in the popup, confirm the front / middle / back arrangement and click **Create and erase**.
## Notes
- Only one layer is split at a time; you can add several middle objects, which are placed together between the front and back copies and keep their order.
- Nothing looks different right after creation: the new front copy starts as a full duplicate, and the covering appears only after you erase.
- If a paint session is uncommitted, apply or discard it before creating.
!> Models imported from CMO3 do not support front/back layers yet.
{tutorial=PAINT_MODE}
## Relation to Cubism
In Cubism you usually go back to the drawing software to split the material into front and back layers, then reload it and set up deformation for each, or connect them with glue. PSD2Live does the duplication, ordering and gluing in one step inside the editor; the back copy keeps all its pixels, and the original layer's deformation data is copied to both.

@@ layout-project | Layout projects and the material library
A Layout project lets you place parts on the canvas first and generate the rig afterwards; after generation you can keep adding images and changing tags and then regenerate, and existing edits are kept. The material library (the **Materials** panel) holds every image the project uses, and the pixels a layer shows come from the material linked to it.
## Starting a Layout project
- **From an empty canvas**: on the Start page choose **Layout project** (or **File → New → New Layout project…**), pick a common size or enter width and height, then click **Create Layout Project**. The new project has no rig yet and switches to the Layout workspace.
- **From a PSD**: import a PSD as usual. The project generates a rig directly, and every PSD layer also appears in the Materials panel, linked to the layer on the canvas.
- **From a Layout manifest**: a manifest written by an external tool (JSON, or a zip with it at the root) opens the same way as a PSD; layers are placed according to the manifest and not yet generated.
## Materials panel
- **Import**: click **Import** or **Import folder…**, or drop files onto the panel. Supported are PNG, BMP, TIFF, WebP, JPEG, layered images inside PSD and CMO3, and SVG. A PSD becomes a folder, and its layer groups become subfolders.
- **Organize**: drag materials into another folder to move them; right-click to rename, create a folder or delete. Materials in use by a layer cannot be deleted.
- **Material window**: double-click a material to open a separate window where you can view, paint, flip and adjust colors; every layer using that material updates together.
## Placing and generating
1. Drag materials or a whole folder onto the canvas, or right-click **Place on canvas**.
2. In **Object** mode, move, scale and mirror layers with the transform box; placing and scaling never resample pixels.
3. Describe part and side in the **Tags** column of the "Layers" panel, and set toggle or switch variants in the **Variant** column.
4. Click **Generate** in the generate bar at the top right of the edit canvas. Layers placed or retagged afterwards show as "waiting"; click **Regenerate** to bring them into the rig.
Layers not yet generated can move up or down one step with {key:LAYER_FORWARD} / {key:LAYER_BACKWARD}, or to the top or bottom with {key:LAYER_TO_FRONT} / {key:LAYER_TO_BACK}; after generation, the front-to-back order of generated layers is adjusted with [[draworder]].
*> Canvas units set the model's size and proportions, while sharpness comes from the materials' own pixels: the smaller a layer is placed, the denser its texture.
{tutorial=LAYOUT}
## Relation to Cubism
Cubism models only from PSDs; adding or removing materials means going back to the drawing software, editing the PSD and loading it again. A Layout project brings "organizing materials, placing and naming" into the editor too, which suits materials that come from several files or were separated by a program. For reference layers, see [[sketch]].

@@ modeling | Modeling
@@ psd-import-folder | PSD Import
@@ psd-import | Import PSDs
After you import a layered PSD, PSD2Live recognizes parts by layer name, automatically generates meshes, deformers, parameters, motions and physics, and enters the editing workspace. What is generated is decided by [[model-presets]], which you can choose before importing or change afterwards.
## Ways to import
- Choose a file with **File → New → New project from PSD…** ({key:OPEN_PSD}).
- Drop a PSD anywhere on the window.
- **Generate from PSD** on the Start page: first pick a preset in the details panel (**Minimal** / **Default** / **Full**), adjust items as needed, then choose a file; double-click in **Recent PSDs** to generate directly.
The preset is remembered, and PSDs imported from the menu, recent files or by dropping are all generated with it. After import, hair and clothing simulations bake one after another, with progress in the status bar.
## Checks after import
1. Open the **Layers** panel and check the **Tags** (part and side) recognized for each layer; if one is wrong, click it to change it, and generated layers take on the new tags after regeneration.
2. In the **Variant** column, set up [[variants]] for layers that should show, hide or switch with a parameter.
3. Switch to preview and check blinking, mouth shapes, head turns and hair motion.
4. Save as a `.psd2live` project with {key:SAVE_PROJECT}. Every PSD layer is also registered as a material; see [[layout-project]].
{tutorial=BASIC}
## Differences from Cubism
- Cubism: loading a PSD creates only ArtMeshes and parts; meshes, deformers and parameters must be built one by one by hand or with helper features.
- PSD2Live: import gives you a complete moving model, which you then edit in the editor; every change is saved as an edit record and replayed on regeneration.
- Cubism builds parts from PSD folders; PSD2Live builds parts automatically from the recognized body parts.
- Existing Cubism models can be opened through [[cmo3-import]].

@@ psd-re-import | Re-import PSDs
After the artwork changes, the new pixels need to go into the existing project. PSD2Live has no feature to reload a whole PSD by layer mapping; instead, pick from the approaches below as needed.
## Replacing individual layers' pixels (keeping edits)
1. **Import** the modified PSD in the Materials panel; it becomes a new material folder.
2. Select the layer to update on the canvas or in the layer list.
3. Right-click the new material and choose **Replace selected layer's image**. The layer's position and size on the canvas stay the same; only the pixels and atlas tile change.
4. Click **Regenerate** in the generate bar. Layers whose image was replaced get their mesh regenerated from the new pixels by default; to keep the original mesh, choose **Generate, keeping replaced images' meshes** from the generate button's dropdown.
You can also replace a single layer with **Replace image…** in the **Texture** panel; see [[png-file-export-replacement]].
## Adding new layers
Drag new materials onto the canvas, place and tag them, then **Regenerate**; existing edits are kept. See [[layout-project]].
## Regenerating from scratch
**Model → Reanalyze PSD** ({key:REANALYZE}) reads the current PSD again and generates a new project with the current presets. If there are unsaved changes, you are asked whether to save first; edits in the original project are not carried into the new one.
## Differences from Cubism
- Cubism: re-importing a PSD maps layers by name or by hand, replaces the textures of existing ArtMeshes, and lets you choose whether to keep the meshes.
- PSD2Live: there is no whole-PSD mapping; pixels are updated layer by layer through material replacement, and new layers are added by placing them and regenerating.
!> For layers with author edits, bindings or materialized meshes, replacing the image and rebuilding the mesh may be refused; the reason is shown above the atlas page.

@@ model-presets | Model presets
The **Model presets** panel decides what is generated automatically and how strongly: deformation and mouth shapes, rig values, motions, physics and simulation. After a change the model is regenerated with the new settings, and your existing edits are replayed as usual.
## Where to set them
- Before import: the details panel of **Generate from PSD** on the Start page, where you pick the **Minimal** / **Default** / **Full** preset and adjust items. The generate button's menu switches presets or offers **Apply to Current Model**.
- After import: the **Model presets** panel (reopen it from the **Windows** menu). The toolbar at the top expands / collapses everything, and **Reset Defaults** returns all model presets to their defaults.
## Groups
| Group | Contents |
|---|---|
| Rigging & Facial | Feature displacement deformers on/off, head range and body range (0–4×), generated upper and lower lip outlines with line width and color, mouth shape settings |
| Rig Values | Body X (turn), Body Y, body lean, Proportion (chibi), Body Z and breath, depth and perspective; **Advanced** holds head turn, eyes, eyebrows and mouth |
| Motions | Two groups, **Basic motions** and **Skeleton motions**; turning one off removes its motions from the Animation panel, preview and export |
| Physics & Simulation | Front hair and back hair each choose **Simulate** / **Classic sway** / **Off**; **Clothing** simulation (only loose areas are simulated); Eye Jelly; Auto pin weights |
With **Selected only** ticked, the physics and simulation presets apply only to the selected layers.
## Tips
1. Generate with the defaults first and preview.
2. When adjusting, change one item at a time and watch the preview.
3. Atlas parameters are not here; they are set in the export window.
{tutorial=BASIC}
## Relation to Cubism
Cubism has no matching panel: deformers, parameters, motions and physics are built by the author by hand, or with separate helper features such as [[auto-generation-of-deformer]], [[face-auto-edit]] and [[auto-generation-of-sway-motion]]. PSD2Live's model presets gather these generation steps in one place, and you can regenerate at any time after a change.

@@ update-generated-rig | Update Generated Rig
Operations that change the automatically generated result, such as turning on hair simulation, enabling the skeleton or splitting layers, merge your existing edits onto the new generated result and save it as fixed data, so later program upgrades do not change these results automatically. To adopt a newer generator's improvements, use **Update Generated Rig**.
## Steps
1. First check with **Model → Preview Generated Rig Update**: the status bar shows whether the update would change the model and how many items need a look; the project itself is unchanged.
2. Run **Model → Update Generated Rig**. Parts you have not changed are replaced with the new generator's results, and your changes are kept.
3. Places that cannot be migrated cleanly are counted in the status bar and listed under **Generated overrides** in the Inspector.
After a project opens or the generated result changes, the program checks in the background for results that an older version merged incorrectly, and if it finds any it shows a notice at the top of the edit canvas, where you can click "Update Generated Rig" or "Preview changes" directly. Once you dismiss the notice, it does not return until the generated result changes again.
## Notes
- When the generated result does not change, no history is created; an update is one undoable history step.
- Canvas edits on keyforms produced by a generator are saved as overrides of the generated result and merged three ways with the new result during an update.
## Relation to Cubism
In Cubism the model data is exactly what the author built by hand, so there is no generated part to update. A PSD2Live model consists of the generated result plus your edits, and this feature re-merges the two after the generator improves.

@@ cmo3-import | Import from CMO3
An existing Cubism model project (`.cmo3`) can be imported into PSD2Live for further editing, preview and export. The imported parameters, keyforms, deformers, textures and physics are saved with the project.
## Two ways to import
| Method | Where | Result |
|---|---|---|
| New project from CMO3 | **File → New → New project from CMO3…**, or **Import CMO3** on the Start page | Clears the current model and imports; the model content comes only from that file |
| Replace the current model with a CMO3 | **Model → Replace the model with a CMO3…**, or **Replace with a CMO3…** on the Start page | Keeps the current project, replaces objects in the model with those of the CMO3 by ID and adds new objects; objects not in the CMO3 stay unchanged |
Neither method generates presets. After import, parameters and keyforms, deformers and the layer hierarchy, textures and meshes, and physics are kept.
## Notes
- The Materials panel can also import a CMO3, using its layered images as materials.
- Parts marked as guide images in Cubism are not written when exporting moc3 by default; tick **Export guide image parts** in the export window if you need them.
!> Models imported from CMO3 cannot use [[split-by-mesh]] or [[depth-split]].
## Differences from Cubism
- Cubism: `.cmo3` is Cubism Editor's native model file and is opened and edited directly.
- PSD2Live: `.cmo3` is an import format and becomes a PSD2Live project (`.psd2live`) when read; you can import it whole or replace objects in the current model by ID. After exporting a CMO3 you can go back to editing it in Cubism Editor.

@@ texture | Texture
@@ original-picture | About Source Image and Model Guide Image
@@ psd-link | Linking Source Image to Model Guide Image
@@ texture-atlas-edit | Edit Texture Atlas
The atlas is the texture pages into which all layer pixels are packed for export. PSD2Live shows the atlas in the **Texture** workspace, where you can adjust each layer's tile position, density and rotation and set the page budget.
## Three sizes
Each layer has three sizes: the rectangle it covers on the canvas (canvas units), the pixels of its own raster, and its tile pixels in the atlas. The **Texture** panel shows these three for the selected layer side by side as "Canvas → Source → Atlas".
## Working on the atlas page
- Click a tile to select its layer, or drag on empty space to box-select; selection syncs with the canvas and the layer list.
- **Drag a tile** to move it; when its mesh overlaps other tiles or it leaves the page, the tile turns red and is not moved on release.
- Drag a corner of the selected tile to scale density proportionally; drag the round handle above the top edge to rotate, holding {kbd:Shift} for 15° steps.
- Right-click a tile to double / halve density, turn it 90°, move it to another page, lock its size, arrange the selected tiles or replace the image.
Moving, scaling and rotating first enter an adjustment session: a session bar at the top of the page offers undo, redo and **Discard**, and **Apply** commits it as one history step.
## Arrangement and budget
- **Arrange** at the top left arranges once by the menu options and saves: **By mesh shape (nest tiles)** or **By rectangles**, over **All tiles** or **Selected tiles only**.
- With **Auto arrange** on, tiles are rearranged by rectangles after every change; when off (the default for new projects), the current layout is kept.
- **Atlas budget** at the top right sets the page size, maximum pages and padding. A "Fit" below 100% means things do not fit, and all unlocked tiles shrink together; layers with **Lock size** keep their size.
## Texture panel
The **Texture density** slider and the ½× / 2× / reset buttons adjust how many atlas pixels correspond to each source pixel (1/64–16). With no layer selected, the panel shows an overview of each page's size, tile count, usage, estimated video memory and so on.
## Differences from Cubism
- Cubism: ArtMeshes are placed and scaled by hand in the texture atlas editing dialog, and atlases are set up by texture size.
- PSD2Live: the atlas is packed automatically with the model and can be adjusted at any time in the Texture workspace, with overlaps judged by the area each mesh actually covers; the export dialog shows only an atlas summary.
*> **Source pixels** in the display bar at the bottom right of the edit canvas makes the canvas sample each layer's own raster directly, for comparing sharpness after packing.

@@ png-file-export-replacement | Exporting and Replacing PNG Files
Export textures as images, edit them in external software, then replace them back into the model. PSD2Live offers three ways: exporting a whole page, replacing a single layer and exporting a PSD.
## Exporting
- **Save page as PNG…**: right-click empty space on the atlas page in [[texture-atlas-edit]]. What is written matches the texture in the exported model byte for byte.
- **File → Export as → Source PSD…** ({key:REEXPORT_PSD}): exports a layered PSD at 1× or an upscale factor, optionally with **Include generated auxiliary layers**, for detailed per-layer touch-ups in drawing software.
- In the Materials panel, right-click a folder → **Export folder as PSD…**.
## Replacing
1. Select the layer to replace.
2. In the **Texture** panel click **Replace image…** (or right-click the tile on the atlas page).
3. Choose the fit, **Stretch** or **Contain**, and whether to **Rebuild the mesh from the new pixels**.
The new image can be any resolution, and the canvas rectangle stays the same; a larger image raises the layer's density. You can also right-click in the Materials panel → **Replace selected layer's image**.
!> For layers with author edits, bindings or materialized meshes, replacing the image and rebuilding the mesh is refused; the reason is shown above the atlas page.
## Differences from Cubism
- Cubism: PNGs are output per atlas page, and after editing the whole page is replaced back.
- PSD2Live: whole-page PNGs are output only; replacement works per layer, and the atlas is repacked automatically, so tile positions need not stay the same.
*> To fix just a few pixels, using [[paint-mode]] directly in the editor is easier.

@@ texture-upscale | Texture upscale
Texture upscale enlarges textures layer by layer before the atlas is packed, for artwork whose resolution is on the low side. It is off by default and does not change the original PSD, canvas size or rig; preview and export use the processed textures.
## Settings
Open **Model → Texture Upscale…** ({key:TEXTURE_UPSCALE}), or click **Texture Upscale…** in the **Texture** panel.
| Item | Contents |
|---|---|
| Python | A Python executable with the nunif dependencies installed |
| nunif folder | A local source folder containing the `nunif` and `waifu2x` subfolders |
| Model folder | The folder holding the Art `.pth` weights |
| Scale | 1 is off; 2× or 4× upscales |
| Denoise | -1 only upscales; 0–3 are denoise levels, default 1 |
| Neural alpha | On by default; when off, alpha is upscaled bilinearly |
| Input tile | 64–512, default 256; lower it when video memory runs short |
Applying rebuilds the preview, and the configuration is saved with the project; on another computer, check the local paths again.
## Notes
- Release packages do not include an inference environment; prepare Python, PyTorch, nunif and matching weights yourself.
- When no matching model is available, an error is reported rather than falling back to ordinary interpolation.
- Neural alpha can change semi-transparent outlines; check hair strands, lashes, lip lines and mask edges in motion against both dark and light backgrounds.
{tutorial=TEXTURE_UPSCALE}
## Relation to Cubism
Cubism Editor has no texture upscaling; you usually upscale in external software and then reload the PSD or replace the textures. PSD2Live makes upscaling a step of the generation pipeline, leaves the original materials untouched, and lets you change the scale or turn it off at any time.

@@ paint-mode | Paint mode
Paint mode changes layer pixels directly, for trimming edges, filling colors or erasing. Strokes are first kept in a separate paint session and are written into the project as one history step only when confirmed.
## Starting to paint
1. Select a layer on the canvas or in the layer list.
2. Choose **Paint** ({key:MODE_PAINT}) from the mode menu at the top left of the canvas.
3. Pick a tool in the left toolbar and adjust size, hardness, opacity and other values in the tool options bar at the bottom left.
## Tools
| Tool | Shortcut |
|---|---|
| Brush | {key:TOOL_PAINT_BRUSH} |
| Pencil | {key:TOOL_PAINT_PENCIL} |
| Eraser | {key:TOOL_PAINT_ERASER} |
| Paint Bucket | {key:TOOL_PAINT_BUCKET} |
| Eyedropper | {key:TOOL_PAINT_EYEDROPPER} |
| Shape (line / rectangle / ellipse) | {key:TOOL_PAINT_LINE}, {key:PAINT_SHAPE_CYCLE} switches shape |
Brush size {key:BRUSH_RADIUS_DOWN} / {key:BRUSH_RADIUS_UP}, hardness {key:BRUSH_HARDNESS_DOWN} / {key:BRUSH_HARDNESS_UP}, opacity {key:PAINT_OPACITY_DOWN} / {key:PAINT_OPACITY_UP}; drag with {key:BRUSH_ADJUST_DRAG} to adjust on the spot. The tool options bar starts with the foreground / background colors; {key:AXIS_CONSTRAIN_X} swaps them.
## Applying and discarding
After the first stroke, a session bar appears at the top center of the canvas showing the layer being painted and the stroke count, with undo, redo, **Discard** and **Apply**; the "Tool Details" panel also has **Discard changes** and **Apply**. During the session, {key:UNDO} undoes strokes only.
## Painting outside the mesh
You can paint anywhere on the canvas. On apply, the result is cut by the layer's mesh: the part on the mesh stays in the layer, with mesh, keyforms and bindings unchanged; the part outside the mesh becomes a new layer named after the original plus "+", placed directly above the original and moving with the same parent. To have the layer's own mesh cover the newly painted area, click **Rebuild from pixels** in the Mesh panel.
*> For a layer linked to a material, painting is written into the material itself, and every layer using that material changes together; see [[layout-project]].
{tutorial=PAINT_MODE}
## Relation to Cubism
Cubism Editor cannot edit pixels; changing the artwork means going back to the drawing software and then reloading the PSD or replacing the textures. PSD2Live's Paint mode keeps small fixes inside the editor, saved with the project and its history.

@@ parts-folder | Part
@@ parts | About Parts
Parts are organizational units that group ArtMeshes and deformers, used to organize objects, show or hide them together and group them on export. When PSD2Live generates a model, it builds parts automatically from the recognized body parts.
## Automatically generated parts
The generator builds parts such as "Head", "Back Hair", "Face", "Front Hair", "Head Accessories", "Extra" and "Body", and layers go into the matching part by their tags; unrecognized layers go into "Body".
## Parts and the hierarchy
PSD2Live's **Hierarchy** shows the parent-child relations of deformers and drawables (which decide how deformation is inherited), not a part tree. Part membership is shown in the **Inspector**: select a drawable and the **Part** dropdown shows the part it belongs to.
## Differences from Cubism
- Cubism: PSD folders become parts on loading, and the Parts palette shows and edits parts and objects as a tree.
- PSD2Live: parts are built automatically by body part, independent of the PSD folder structure; there is no separate Parts panel, and the deformer hierarchy is edited in the Hierarchy.
- When exporting moc3, **Export hidden parts** in the export window decides whether hidden parts are written.
*> Models imported from CMO3 keep their original part structure.

@@ edit-parts | Editing Parts
This page explains how to change which part an object belongs to.
## Changing a drawable's part
1. Select the drawable on the canvas or in the hierarchy.
2. In the **Part** dropdown of the **Inspector**, choose the target part, or choose **(None)** to move it to the top level.
The change is recorded as one undoable history step.
## Choosing a part when creating a deformer
When creating a Warp in Object mode, **Part to insert** in the "Tool Details" panel chooses which part the new deformer belongs to; the default is **Inherit from selection**.
## Differences from Cubism
- Cubism: create, delete and rename parts in the Parts palette, and drag objects to change their membership.
- PSD2Live: parts are built by the generator, and the interface can only change the part of drawables and of newly created deformers; deformer parent-child relations are changed by dragging in the **Hierarchy**; see [[partspalatte]].

@@ edit-parts-sketch | Guide image parts
Images used only for comparison, such as the original artwork or sketches, can be set as references so they show on the canvas without taking part in generation, and are not exported. PSD2Live uses "reference layers" for the role of Cubism's guide image parts.
## Setting a reference
1. Select the layer.
2. Click **Use as Reference** in the toolbar of the "Layers" panel, or press {key:LAYER_TOGGLE_REFERENCE}; the canvas context menu also has a **Use as Reference** toggle.
A reference layer has a reference icon after its name; click it to make it a model layer again. Generated layers cannot be made references; a restored reference layer joins the model at the next generation.
## Display
Reference layers sit under the model by default; **Reference layers on top** in the display bar at the bottom right of the canvas stacks them above the model. References for different angles can be shown only at the matching parameter values with **Pin to Keyform** in the context menu.
## Differences from Cubism
- Cubism: a part is set as a guide image part and is not written on export.
- PSD2Live: set per layer, not per part; reference layers take no part in generation and are not exported.
- Guide image parts in models imported from CMO3 are not written when exporting moc3 by default; tick **Export guide image parts** in the export window to write them.
For placing a new reference image, see [[sketch]].

@@ variants | Variants
Variants let layers show, hide or switch with a parameter, for "on / off" parts such as blush, tears and glasses, and for "one of several" looks such as expressions, gestures and outfits. They are set in the **Variant** column of the "Layers" panel.
## Kinds of variant
| Kind | What it does | Use for |
|---|---|---|
| None | Generates the part given by the tags | Ordinary layers |
| Toggle Variant | One parameter shows or hides the layer | Blush, tears, glasses, lighting |
| Switch Variant | One parameter shows only the layer with one ID at a time | Expressions, gestures, outfits |
## Setting up a toggle variant
1. In the "Layers" panel, set the row's **Variant** to **Toggle Variant**.
2. Enter a parameter name (such as `blush`) under **Parameter**. One parameter name can drive several toggle layers.
## Setting up a switch variant
1. Set all the related layers to **Switch Variant**.
2. Enter the same parameter name under **Parameter**, or pick an existing switch parameter from the dropdown.
3. Set the ID next to it to 0, 1, 2… in turn. For example `expression`: 0 normal / 1 happy.
Drag the parameter to see the result in editing and preview.
!> Numbers in layer names do not create variants automatically; a toggle variant needs a parameter, and a switch variant needs a shared parameter with different IDs.
{tutorial=VARIANTS}
## Relation to Cubism
In Cubism, variants are usually made by keying an ArtMesh's opacity on a parameter. PSD2Live declares variants on layers and builds the matching parameters and visibility control automatically at generation. Temporary solo in the tree is only for checking while editing and is not a variant.

@@ copy-and-paste | Copy and Paste Objects
@@ search-and-replace | Search and Replace Objects
@@ offscreen-drawing | Offscreen drawing
@@ draw-order | Draw order
@@ draworder | About Draw Order
Draw order decides how ArtMeshes cover one another: higher values are further in front. The range is 0–1000, default 500. Draw order is independent of the deformer parent-child relations.
## Changing draw order
The same property has three entry points:
- The **Draw Order** field in the **Inspector**.
- Right-click a drawable in the hierarchy → **Draw order…** and enter a value, or **Reset to Default**.
- The draw order ruler beside the hierarchy: zoom with the wheel, drag to reorder, double-click to type a value.
Choose **Draw order (front to back)** from the hierarchy's **Sort** menu to see the front-to-back order.
{tutorial=INSPECTOR}
*> Layout layers not yet generated are reordered up and down ({key:LAYER_FORWARD} / {key:LAYER_BACKWARD}); after generation, use draw order instead; see [[layout-project]].

@@ drawing-order-group | Draw order group
@@ artmesh | ArtMesh
@@ concept-of-artmesh | About ArtMeshes
An ArtMesh is an object that maps a texture onto a mesh of vertices and triangles, so moving the vertices deforms the image. Each layer of a PSD becomes an ArtMesh on import; in PSD2Live it is also called a drawable.
## What a mesh is made of
- Vertices: points on the mesh, the things that move during deformation.
- Edges and triangles: connect the vertices and decide how the image stretches with them.
- Outline: follows the edge of the layer's opaque pixels; pixels outside the mesh are not shown.
Denser vertices give finer deformation but cost more in editing and at runtime. PSD2Live generates meshes automatically from layer alpha on import; see [[mesh-edit]], and for local adjustments see [[mesh-edit-manual]].
## Properties
With an ArtMesh selected, the **Inspector** shows name, ID, part, deformer, clip ID, inverted mask, draw order, opacity, multiply color, screen color, blend mode, culling and user data.
{tutorial=LIVE2D_BRIDGE}
*> Warp deformers and rotation deformers deform ArtMeshes as a whole; see [[deformer]].

@@ mesh-edit | Automatic Mesh generator
When importing a PSD, PSD2Live generates a mesh for every layer automatically: it first traces an outline along the layer's alpha, then places edge and interior vertices according to the settings and triangulates. Mesh density and shape are decided by the settings in the **Mesh** panel, which can be set globally or overridden per part.
## Mesh panel
Open the **Mesh** panel from the **Mesh** workspace or the **Windows** menu:
- **Global defaults** apply to every part without its own override.
- With an ArtMesh selected, the panel shows that part's own settings, and after a change the part shows "Custom Settings".
- When the mesh does not cover some visible pixels, the panel shows the proportion; click **Rebuild from pixels** to include them.
## Main settings
| Group | Setting | What it does |
|---|---|---|
| Contour Placement | Single / double / triple edge rows, outer margin, edge mesh width | How many rings of vertices sit near the outline and where |
| Vertex Spacing | Edge distance, interior density | Spacing of outline vertices and of interior vertices |
| Interior fill algorithm | Graded Poisson fill (default), balanced quadtree fill, triangle fractal subdivision, contour paving, simple triangular fill | How interior vertices are laid out |
| Connections | Suppress diagonals between boundary vertices | Connects non-adjacent edge vertices through interior vertices |
| Units | Scale with document size, trace outlines from texture pixels, wrap | The length unit, the precision of outline tracing, and gaps between small protrusions such as closed lashes |
Changing the settings rebuilds the mesh, and existing keyforms, paths, weights and glue migrate to the new mesh.
## Differences from Cubism
- Cubism: after loading, run automatic mesh generation on the selected ArtMeshes, choose a preset and adjust margins and point spacing.
- PSD2Live: meshes are already generated for all layers on import, and the settings are saved as global defaults and part overrides, rebuilding whenever you change them.
- PSD2Live offers multiple edge rows and several interior fill algorithms, plus tracing outlines from texture pixels and wrap topology.
*> **Show descriptions** in the settings panel expands an explanation of each option.

@@ mesh-edit-manual | Edit Mesh manually
When an automatically generated mesh does not fit, you can change its topology by hand in **Edit** mode: subdivide, connect, merge, duplicate, delete and cut. When the topology changes, UVs and the vertex references of keyforms, blend shapes and glue are updated along with it.
## Entering Edit mode
1. Select one or more drawables.
2. Choose **Edit** ({key:MODE_EDIT}) from the mode menu. Edit mode shows the mesh.
3. On the mode bar choose vertex / edge / face ({key:PICK_OPTION_1}–{key:PICK_OPTION_3}), which decides what the selection tools pick.
## Operations
| Operation | Result |
|---|---|
| Subdivide ({key:TOOL_SUBDIVIDE}) | Adds points on the edges hit and rebuilds the triangles; neighboring faces fix the seam too |
| Connect / merge | Connects the selected points, or merges the selected vertices into one |
| Duplicate | Duplicates the selected vertices and the faces they fully cover; the copy becomes the selection |
| Delete ({key:DELETE_SELECTION}) | Deletes the selection and tries to rebuild the gap; refused if no valid topology can be formed |
| Knife ({key:TOOL_KNIFE}) | Places a polyline point by point, snapping near points or edges, with {kbd:Shift} turning snapping off temporarily; {key:FINISH_PATH} finishes, {key:CANCEL} cancels |
## Editing several meshes
Edit mode works on all selected drawables at once. Clicking a vertex of one mesh makes it the primary mesh, and the knife and edge / face selection act only on the primary mesh.
{tutorial=EDIT_MODE}
## Differences from Cubism
- Cubism: in manual mesh edit mode you add and delete vertices and draw connections point by point, then exit the mode to confirm.
- PSD2Live: existing meshes are changed with topology operations such as subdivide and cut, each going straight into history; meshes that already have keyforms can be changed too.
!> Deleting outline points changes the boundary; afterwards, check under several parameter poses. Everyday reshaping belongs in Deform mode.

@@ mesh-duplication | Mesh duplication
@@ clipping-mask | Clipping Mask
A clipping mask makes an ArtMesh show only within the area of another ArtMesh, for example the iris showing only inside the eye white.
## Setting it up
1. Select the ArtMesh to be masked.
2. Under **Clip ID** in the **Inspector**, specify the ArtMesh that acts as the mask: type the ID directly (separate several with commas), click the list button **Select clipping mask from list**, or click the crosshair button **Quick link mask from canvas** and then click the mask drawable on the canvas.
One ArtMesh can be limited by several masks together.
*> Common masks are set up automatically when the model is generated: the iris is masked by the eye white on the same side, and parts inside the mouth such as teeth and tongue are masked by the mouth. For the inverted effect, see [[reversed-mask]].

@@ reversed-mask | Reversed mask
An inverted mask makes an ArtMesh show only outside the mask area, the opposite of [[clipping-mask]].
## Setting it up
1. Select an ArtMesh that already has a **Clip ID**.
2. Tick **Invert Mask** in the **Inspector**.
The change is recorded as one undoable history step.

@@ sketch | Placement of Guide Image
Put the original artwork, sketches or references from other angles on the canvas for comparison; layers used as references show on the canvas but take no part in generation and are not exported.
## Placing a reference image
1. **Import** the reference image in the Materials panel.
2. Right-click the material and choose **Place as reference layer**; with **Place as reference by default** on, every layer dragged onto the canvas from that material is a reference layer.
3. Adjust position and size with the transform box.
Layers already on the canvas can be toggled with **Use as Reference** in the toolbar of the "Layers" panel or {key:LAYER_TOGGLE_REFERENCE}; see [[edit-parts-sketch]].
## Showing by pose
References for different angles (front, side, looking up) can be stacked in the same place, each showing only at its matching keyform:
1. Right-click the reference layer → **Pin to Keyform**.
2. The menu lists the parameters worth pinning and their current values; click a pin to pin at that value or unpin; **Pin at Current Pose** pins all the listed parameters at once, and **Unpin** makes it show in every pose again.
## Differences from Cubism
- Cubism: the guide image placement feature adds the image to the model as a guide image part.
- PSD2Live: a reference image is a material that becomes a reference layer when placed, and can be pinned to keyforms to show only in the matching pose.
- Reference layers sit under the model by default; **Reference layers on top** in the display bar at the bottom right of the canvas stacks them above.

@@ hittest | Preparation for Setting Up Collision Detection
@@ blend-mode | Blend mode
The blend mode decides how an ArtMesh's colors are composited with what has already been drawn below.
## Setting it up
Select the ArtMesh and choose under **Blend Mode** in the **Inspector**:
| Mode | Effect |
|---|---|
| Normal | Ordinary overlay |
| Multiply | Multiplies with the colors below, for shadows |
| Add | Adds to the colors below, for glow and highlights |
## On PSD import
PSD layer blend modes are converted on import: additive modes such as Linear Dodge (Add), and Linear Light, become Add; Multiply, Linear Burn and Color Burn become Multiply; other modes are treated as Normal.
## Differences from Cubism
- From Cubism 5.3, more blend modes are available (such as Screen and Overlay), and they can be combined with offscreen drawing.
- PSD2Live's Inspector offers only Normal, Multiply and Add; offscreen drawing is not available yet.
*> To adjust colors without changing how they blend, use **Multiply Color** and **Screen Color** in the Inspector.

@@ interior-shape | Interior shape
Interior shape is an optional mesh keyform generator: it makes the texture inside a layer compress, stretch and press along a virtual surface, giving large parts such as clothes and hair a sense of volume when they turn. It does not generate new textures or shading, and it is not cloth simulation.
## Using it
1. Select a generated layer and open the **Interior shape** panel.
2. Tick **Enabled**; the program generates the interior shape and starts the audition.
3. Under **Shape**, adjust horizontal / vertical range, surface bulge, pressure, fold strength and outline keeping.
4. If the motion needs easing, open **Easing / propagation** and set response time, propagation time, propagation mix and **Direction**.
5. Under **Audition**, drag the 2D parameter pad or turn on **Mouse follow** to check the result; **Solo / frame** shows only this layer and centers it on the canvas.
Ending a drag commits one history record. If generation fails, the draft and the previous valid result are kept.
## Settings
| Setting | Range | Meaning |
|---|---|---|
| Horizontal / vertical range | 0–2 | The overall amount of shaping in each direction |
| Surface bulge / pressure | 0–2 | The virtual arch and how much it presses; if either is 0 there is no surface displacement |
| Fold strength | 0–2 | The contribution of **Local folds**; has no effect without local folds |
| Keep outline | 0–1 | 0 frees the visible outline, 1 protects the original outline |
| Response time | 0–0.6 s | The response time of the first stage |
| Propagation time | 0–0.5 s | How long the rear zone lags behind the front zone |
| Propagation mix | 0–1 | The weight of the rear zone; 0 means no zones |
The horizontal / vertical mouse gains under **More settings** affect only the mouse audition, not the keyform range or the exported parameter range. **Source parameters** can pick existing parameters or **New parameter**.
## Notes
- Turning off **Enabled** keeps the settings without applying the deformation; **Delete effect** removes the recipe and its physics, and can be undone.
- When the mesh, material or source parameters change, the panel prompts you to regenerate or rebind; invalid effects are skipped on export and listed in the warnings.
- Easing uses the project's physics groups and exports as standard physics settings.
## Relation to Cubism
In Cubism, a similar sense of volume requires adjusting mesh vertices by hand on several parameter keys, or combining deformers. Interior shape generates these deformations automatically and exports them as Cubism-native blend shapes and physics settings.

@@ artpath-folder | ArtPath
@@ artpath | About ArtPaths
@@ artpath-auto-edit | ArtPath Auto Generation
@@ artpath-edit-manual | ArtPath Manual Creation
@@ artpath-brush-custom | ArtPath Brush Editing
@@ artpath-adjustment | ArtPath adjustment
@@ stroke-artpath | Stroke ArtPath / Re-stroke ArtPath

@@ deformer-folder | Deformer
@@ deformer | About Deformers
A deformer is a "container" that deforms ArtMeshes as a whole. Meshes sit under a deformer; when the deformer changes shape with a parameter, the meshes and child deformers below it deform along with it. Like Cubism, PSD2Live has two kinds of deformers: the warp deformer (Warp) and the rotation deformer (Rotation).
## The two kinds of deformers
| Kind | UI name | What it does | Suited to |
|---|---|---|---|
| Warp deformer | Warp | Wraps its children in a grid of control points; moving the control points deforms the inside smoothly | Face turns, body bends, hair sway |
| Rotation deformer | Rotation | Rotates everything around an origin (optionally with scale); children stay rigid | Arms, neck, accessories and other parts that turn around a joint |
## Viewing them in PSD2Live
- The **Hierarchy** lists deformers and ArtMeshes by parent-child relation; clicking stays in sync with the canvas selection.
- The **Deformer Warp** and **Deformer Rotation** toggles in the display rail at the bottom right of the canvas control whether deformers are shown on the canvas.
- After a PSD is imported, the program has already generated a complete deformer structure from the layer tags (body, head, face, hair and so on), so you usually do not have to build one from scratch; see [[auto-generation-of-deformer]].
*> In Deform mode a warp deformer can switch between the **Vertices** and **Bezier** edit levels; see [[making-and-placement-of-warp-deformer]].
Related: [[system-of-parent-child-relation]] · [[setting-of-parent-child-relation]] · [[deformerpalatte]]
{tutorial=CREATE_DEFORMER}

@@ making-and-placement-of-warp-deformer | Warp Deformer
A warp deformer (Warp) wraps the selected objects in a rows × columns grid of control points; moving the control points bends the objects smoothly. In PSD2Live you create it by dragging a box on the canvas, and the placement preview is the final grid.
## Creating
1. Select the ArtMeshes to put into the deformer in the Hierarchy or on the canvas (when several are selected they must share the same parent deformer).
2. Choose **Create Warp** ({key:TOOL_CREATE_WARP}) in the last group of the left toolbar in Object mode; you can also pick it under Create in the canvas context menu, or choose **Parent · Warp** / **Child · Warp** from the Hierarchy context menu.
3. Drag a rectangle on the canvas; while dragging, the mouse wheel changes the conversion divisions. You can also click **Create from selection** to generate it directly from the extent of the selected objects.
4. Check the attach position, part to insert and size strategy in the placement panel at the bottom left; drag inside the box to move it, and drag a corner or edge midpoint to scale it.
5. Click **Create** on the mode bar or press {key:FINISH_PATH} to write it to the project; **Cancel** or {key:CANCEL} discards it.
## Placement options
| Option | Content |
|---|---|
| Add to | Set as parent of selected / Set as child of selected deformer (empty) / Specify parent deformer |
| Part to insert | The part the new deformer goes into; inherits the selection's part by default |
| Size | Fit selection (+5% padding) / Include keyform envelope / Align center with selection |
| Sequential create | Keep placing the next one after creating |
## Edit levels
In Deform mode, when the target is a warp deformer, the mode bar switches between **Vertices** and **Bezier** (number keys 1 / 2). The Vertices level edits grid points; at the Bezier level you can only drag the cyan anchors and amber handles, and the detailed grid fades and cannot be edited. In the Inspector you can set **Bezier Division**, **Reset Bezier Control Points** and **Deformer Conversion (3.2 method)**.
## Differences from Cubism
- Cubism creates a deformer after you enter a name and divisions in a dialog; PSD2Live creates it by dragging a box on the canvas, divisions are adjusted with the mouse wheel while dragging, and the attach position and size strategy are chosen in the placement panel.
- The Warp placement box is created as a rectangle and can only be moved and scaled, not rotated.
- The **Tool Details** panel stores the creation defaults for Warp / Rotation; the model presets also hold the default divisions for deformers created on the canvas.
{tutorial=CREATE_DEFORMER}

@@ making-and-rotation-of-rotationdeformer | Rotation Deformer
A rotation deformer turns the objects under it as a whole around an origin, suited to arms, neck, accessories and other parts that turn around a joint. Its shape is defined by the origin, angle and scale, and it is an exact rigid rotation at any parameter value.
## Creating
1. Select the ArtMeshes or deformers to rotate.
2. Choose **Create Rotation** ({key:TOOL_CREATE_ROTATION}) in the Object mode toolbar, or pick it under Create in the canvas context menu or **Parent · Rotation** in the Hierarchy context menu.
3. Drag on the canvas from the rotation center toward the direction end; hold Shift to snap the angle in 15° steps.
4. The new deformer is inserted as the direct parent of the selected objects (keeping their original parent); the canvas highlights the objects that will be reparented, and the placement panel shows how many deformers and ArtMeshes will be affected.
5. Press {key:FINISH_PATH} or click **Create** to confirm, {key:CANCEL} to cancel.
## Editing
In Deform mode: drag the origin to move it and drag the end to rotate; by default hold Shift to snap the angle, and Alt + drag the end to adjust the scale. The deform brushes do not work on rotation deformers; use the move, rotate or scale tools.
## Differences from Cubism
- Cubism creates the deformer in a dialog first and then adjusts its position; PSD2Live sets the origin and direction in one drag and automatically inserts it as the parent of the selected objects.
- The handle direction follows Cubism's convention: up at 0°, clockwise positive.
- Skeleton baking generates rotation deformers for limbs, tails and wings automatically; see [[skeleton]].
{tutorial=CREATE_DEFORMER}

@@ system-of-parent-child-relation | Parent-Child Hierarchy Structure
An object follows whichever deformer it is attached to; this is the parent-child structure. A child's shape is evaluated in its parent's local space, so when the parent deforms, the child is carried along as a whole; the child's own keyforms are then layered on top.
## Inheritance rules
- Objects under a warp deformer: the whole shape bends together with the parent grid.
- A warp deformer under a warp deformer: the child deformer's control points are transformed by the parent, and its inside is then interpolated by its own grid.
- A rotation deformer under a warp deformer: only the origin position and angle are inherited; the parent's non-rigid bending does not pass below it.
- Objects under a rotation deformer: they turn rigidly with the origin.
## Parents and direct bindings
The parameters an object is bound to itself determine its own shape; the parent's parameters are passed down through the hierarchy. When editing, first make sure whether you want to change the object's own axis or the motion brought by its parent, so you do not mistake the parent's pose for the object's direct binding.
*> The **Parent** badge in the Hierarchy marks the ancestors of the selected object.
Related: [[setting-of-parent-child-relation]] · [[combintion-of-parent-child-relation]] · [[keyform-parent-chilid-relation]]

@@ setting-of-parent-child-relation | Setting Up Parent-Child Hierarchies
Set which deformer an object is attached to. In PSD2Live this is mainly done by dragging in the Hierarchy; you can also specify the attach position directly when creating a deformer.
## Setting it in the Hierarchy
1. In the Hierarchy, press and hold the row of an ArtMesh or deformer.
2. Drop it on the target deformer to attach it there; drop it on the move-to-root area to move it to the root.
3. Changing the parent is one history step and can be undone.
## Other ways
- Hierarchy context menu: **Parent · Warp**, **Child · Warp** and **Parent · Rotation** insert a new deformer above or below the selected objects; when a deformer is deleted its children move up.
- **Add to** when creating a Warp: Set as parent of selected / Set as child of selected deformer (empty) / Specify parent deformer.
- The Inspector shows the part and deformer an object belongs to.
!> Dragging transparent images onto the Hierarchy imports layers; it is not the same operation as reparenting.
## Differences from Cubism
- Cubism chooses the parent in the Inspector or the "Set parent" dialog; PSD2Live works mainly by drag and drop in the Hierarchy, and the parent-child relation can be set while creating a deformer.
- If you reparent objects in a generated model and later regenerate (changing the model presets, the skeleton and so on), your changes are kept by a three-way merge; see [[update-generated-rig]].
{tutorial=HIERARCHY}

@@ combintion-of-parent-child-relation | Combination of Parent-Child Hierarchy
Nesting several deformers lets you split a complex motion into a few simple layers: the outer layer handles the large turn, the inner layers handle local bending or rotation, and each layer binds only the parameters it needs.
## Combinations in the generated model
The body PSD2Live generates is layered this way:
1. `DeformBodyXY`: body X / Y; the whole upper body turns and follows the hips.
2. `DeformBodyLean`: body lean and proportion (head-to-body ratio).
3. `DeformBodyZBreath`: body Z and breathing.
4. Below these are the head rotation and the face lattice, and below those the facial features and hair.
Each layer handles only its own parameters, and lower layers inherit the motion of upper layers automatically; for example, the head rotation is also carried by the body turn. The Hierarchy shows the actual structure.
## Things to watch when combining
- An upper deformer with a coarse grid resamples the shapes below it; put fine bending in the layer closest to the mesh.
- A rotation deformer under a warp deformer inherits only the origin and angle, so parts that should follow the bending belong under a warp deformer.
*> With a skeleton enabled, the torso bend and limb rotation deformers attach to the end of this chain; see [[skeleton]].

@@ auto-generation-of-deformer | Auto Generation of Deformer
When PSD2Live imports a PSD it automatically generates the full set of deformers, parameters and keyforms from the layer tags; you do not need to create them one by one or apply templates as in Cubism.
## What is generated
- Body chain: body X/Y, lean, body Z and breathing; standing full-body characters also get leg deformers.
- Head: head rotation and head-shell follow, the face lattice, face outline and feature displacement; see [[face-auto-edit]].
- Hair: follow and sway branches for front and back hair; you can also generate [[auto-generation-of-sway-motion]] or simulation separately.
- Skeleton (when enabled): torso bend and limb rotation deformers; see [[skeleton]].
## Adjusting the result
1. Adjust the deformation strength, Rig Values and other settings in the **Model presets** panel; the model regenerates accordingly.
2. Edits you make on the generated result are merged onto the new result and kept.
3. To adopt improvements from a newer generator version, use **Model → Update Generated Rig**.
## Automatic size when creating by hand
When using **Create Warp**, click **Create from selection**, or choose a size strategy in the placement panel (Fit selection / Include keyform envelope / Align center with selection), and the deformer is sized automatically from the selected objects.
## Differences from Cubism
- Cubism's auto generation creates a single deformer structure for the selected objects; PSD2Live generates the deformer chain and keyforms for the whole model at import.
- The generated structure changes with the model presets; the Hierarchy shows the actual objects.
Related: [[model-presets]] · [[update-generated-rig]] · [[psd-import]]

@@ convenient-function-deformer | Validate Deformer Function
When checking a deformer structure you often need to see where an object is attached and which deformers and parameters affect it. PSD2Live puts this information in the canvas display rail, the Hierarchy and the Parameters panel.
## Available checks
| Location | Function |
|---|---|
| Display rail at the bottom right of the canvas | **Deformer Warp** and **Deformer Rotation** toggles; **Contextual warp** shows only deformers related to the selected object; **Dim unselected**; **Names** and **Selected only** |
| Hierarchy | The **Parent** badge marks the ancestors of the selected object; search deformers and layers; expand / collapse all |
| Parameters panel | **Related only** lists only parameters bound to the selected components; hover a parameter key mark to see the components with keyforms on that key |
| Canvas | {key:FRAME_VIEW} frames the selected objects |
## Differences from Cubism
- Cubism provides dedicated commands for displaying and validating deformers; PSD2Live does the same checks with the display rail toggles and the Parameters panel filter, without a separate validation menu.
{tutorial=HIERARCHY}

@@ parameter-folder | Parameter
@@ parameter | About Parameters
Parameters are the numeric sliders that drive the model's motion, such as Angle X or eye open/close. An object records a shape (keyform) on each of several keys of a parameter, and in-between values interpolate the shape automatically. Animation and physics ultimately just change parameter values over time.
## Parameters panel
| Action | How |
|---|---|
| Change the value | Drag the slider with the left button |
| Snap to a key | Hover over a key mark and right-click |
| 2D control | Two linked parameters appear as a 2D pad: drag with the left button, right-click to snap to the intersection of both parameters' keys |
| Reset / lock | The reset button in the row; lock the parameter value; **Reset All** and **Unlock All** on the toolbar |
| Find | Search by name or parameter ID; **Related only** shows only parameters bound to the selected components |
| Properties | Click the parameter name to open **Parameter Properties** |
## Key marks
- Round dot: a normal key; values between keys are interpolated.
- Square dot: a blend shape key, which adds the shape difference on top of the base shape; see [[blend-shape]].
- Hovering a key mark lists the components that have keyforms on that key.
- Parameters with a physics icon are physics output parameters and are driven by physics in the preview.
!> Keys on a parameter are modeling data, not the keyframes on the animation timeline.
*> Parameter operations in PSD2Live are basically the same as in Cubism; all canvases share one parameter pose.
{tutorial=PARAMETERS}

@@ edit-parameters | Add/Delete Keys to/from parameters
In PSD2Live, key positions are defined on the parameter and shared by every object bound to it; when an object is modified on a key, the shape is recorded on that key.
## Editing a parameter's keys
1. Click the parameter name in the Parameters panel to open **Parameter Properties**.
2. On the **Key points** axis: click an empty spot to add a point and drag to change its value; click a number to edit it directly and press Enter to confirm; right-click to delete.
3. **Step** sets the snap interval while dragging, and **Even** spreads the key points evenly; the minimum, default and maximum can be typed directly at the ends of the axis.
4. Step back with **Undo point edit** and **Redo point edit**, and click **Save** to commit everything at once.
## Binding an object to a parameter
1. Select the object.
2. In the Parameters panel, drag the parameter to the target key's value (or right-click to snap to the key); the parameter you operate in the panel becomes the edit target.
3. Switch to Deform mode ({key:MODE_DEFORM}) and change the shape; the result is written to that key's keyform.
!> When a key is moved, existing keyforms move with it; deleting a key also deletes every object's shape on that key, which can be undone.
## Differences from Cubism
- Cubism adds keys to a selected object individually with buttons such as "Add 2 points / 3 points"; PSD2Live edits a parameter's key points in one place in **Parameter Properties**, and objects record shapes by being deformed on a key.
- A new parameter does not produce any motion by itself; you need to give objects shapes on its keys.
{tutorial=PARAMETERS}

@@ parameter-modifyication | Parameter Adjustment
Change a parameter's name, range and keys in **Parameter Properties**, and organize parameter groups and display in the Parameters panel.
## Parameter Properties
1. Click the parameter name in the Parameters panel to open **Parameter Properties**.
2. Change the **Name**; the ID can only be edited when the parameter is created.
3. Change the minimum, default and maximum at the ends of the **Key points** axis; the default must lie within the range.
4. **Lock parameter value** keeps the preview at the current value.
5. **Duplicate** creates a new parameter with the same name and range (without copying component bindings or deformation data); **Delete Parameter** also removes the related keyframes and links.
6. Click **Save**.
## Groups and links
- Create parameters or folders from the toolbar; a folder's context menu can create a parameter / folder inside it, set the tag color, rename it or dissolve it.
- The link button in a row links the parameter with the next one into a 2D controller; **Unlink** turns them back into independent sliders. The height of the 2D pad can be adjusted by dragging.
!> Shrinking a parameter's range also clamps key values and Bezier control points in animations; deleting a parameter removes the corresponding animation tracks.
*> Changing a parameter definition is one history step and can be undone.

@@ standard-parameter-list | Standard Parameter List
Models generated by PSD2Live use Cubism standard parameter IDs, so they work directly with various runtimes and face tracking software. The table below lists the default generated parameters; with mesh mode, deformers turned off or missing parts, the actual parameter set may differ.
| ID | Name | Range | Default |
|---|---|---|---|
| `ParamAngleX` | Angle X | -45…45 | 0 |
| `ParamAngleY` / `ParamAngleZ` | Angle Y / Angle Z | -30…30 | 0 |
| `ParamBodyAngleX` / `Y` / `Z` | Body Angle X / Y / Z | -10…10 | 0 |
| `ParamBodyLean` | Body Lean | -10…10 | 0 |
| `ParamProportion` | Proportion (chibi) | -10…10 | 0 |
| `ParamEyeLOpen` / `ParamEyeROpen` | Left Eye Open / Right Eye Open | 0…1 | 1 |
| `ParamEyeBallX` / `ParamEyeBallY` | EyeBall X / EyeBall Y | -1…1 | 0 |
| `ParamEyeBallForm` | Eye Jelly | -1…1 | 0 |
| `ParamBrowLY` / `ParamBrowRY` | Left Brow Y / Right Brow Y | -1…1 | 0 |
| `ParamMouthForm` | Mouth Form | -1…1 | 0 |
| `ParamMouthOpenY` | Mouth Open | 0…1 | 0 |
| `ParamBreath` | Breath | 0…1 | 0 |
| `ParamHairFront` / `ParamHairBack` | Front Hair Sway / Back Hair Sway | -1…1 | 0 |
## Other automatic parameters
- Skeleton parameters: `ParamArmLA`, `ParamLegLA`, `ParamTail1..n` and so on, in degrees; see [[skeleton]].
- Swing parameters: `ParamSwing<id>`, range -1..1; see [[auto-generation-of-sway-motion]].
*> `ParamBodyLean` and `ParamProportion` are PSD2Live's own parameters and are not among the Cubism standard parameters.

@@ extended-interpolation-folder | Extended Interpolation
@@ extended-interpolation | About Extended Interpolation
@@ extended-type | Interpolation Type
@@ repeat | Repeat
@@ recording-parameters | Record Parameter Operations and Generate Animations
@@ copy-and-paste-parameter-values | Copy/Paste Parameter Values
PSD2Live saves and restores a set of parameter values with "parameter snapshots" instead of Cubism's copy / paste of parameter values. A snapshot records all of the model's current parameter values and can be loaded back at any time.
## Using parameter snapshots
1. Set the parameters to the pose you want in the Parameters panel.
2. Click **Save current parameters as a snapshot** in the snapshot bar; the new snapshot is named "Snapshot n".
3. Hover a snapshot to preview that pose on the canvas, click to load it, middle-click to delete it.
4. Right-click a snapshot for **Overwrite with current values**, **Rename** and **Delete**.
## Skeleton poses
In the **Pose Bones** tool of Skeleton mode, **Tool Details** can save the current model parameters under a name and apply them again; **Insert saved pose** in the animation editor inserts it at the playhead; see [[skeleton]].
## Differences from Cubism
- Cubism copies the values of selected parameters and pastes them elsewhere; PSD2Live saves the whole parameter pose and restores it as a whole when loaded.
- Snapshots and named poses only change parameter values and do not modify the model.

@@ motion-sync | Motion-sync
@@ keyform | Key form editing
@@ how-to-choose | How to make selections
Before editing keyforms, first select the object to edit, then select the points to move in Deform or Edit mode. Selection in PSD2Live works the same in every mode and is synced with the Hierarchy.
## Selecting objects (Object mode)
- Click to select, Shift to add, Alt to subtract; Ctrl + click cycles through overlapping objects.
- Drag to box-select; {key:SELECT_ALL} selects all, {key:CANCEL} cancels.
- Clicking in the Hierarchy is synced with the canvas; in the tree, Shift selects a range and Ctrl toggles.
## Selecting points (Deform / Edit mode)
| Tool | Shortcut | Use |
|---|---|---|
| Select | {key:TOOL_SELECT} | Click and box selection |
| Lasso Select | {key:TOOL_LASSO_SELECT} | Free-form selection |
| Brush Select | {key:TOOL_BRUSH_SELECT} | Select by painting like a brush |
| Temporary select | Hold {key:TEMPORARY_SELECT} | Release to return to the previous tool |
Meshes can be selected by point / edge / face (mode bar or `1`–`3`). {key:INVERT_SELECTION} inverts the selection, {key:SELECT_LINKED} selects connected parts.
## Differences from Cubism
- Cubism switches between object selection and point selection in the same view; PSD2Live separates them by canvas mode: Object mode selects only objects, Deform / Edit mode selects points.
- Edit mode can edit several selected meshes at once.
{tutorial=SELECT_MODE}

@@ how-to-edit | How to edit
Change object shapes in Deform mode. For an object not bound to any parameter you change its base shape; for a bound object you change the keyform at the current parameter combination.
## Basic flow
1. Select the object and switch to Deform mode ({key:MODE_DEFORM}).
2. Move the parameters to the target key in the Parameters panel.
3. Change the shape with the tools; when you release the mouse the result enters the history and can be undone.
## Tools
| Tool | Shortcut | What it does |
|---|---|---|
| Transform | {key:TOOL_TRANSFORM} | Move, scale and rotate the selected points with a transform box |
| Deform Brush | {key:TOOL_BRUSH} | Push the points within the brush radius; see [[deform-brush]] |
| Smooth Brush | {key:TOOL_SMOOTH} | Smooth the shape |
| Inflate Brush | {key:TOOL_INFLATE} | Inflate outward or shrink inward |
- Warp deformers can switch between the **Vertices** and **Bezier** edit levels; rotation deformers are edited by dragging the origin and the end.
- **Precise Transform** in **Tool Details** moves, scales and rotates the selected points by numbers; see [[edit-vertices]].
- **Mirror keyform** in the canvas context menu mirrors a shape to the opposite key or the opposite-side object; see [[motion-mirroring]].
## Differences from Cubism
- PSD2Live separates changing shapes (Deform mode) from changing mesh structure (Edit mode); tool settings are in the tool options bar at the bottom left and in the context menu.
- Edits on keyforms owned by a generator are recorded as generated overrides; see [[generated-overrides]].
{tutorial=DEFORM_MODE}

@@ keyform-parent-chilid-relation | Keyforms (Parent-Child Hierarchy Movement)
A parent deformer and its child objects can bind the same parameter and each record shapes on its keys; when the parameter changes, the overall motion carried by the parent and the child's own shape are layered to give the final result.
## Example
When the head turns on Angle X, the head deformer handles the turn of the whole head, and the face lattice and facial features each correct their shapes by Angle X. The generated model is bound in layers this way.
## Things to watch when editing
- After selecting an object, check which parameters it is bound to directly, and tell them apart from the motion its parent brings through the hierarchy.
- Shapes edited on a child are based on the parent's state in the current pose.
- Mirror keyform only handles the object's own deformation, using the parent at the default pose as the coordinate system; the parent's keyforms need to be mirrored separately.
*> **Related only** in the Parameters panel lists the parameters bound directly to the selected components.

@@ keyform-xydirection | Keyforms (Make X, Y Movements)
When an object is bound to two parameters at once (such as Angle X and Angle Y), its keyforms form a grid: every combination of an X key and a Y key has a shape, and diagonal poses are interpolated from the surrounding keys.
## How to make them
1. Link the two parameters into a 2D controller in the Parameters panel (the link button in the row), and right-click on the pad to snap to the intersection of both parameters' keys.
2. In Deform mode, make the shape for each intersection in turn: first the ends and midpoints, then the four diagonal corners.
3. Drag between keys on the pad to check that the interpolation looks natural.
## Generated model
The generated head produces nine poses from combinations of the ends and midpoints of Angle X / Y, with extra correction on the diagonals; the strength can be adjusted in **Rig Values** of the model presets.
*> Diagonal shapes are not synthesized automatically and must be made one by one (Cubism's [[synthesize-corners]] is not yet available).

@@ copy-and-blend-shape | Edit Form
@@ paste-form-special | Paste Form Special
@@ confirm-target-for-pasting-multiple-forms | Where to apply form pasting
@@ synthesize-corners | Automatic Generation of Four Corner Forms
@@ face-auto-edit | Auto generation of facial motion
When PSD2Live imports a PSD it automatically generates a full set of facial motion: head turns, blinking, eyeballs, brows, mouth form and lip sync, without first making the up, down, left and right shapes by hand.
## What is generated
- Head X / Y: nine poses from combinations of the ends and midpoints, based on the head tilt in the original art; the diagonals get extra correction.
- The face uses a lattice and region-based reshaping; the eyes and brows, pupils, mouth and ears have their own compensation or masks.
- Mouth: open/close and mouth form are generated from the mouth settings, with optional upper and lower lip outlines.
- Front and back hair are separated from strong face deformation and output their own sway parameters.
## Adjusting
1. Open the **Rigging & Facial** group of the **Model presets** panel: the **Facial Feature Displacement Deformer** toggle, **Head range** and **Body range**.
2. Fine-tune the head turn, eyes, brows, mouth, nose and ears, and hair in the **Rig Values** group; less common items are under **Advanced**.
3. After a change the model regenerates, and existing manual edits are kept by merging.
!> This is the output of preset algorithms, not a 3D reconstruction. The layering, wide hair strands, extreme angles and overlapping regions may all need manual reshaping.
## Differences from Cubism
- Cubism generates the rest of the facial motion automatically from up, down, left and right shapes you have already made; PSD2Live generates all facial motion directly from the PSD layers and adjusts the strength with numbers.
- Generated facial keyforms can be further edited by hand in Deform mode.
Related: [[model-presets]] · [[standard-parameter-list]]

@@ auto-generation-of-sway-motion | Auto Generation of Sway Motion
Swing generation corresponds to Cubism's "Auto Generation of Sway Motion": select a warp deformer (or a mesh), and swaying keyforms are generated automatically on parameter values -1 / 0 / 1, together with a physics pendulum. The settings are saved with the project and can be reopened and adjusted at any time.
## Opening
- In Object mode, right-click the selected mesh or warp deformer on the canvas → **Generate Swing...**; an already generated one shows **Edit Swing...**.
- The same menu is in the Hierarchy context menu.
- Physics panel **+ New** → **Swing for the selection…**.
When a mesh is selected, a fitting warp deformer is wrapped around it automatically. A rotation deformer cannot be a target.
## Editing
1. Once opened, the canvas enters swing editing and a floating swing panel appears at the bottom left.
2. Drag handles on the canvas: the tip dot sets amplitude and upturn, the diamond on the center line sets softness, the two tip corners set widen / narrow and parallel, the hollow circle rotates the whole swing rectangle, and the hollow square changes the pivot edge.
3. In the panel choose the **Motion** direction (**Left / Right** / **Up / Down**, both can be checked), **Segments** (1–3), **Estimate** (**Hair** / **Accessory** / **Cloth**) and whether to generate a physics pendulum.
4. **Preview Sway** shows the effect; **Generate** / **Apply** or Enter writes it, **Cancel** or Esc discards it.
## Generator and baking
- Keyforms on the swing axis are owned by the swing and recalculated from the target's current form on every rebuild; manual changes on these keyforms are recorded as generated overrides (see [[generated-overrides]]).
- **Bake to Keys** writes the current forms as ordinary keyforms and keeps only the physics pendulum; **Delete** removes it together with the generated axis and the automatically created parameter.
## Differences from Cubism
- Cubism generates sway with a warp deformer as the target; PSD2Live also supports mesh targets (wrapped in a deformer automatically), both left-right and up-down sway, segments, a parallel bottom (several hair strands swaying together) and rotating the swing rectangle.
- A physics pendulum is generated at the same time and exported to physics3.json and cmo3.
- If the parameter does not exist it is created automatically, named `ParamSwing<id>`.

@@ edit-vertices | Edit Vertices in Canvas Coordinates
Canvas editing in PSD2Live always works on where points appear on the canvas: whatever deformer an object is attached to, dragging, the transform box and the brushes act on the points you see on the canvas, and the program converts to the parent's local coordinates automatically. Use **Precise Transform** when you need exact numbers.
## Precise Transform
1. Select the points to move in Deform mode.
2. Open **Precise Transform** in the **Tool Details** panel.
3. Enter the offset (canvas pixels), scale or rotation angle and click **Apply**.
Rotation and scale are centered on the transform box anchor; when the target is a single rotation deformer, they are centered on its origin.
## Transform box
While dragging the transform box, the rotation angle, scale or moved canvas pixels are shown next to the pointer; drag the anchor to change the center, which snaps near the center, corners or edge midpoints.
## Differences from Cubism
- Cubism offers editing vertices in canvas coordinates as a separate feature; PSD2Live edits in canvas coordinates by default, with no separate switch.
- Numeric input only moves, scales and rotates the selected points as a whole; there is no per-vertex coordinate input.
{tutorial=TOOL_DETAILS}

@@ restore-deformer-shape | Restore deformer shape
@@ glue | Glue
Glue sticks the vertices of two meshes together so the two parts move together at the seam without tearing. A glue point is one point shared by both meshes, shown as a diamond on the canvas; when selected or dragged, the vertices on both sides always move together.
## Creating
1. Select two meshes in Object mode and switch to Edit mode ({key:MODE_EDIT}).
2. Choose the **Glue** tool ({key:TOOL_GLUE}), which has three sub-tools: **Glue brush**, **Weight brush** and **Remerge** (`1`–`3`).
3. Paint with the glue brush over the overlap; or press {key:FINISH_PATH} / **Create glue** on the mode bar to glue a whole stretch along the two outlines.
## Operations
| Operation | Effect |
|---|---|
| Glue brush | Adds or slides a vertex on the other mesh at the overlap (its UV moves with it); changes only the mesh, not the image |
| Alt + drag | Unglue |
| Weight brush | Shows and paints the weights of the two sides in blue (A) / orange (B) |
| Remerge | Removes the existing glue on brushed vertices, then glues again by the snap tolerance |
| {key:GENERATE} | Glues the selected vertices on either mesh (this does not open export here) |
| Right-click **Remerge the whole seam** | Removes all glue between the two meshes and glues again along the outline |
!> Gluing happens on the rest form (all parameters at their defaults). When parameters are not at their defaults, the glue brush, Remerge and Create glue first move the parameters smoothly back to their defaults and then glue.
## Differences from Cubism
- PSD2Live's glue brush adds or slides vertices on the other mesh so the vertices on both sides coincide; the two meshes do not need aligned vertices beforehand.
- Limbs split by the skeleton and depth splits generate glue automatically.
{tutorial=EDIT_MODE}

@@ deformpath | Deform Paths
A deform path (experimental) is an editing aid attached to a mesh: draw a curve along a part, and dragging the curve's control points makes the mesh deform along it, with the result written as the mesh's keyforms. It is not a third kind of deformer.
## Creating and editing
1. Select an ArtMesh and choose **Deform path** in the Hierarchy context menu, or use {key:TOOL_CREATE_DEFORM_PATH}.
2. Click on the ArtMesh to place at least two control points, press {key:FINISH_PATH} to confirm, {key:CANCEL} to cancel.
3. In Edit mode adjust the path's bind position, influence width, hardness, closing and corners; the mesh does not move at this stage.
4. Set the target pose in the Parameters panel, switch to Deform mode and drag the control points; the shape is written to the ArtMesh's keyforms.
## Edit levels
Paths come in two groups, L2 and L3, each stored separately; choose one in the placement panel or in **Path edit level** in **Tool Details**, and change an existing path in **Edit Level** in the Inspector. The canvas shows only paths of the current group.
## Export
| File | Content |
|---|---|
| `.cmo3` | Path controller structure and mesh keyforms |
| `.moc3` | Baked mesh keyforms; the runtime does not run the path algorithm |
!> A mesh can have several paths, but one path cannot control several meshes. The drag result has not yet been fully verified to match Cubism Editor; check width, hardness and large-angle deformation.
## Differences from Cubism
- PSD2Live's paths act on a single mesh and are an editing aid on that mesh; only the baked keyforms remain in `.moc3`.
- The path edit levels L2 / L3 are unrelated to a warp deformer's Vertices / Bezier levels.

@@ skinning | Skinning
Skinning lets a mesh that spans a joint bend smoothly with several bones. Cubism's skinning converts the mesh into a chain of rotation deformers; PSD2Live does the same with its own skeleton: the mesh is bound to bones, weights are computed automatically, and at export it is baked into Cubism deformers and keyforms.
## Binding and weights
1. In Skeleton mode choose **Edit Bones** ({key:TOOL_SKELETON_EDIT}) and use the **Bind** sub-tool to bind meshes to bones; meshes of one limb only need to be bound to one of its bones.
2. Weights are computed automatically over the whole limb: each vertex follows at most the two bones on either side of a joint, blending smoothly within the **Joint band**.
3. To correct them, use the **Weights** sub-tool: select a bound mesh and a bone in the same limb, and paint with add, subtract, replace or smooth.
4. In **Tool Details** you can clean up, restore automatic weights, or copy / mirror weights to another mesh.
5. **Show skin weights** in the display rail colors vertices by bone color.
## Baked result
Vertices within the joint band rotate around the joint blended by angle, so the limb keeps its width as it bends; at export this is written as rotation deformers plus corrective mesh keyforms.
## Differences from Cubism
- Cubism's skinning generates a chain of rotation deformers directly on the mesh, which you then edit in Cubism; in PSD2Live the weights belong to the skeleton and are rebaked after the bones change.
- Each vertex is affected by at most two adjacent bones.
See [[skeleton]] for details.
{tutorial=SKELETON}

@@ skeleton | Skeleton
The skeleton is PSD2Live's quick limb rigging tool: a skeleton drives the body, limbs, tails and wings, and at export it is baked into structures Cubism can run directly—a torso bend deformer, rotation deformers for the limbs, and corrective keyforms for meshes that span joints. The exported model does not depend on this program.
## Skeleton mode
Skeleton work happens in the **Skeleton** tab (next to the Hierarchy) and in the canvas's Skeleton mode ({key:MODE_SKELETON}):
| Tool | Shortcut | Sub-tools |
|---|---|---|
| Pose Bones | {key:TOOL_SKELETON_POSE} | Auto / FK / IK |
| Edit Bones | {key:TOOL_SKELETON_EDIT} | Edit / New Bone / Extrude / Bind / Weights |
## Creating a skeleton
1. Enter Skeleton mode; if the project has no skeleton yet, click **Create skeleton** in the middle of the canvas or in the **Skeleton** tab.
2. The program infers and binds bones from layer tags and sides: arms bind to handwear, legs to legwear, feet to footwear, and tails and wings to their own layers.
3. Use **Edit** to drag joint ends into place; **New Bone** and **Extrude** add custom bones; **Bind** binds meshes in bulk.
4. In **Tool Details** you can copy, mirror, subdivide and merge bones; with a bone selected you can set the **Joint band** and the minimum / maximum angle.
5. Click **Done** to save it as one history step; switching to Pose Bones, changing mode or selecting another layer also saves, and only **Cancel** discards the changes.
## Posing
- Dragging a bone's body is FK and rotates only that bone; dragging its end or holding Shift while dragging is IK.
- **Tool Details** sets the IK chain length, iterations and bend direction, and can pin the ends of selected bones.
- Posing only changes parameter values and creates no history; **Reset Pose** zeroes it. Named poses can be saved and inserted into animations.
## Generated structures and parameters
- Torso bend `DeformSkelTorso`, and rotation deformers `DeformSkel_<bone ID>` for limbs, tails and wings.
- Skeleton parameters such as `ParamArmLA`, `ParamLegLA` and `ParamTail1..n`, in degrees.
- Pose parameters: Crouch, Weight Shift, Tail Swing, Wing Flap, Knees In and Hop.
- Skeleton motions appear in the Animation panel; see [[builtin-motions]].
- **Parameter sampling** sets the tolerance, minimum angle step and the keyform limit per mesh, which control export size.
!> Limbs whose left and right sides are drawn on the same layer are not bound; split them first with **Model → Split Layers by Mesh…**. After the skeleton is baked, Cubism Editor keeps no skeleton semantics; keep the `.psd2live` project if you want to change the skeleton later.
!> Skeleton parameters for limbs that can be added together are written as blend shape parameters when the export target is Cubism 5.0 or later; for targets below 5.0 everything is baked into ordinary keyforms, and poses on rotation deformers are dropped.
## Relation to Cubism
Cubism Editor has no skeleton object; limbs are usually built by hand from rotation deformer chains and skinning. PSD2Live's skeleton is a tool for generating those structures: you place bones, bind and adjust weights on the skeleton, and the same deformers and keyforms come out automatically at export. Related: [[skinning]] · [[making-and-rotation-of-rotationdeformer]]
{tutorial=SKELETON}

@@ generated-overrides | Generated Overrides
Keyforms from generators such as swing and simulation are recalculated on every rebuild. Your changes on these keyforms are not overwritten; they are recorded as "generated overrides" and merged with the generated result after all generators run.
## Which edits are overrides
- Keyforms generated by a swing on its target warp deformer (when the swing parameter is not at its default).
- Mesh keyforms written back as keyform axes by a baked simulation.
- Keyforms written by skeleton skinning on meshes you reparented yourself.
Edits where the generated parameter is at its default are still ordinary edits and change the rest shape.
## Merge rules
| Case | Result |
|---|---|
| Points you did not change | Follow the new generated result |
| Points the generator did not change | Keep your values |
| Both changed | Keep your values and report a conflict |
## Viewing issues
When there are issues, the Inspector toolbar shows an "n override issue(s)" badge; the selected object's issues are listed under **Generated overrides** at the top of the Inspector, and clicking the badge lists all issues.
- Conflict: some points were changed by both the generator and your edit; your edit was kept.
- Not applied: the generated keyform no longer exists, the generated shape's point count changed, or the mesh was replaced by a split.
!> Copying to or from generated keyforms, and adding or deleting keys on a generator's axis, cannot be recorded as overrides and are rejected. To edit a swing fully by hand, first use **Bake to Keys** in the swing panel.
## Relation to Cubism
Cubism's auto generation is one-off; afterwards the result is ordinary keyforms. PSD2Live's generators run again as settings change, and overrides keep your manual changes across regeneration. Related: [[auto-generation-of-sway-motion]] · [[simulation]] · [[update-generated-rig]]

@@ multi-key | Multiple Keys Editing Function
@@ motion-mirroring | Motion Mirroring
Mirror a keyform made on one side to the other side, for example the Angle X 30 shape to -30, or the left eye's shape to the right eye. The result is written as ordinary keyforms and can be undone.
## How to use
1. Select the object in Deform mode and set the parameters to the key to mirror.
2. Choose an operation in **Mirror keyform** in the canvas context menu, or press {key:MIRROR_KEYFORM} to mirror to the opposite key.
| Operation | Effect |
|---|---|
| Mirror to opposite key | Mirrors the current key to the opposite key and switches to that key to show the result |
| Mirror from opposite key | Overwrites the current key with the opposite key's shape |
| Mirror to the opposite-side object | Writes to the matching object on the other side; L/R parameters are swapped for the other side and left-right parameters are negated |
| Flip in place | Flips the current key around the object's own center |
| Mirror all keys to the other side / to the opposite-side object | Processes all keys at once |
## Rules
- The negated left-right parameters are those whose range is symmetric around the default and that are the horizontal axis of a 2D link or whose ID ends in X or Z (AngleX, BodyAngleX, EyeBallX and so on).
- Opposite-side objects are paired first by layer semantics, then by L/R, Left/Right or 左/右 in the name.
- Mirroring only handles the object's own deformation; the parent's keyforms need to be mirrored separately. With several layers selected, each is mirrored separately and committed in one history step.
## Differences from Cubism
- PSD2Live works from the canvas context menu, and the opposite key and opposite-side object are determined automatically from parameters and layer semantics, with no correspondence to set up separately.

@@ temporary-deform-tool | Temporary deform tool
@@ deform-brush | Deform brushes
The deform brush pushes shapes like a brush in Deform mode, suited to places that need broad, soft changes such as hair and outlines. It only changes geometry, not textures.
## How to use
1. Select the object, switch to Deform mode and choose **Deform Brush** ({key:TOOL_BRUSH}); there are also **Smooth Brush** and **Inflate Brush**.
2. Drag on the canvas to push points; with no selection it acts on all of the object's points, with a selection only on the selected points.
3. By default drag with {key:BRUSH_ADJUST_DRAG} to adjust radius or hardness, {key:BRUSH_RADIUS_DOWN} / {key:BRUSH_RADIUS_UP} to adjust the radius, and {key:CANCEL} to cancel the current stroke.
## Settings
| Setting | Effect |
|---|---|
| Tip | Circle, line or rectangle ({key:BRUSH_SHAPE_CYCLE} cycles) |
| Radius / Hardness / Strength | Area of effect, edge softness and the force of each push |
| Falloff | Smooth, sphere, root, inverse square, sharp, linear, constant, random |
| Connected Only | Affects only points connected to the mesh under the pointer |
The tip is chosen below the brush in the left toolbar; the other settings are in the tool options bar at the bottom left and in the canvas context menu. While hovering, red circles mark the points a press would push; the bigger the circle, the stronger the effect.
*> **Connected Only** is useful for brushing close-together parts separately, such as neighbouring hair strands or the upper and lower lips.
{tutorial=DEFORM_MODE}

@@ blend-shape | Blend Shape
A blend shape parameter adds a shape difference on top of an object's existing keyforms instead of multiplying with other parameters' keys. When several parameters act independently, blend shapes can greatly reduce the number of keyforms. PSD2Live supports blend shape parameters, but the editing workflow in the product is still being completed.
## What you can already do
1. When creating a parameter, choose **Blend shape** in **Parameter Properties** (the type can only be chosen at creation; the default range is 0..1).
2. Blend shape keys appear as square dots in the Parameters panel; 0 is the neutral base and cannot be moved or deleted.
3. Move the parameter to another key and change the mesh or deformer in Deform mode to record the additive shape.
4. The skeleton and simulation generate blend shape parameters automatically (skeleton parameters, simulation mode parameters); see [[skeleton]], [[simulation]].
!> Not yet complete: Cubism's weight limit settings ([[limit-settings-for-blend-shape-weights]]), limit curves and the full creation workflow are not available yet.
## Export
Blend shapes on meshes and warp deformers need an export target of Cubism 4.2 or later; blend shapes on rotation deformers and parts need 5.0 or later. When the target version does not support them, export downgrades and explains it in the report.
## Differences from Cubism
- In Cubism you can switch an existing parameter to a blend shape and set weight limits; PSD2Live chooses the type only at creation and has no weight limits yet.

@@ limit-settings-for-blend-shape-weights | Limit settings for blend shape weights
@@ multiply-color-screen-color | Multiply Color/Screen Color
Multiply color darkens or tints an object and screen color lightens it, useful for blush, shadows or highlights without drawing extra layers.
## How to set it
1. Select an ArtMesh or deformer.
2. In **Multiply Color** and **Screen Color** in the Inspector, click the swatch to pick a color or enter a hex code.
3. The reset button at the end of the row restores the default (white for multiply, black for screen).
!> The Inspector sets a fixed color for the object; colors that change with parameters must be written as color channel keyforms through MCP `keyform_apply`.
*> When the export target version does not support multiply / screen color, export downgrades and explains it in the report.
## Differences from Cubism
- Cubism: multiply and screen colors belong to keyforms, and a different color can be set on each parameter key.
- PSD2Live: the Inspector sets only one fixed color; colors that change with parameters can only be written through MCP.

@@ apply-3d-rotation-expression | Application of 3D Rotation Expression
@@ apply-3d-rotation-expression-settings | Setting items for applying 3D rotation expressions
@@ convenience | Convenient editing function
@@ csv-export-import | CSV export/import
@@ template | About model templates
@@ applying-the-model-template | How to Apply Model Templates
@@ creating-exporting-the-model-template | Creating/Exporting Model Templates
@@ animation | Animation
@@ animation-preparation | Preparation for Animation Creation
Animation in PSD2Live is done inside the editor; you do not open a separate Animator, set up a scene and import the model as in Cubism. The model's motions are saved directly in the project and exported as `motion3.json`, or as `.can3` alongside CMO3.
## Where motions come from
- **Built-in motions**: when the model is generated, idle, blink, nod, shake and other motions are generated from the model presets, plus skeleton motions when there is a skeleton; see [[builtin-motions]].
- **Custom motions**: click **New Motion** in the Animation panel, choose **Blank Motion** or **Copy From Preset**, then key each parameter in the Animation Editor.
## Preparing the workspace
1. Click **+** at the right of the workspace tab bar and choose the **Animation** preset: the motion list is left of the preview canvas, the Animation Editor below, and the Parameters panel on the right.
2. In the Animation panel, click the edit button on a motion row, or right-click and choose **Edit**; the motion opens in the Animation Editor.
3. Drag sliders in the Parameters panel to pose the model and record keyframes in the Animation Editor; see [[timeline-basic-operation-timelinepalette]].
{tutorial=ANIMATION}
## Differences from Cubism
- Cubism: create a scene in the Animator, import the model and place a model track on the timeline before making motion; one scene corresponds to one motion file.
- PSD2Live: there are no scenes, model tracks or group tracks; each motion is a clip in the project that directly drives the current model's parameters, and you cannot add backgrounds, audio or multiple models.
- Motions are saved with the project and take part in undo/redo; no separate animation file needs saving.
*> When exporting CMO3, motions are saved as a `.can3` with the same name, which can be opened together with the cmo3 in Cubism Editor's Animator for further editing.
@@ builtin-motions | Built-in Motions
Built-in motions are motions PSD2Live generates for the model automatically, giving you idle, blink and other common motions without keying by hand; they are exported with the model.
## Motion list
| Group | Motions | Notes |
|---|---|---|
| Basic motions | Idle Loop, Blink, Nod, Shake | Playable on any model; the idle loop loops |
| Skeleton motions | Tail Swing, Crouch, Weight Shift, Shy, Wave, Head Tilt, Cheer, Leg Kick, Happy Sway, Cute Idle | Need a skeleton; motions the current skeleton cannot play are not listed; Cute Idle loops |
The **Basic motions** and **Skeleton motions** checkboxes in the **Motions** group of the model presets decide whether each whole group is generated; see [[model-presets]].
## Adjusting and toggling
1. In the **Built-in Motions** folder of the Animation panel, check or uncheck the box at the left of each row to decide whether that motion is exported.
2. Click a motion row to expand its controls: for example the idle loop has **Sway**, **Breath**, **Head**, **Speed** and a **Blink** toggle; Blink has **Closure**, **Speed** and **Double blink**; Nod and Shake have **Amplitude**, **Speed** and **Count**.
3. The play button at the right of the row plays the motion in the preview canvas.
## Manual changes
After you open a built-in motion in the Animation Editor and change its keyframes, the row shows **Modified** and the controls become unavailable (Keys edited by hand: reset to use these settings). The context menu offers:
- **Duplicate as Custom Motion**: creates a new custom motion from the current content; the built-in motion stays unchanged.
- **Restore Default**: discards the manual changes and returns to the result generated from the controls.
- **Delete**: it is no longer exported; it can be brought back from **Restore deleted presets** in the **New Motion** menu.
!> Physics-driven parameters are not written into the idle loop, to avoid conflicts with the physics output.
## Relation to Cubism
Cubism has no automatically generated motions; they are made by hand in the Animator. Built-in motions are like ready-made motions, and after export they are written as `motion3.json` just like hand-made ones; looping motions go into the `Idle` group; see [[notes-for-sdk]].
{tutorial=ANIMATION}
@@ loading-model-and-placement | Model loading and placement
@@ eye-blink-settings | Eye Blinking Setting
Cubism sets up automatic blinking with the blink effect in the Animator or the blink settings in the Viewer. PSD2Live achieves the same with the built-in Blink motion and the `EyeBlink` group written at export.
## How to set it
1. Make sure **Basic motions** is checked in the **Motions** group of the model presets.
2. Check **Blink** in the Animation panel; click the row to expand it and adjust **Closure** and **Speed**, and check **Double blink** to blink twice in a row.
3. The idle loop also blinks by itself; turn off **Blink** in the idle loop's controls if needed.
## Export result
- When both basic motions and the Blink motion are enabled and the export is not mesh-only, the `EyeBlink` group is written to `Groups` in `model3.json`, containing `ParamEyeLOpen` and `ParamEyeROpen`, which the SDK's automatic blinking uses.
- The Blink motion itself is also exported as a `motion3.json`.
## Differences from Cubism
- Cubism: set the blink interval with the blink effect on the model track in the Animator, or set the blink parameters in the Viewer and then output model3.json.
- PSD2Live: there is no blink effect track or interval setting; blinking comes from the built-in motion, and the `EyeBlink` group is written automatically at export.
!> When exporting CMO3, the blink effect curve in a motion has no matching track in the Animator and is not written into `.can3`.
@@ generating-scene-from-audio-file | Creating Scenes with Background Music and Audio
@@ placement-background-and-grouptrack | Background Placement and Group Tracks
@@ management-of-scenes | Scene management and settings
@@ change-pose | Create Motion with Pose Switching
@@ timeline | Manipulating the timeline
@@ timeline-basic-operation-timelinepalette | Displaying and operating the timeline palette
Cubism's timeline palette corresponds to PSD2Live's **Animation Editor**. It shows the parameter tracks of the open motion, where you move the playhead, set keyframes and adjust interpolation.
## Opening
Open the **Animation Editor** from the **Windows** menu, or use the **Animation** workspace preset. Edit a motion in the Animation panel, or choose one in the **Choose a motion…** dropdown on the editor toolbar.
## Toolbar
| Item | Description |
|---|---|
| New Motion | Creates a blank motion and opens it |
| Back to start / Play | Moves the playhead to 0; plays or pauses |
| Playhead time / Duration | Current time (seconds) and motion length (0.1–600 seconds) |
| FPS | Motion frame rate (1–120), which sets the step for frame-by-frame moves and snapping |
| Loop | Whether it loops on export |
| Snap to Frames | Aligns keyframes and the playhead to whole frames while dragging |
| Auto Key | Sets keyframes at the playhead automatically when parameters change; unrecorded parameters are added to the tracks automatically |
| Track | Search for and add parameters to animate |
| Key Pose | Records the current values of all tracks as keyframes at the playhead |
| Dopesheet / Curves | Switches between the [[timeline-basic-operation-dopesheet]] and [[grapheditor]] views |
## Basic operation
1. Add parameters with **Track**, one track per parameter; a track row shows the value at the playhead and has **Key at playhead** and **Remove track** buttons.
2. Click or drag on the ruler to move the playhead, and change parameter values in the Parameters panel.
3. Press {key:MOTION_KEY_POSE} to key the pose, or turn on **Auto Key** and adjust parameters directly.
4. Press {key:MOTION_PLAY_PAUSE} to play and check.
## Shortcuts (when the timeline has focus)
| Action | Key |
|---|---|
| Play / pause | {key:MOTION_PLAY_PAUSE} |
| Key the current pose | {key:MOTION_KEY_POSE} |
| Toggle auto key | {key:MOTION_TOGGLE_AUTO_KEY} |
| Previous / next frame | {key:MOTION_PREV_FRAME} / {key:MOTION_NEXT_FRAME} |
| Go to start / end | {key:MOTION_GO_START} / {key:MOTION_GO_END} |
| Fit the timeline | {key:MOTION_FIT_VIEW} |
| Zoom / pan the timeline | {kbd:Ctrl+Wheel} / {kbd:Shift+Wheel} |
## Differences from Cubism
- Cubism: the timeline palette has a multi-level structure of model tracks, group tracks, effects and properties, with parameter tracks shown under the model track.
- PSD2Live: there are only parameter tracks, one set of tracks per motion; there are no bookmarks, Shy, Freeze, markers and similar features.
- A motion's duration, frame rate and loop are set directly on the toolbar, with no scene settings needed.
{tutorial=ANIMATION}
@@ timeline-basic-operation-dopesheet | Basic Dope Sheet Operation
The dope sheet shows each track's keyframes as a row of dots, for viewing and adjusting keyframe timing. Click **Dopesheet** on the Animation Editor toolbar to switch to this view.
## Operations
| Action | How |
|---|---|
| Add a keyframe | Double-click an empty spot on a track |
| Select | Click a keyframe; {kbd:Shift} adds, {kbd:Ctrl} toggles; drag on an empty area to box-select |
| Select all | {key:MOTION_SELECT_ALL} |
| Move | Drag the selected keyframes (aligned to frames when **Snap to Frames** is on) |
| Copy / paste | {key:MOTION_COPY_KEYS} / {key:MOTION_PASTE_KEYS} |
| Delete | {key:MOTION_DELETE_KEYS}, or **Delete Keys** at the bottom |
With keyframes selected, the bottom of the editor shows **n keys selected**, where you can enter **Time** and **Value** directly and set the **Interpolation** for all of them (Linear, Ease (Bezier), Stepped, Inverse Stepped).
*> A continuous drag or input is recorded as one history step and can be undone.
@@ parameter-bookmark | Parameter Bookmarks
@@ timeline-basic-operation-shy | Shy function
@@ freeze | Freeze function
@@ timeline-basic-operation-repeat | Animation of parameter repeat
@@ grapheditor | Graph Editor
The curves view shows parameter tracks as value–time curves, for adjusting keyframe values and easing. Click **Curves** on the Animation Editor toolbar to switch to it.
## Operations
1. Click a track row on the left to show only that curve; click **Show all** above the track list to show all curves again.
2. Dragging a keyframe changes its time and value together; when only one curve is shown, double-clicking an empty spot adds a keyframe at that time and value.
3. Keyframes in Bezier segments have handles on both sides; drag them to adjust easing. **Reset Handles** at the bottom restores the default handles.
4. {kbd:Ctrl+Wheel} zooms the time axis, {kbd:Ctrl+Shift+Wheel} zooms the value axis, and {kbd:Wheel} pans the values up and down.
## Interpolation
| Interpolation | Description |
|---|---|
| Linear | Changes at a constant rate between two keys |
| Ease (Bezier) | Default; handles control acceleration and deceleration |
| Stepped | Holds the previous key's value and jumps at the next key |
| Inverse Stepped | Jumps to the next key's value immediately and holds it |
Handle times are restricted to their own segment, matching `AreBeziersRestricted` in `motion3.json`, so the curve shape is unchanged after export.
## Differences from Cubism
- Cubism: the Graph Editor is a separate view that can show curves of several properties at once, with more handle and zoom tools.
- PSD2Live: the curves view shares the same area, selection and shortcuts with the dope sheet, and edits only parameter curves.
@@ marker | Marker Function
@@ locus-of-tracks | Track Trajectory
@@ template-making-and-management | Animation Templates
@@ about-fade | About Fading
A fade is the transition time between a motion and other motions at its start and end. When the runtime switches motions, the new motion takes effect gradually over the fade-in time and the old one fades away over the fade-out time, avoiding sudden pose changes.
## Fades in PSD2Live
- Custom motions have **Fade In (s)** and **Fade Out (s)**, both 1 second by default, exported as `FadeInTime` / `FadeOutTime` in `motion3.json`.
- Built-in motions that have not been changed by hand write no fade times, and the runtime uses its defaults.
- When exporting CMO3, fades are written into the `.can3` scene.
See [[fade-setting]] for how to set them.
*> Fades only matter when the runtime switches motions; you cannot see a difference when the editor preview plays one motion on its own.
@@ fade-setting | Fade value setting
Set the fade times of custom motions in the Animation panel.
1. In the **Custom Motions** folder of the Animation panel, click the row of the motion to set to expand its settings.
2. Enter a value of 0–5 seconds in **Fade In (s)** and **Fade Out (s)**.
3. After export they are written into that motion's `motion3.json`.
*> When a built-in motion needs fades, first make a copy with **Duplicate as Custom Motion** and set them there.
## Differences from Cubism
- Cubism: set fade-in/out for the whole motion in the timeline palette, and fades can also be set per parameter curve.
- PSD2Live: fade-in/out can only be set for the whole motion in the Animation panel; there are no per-curve fades.
@@ lightweight-playback | Lightweight Playback Function
@@ notes-for-sdk | Precautions When Embedding the SDK
Motions exported to the SDK are `motion3.json` files. PSD2Live writes them by the following rules; call them accordingly when embedding in an application.
## Files and groups
| Motion | Motion group | File name |
|---|---|---|
| Idle loop and other looping motions (including looping custom motions) | `Idle` | `<model name>.<motion>.motion3.json` |
| Other built-in motions | Same name as the motion (such as `Blink`, `Nod`) | As above, such as `<model name>.blink.motion3.json` |
| Non-looping custom motions | Motion name | As above |
All motions are written to `Motions` in `model3.json`, and `Groups` gets `EyeBlink` (when blinking is enabled) and `LipSync` (`ParamMouthOpenY`).
## Notes
- Only parameters that exist in the model are written; tracks in a motion that point to deleted parameters are not exported.
- Bezier handles are restricted to their segment (`AreBeziersRestricted: true`), so every SDK version plays them correctly.
- Physics-driven parameters are not written into the idle loop, so motions and physics do not fight over the same parameter.
- Motions unchecked in the Animation panel are not exported; a mesh-only export writes no motions.
- Motions contain no user data events (Event); see [[userdata]].
## Differences from Cubism
- Cubism: motion3.json files are output scene by scene in the Animator's export settings, with groups and file names decided by the export settings or the Viewer.
- PSD2Live: all motions are written at once when exporting the Live2D model, with groups and file names decided automatically by the table above.
@@ parameter-controller | Parameter Controller
@@ about-the-parameter-controller | About the parameter controller
@@ parameter-controller-target-setting | Controller target settings
@@ form-animation | Form animation (FA)
@@ about-form-animation | About Form Animation (FA)
@@ create-form-animation | Create a form animation (FA)
@@ form-animation-time-remapping | Adjusting the Display Timing of Form Animations (FA)
@@ adjustment-mode | Adjustment Mode
@@ form-animation-animation-preview | Animation preview
@@ serial-number-image-track | About Image Sequence Track
@@ limited-animation | Frame Step
@@ physics-operation-bake | Bake Animation from Physics
@@ motion-sync-bake | Motion-sync Animation Bake
@@ gather-up-files | Gather files
@@ runtime-model-track | Runtime Model Track
@@ loop-editing-support | Loop editing support
@@ export | Export
@@ exporting-images | Image and video export
Output the model's motions or poses as image sequences, animated images or video. In PSD2Live you choose a format in the **Images and video** group of **File → Export as**, and each format opens its own export window.
## Formats
| Menu item | Output | Notes |
|---|---|---|
| PNG sequence… | Numbered PNG frames | With transparency |
| Sprite sheet… | PNG laid out in a grid + JSON | Refused above 16384 px |
| GIF… | Animated GIF | 256 colors, 1-bit transparency |
| APNG… / Animated WebP… | Animated PNG / WebP | WebP is lossy |
| MP4 video… | H.264 | No transparency; composited onto the background color (white by default) |
| WebM video (with alpha)… | VP9 | Transparency needs libvpx to decode |
| QuickTime ProRes 4444… | MOV | With transparency |
Video formats, APNG and WebP are encoded with ffmpeg: the environment variable `PSD2LIVE_FFMPEG`, the ffmpeg bundled with the installer and ffmpeg on PATH are tried in turn, or you can enter a path in the window's **ffmpeg** field.
## Steps
1. Choose **File → Export as →** the format you need.
2. Set the **Motion** (the first motion by default, or a still pose when there are no motions), **FPS**, **Size** (long edge in pixels, 1024 by default), **Background** and **Physics**; GIF also has **Dither**.
3. Choose the **Output folder** and click **Export**. Files are written to a folder named "<project name>-<format>" in the output folder.
4. When done, the "Export complete" window appears and lists what this format cannot keep.
## Differences from Cubism
- Cubism: output images or video per scene in the Animator, with range, size and format settings.
- PSD2Live: output directly from one motion, drawn by the editor's own software renderer with physics advanced between frames; it does not depend on the Cubism SDK.
*> For a single still image, export a layered PSD with **PSD at a pose…**, or export a texture atlas with **Save page as PNG…** from a page's context menu in the Texture workspace; see [[other-formats]].
@@ export-moc3-motion3-files | Data for Embedded Use
Data for embedded use is the set of files used by runtimes such as the Cubism SDK: `.moc3`, `model3.json`, `physics3.json`, `motion3.json`, `cdi3.json` and textures. PSD2Live writes them all at once from the **Export Live2D model** window, and can write a Cubism Editor project at the same time.
## Steps
1. Choose **File → Export Live2D model…** ({key:GENERATE}).
2. Under **Formats:** check **moc3**, **cmo3**, **can3** (motions written alongside cmo3) and **json** (project metadata report). With **Mesh Only** checked, only meshes are exported, without motions or physics.
3. Choose the runtime version in **SDK target** (from Cubism 3.0 to the latest). When the chosen version does not support features the model uses, the window lists the features that will be downgraded.
4. Adjust the **MOC3 bake options** as needed: Export hidden parts, Export hidden art meshes, Export guide image parts, Include physics3.json, Include cdi3.json, and **Pixels / unit** (automatic by default, from the canvas width).
5. Choose the **Output folder** and click **Generate & Export**.
## Output files
| File | Content |
|---|---|
| `<name>.moc3` | Model data |
| `<name>.model3.json` | Model settings; see [[export-model3-json]] |
| `<name>.physics3.json` | Physics, with `Fps` written from the project's physics frame rate |
| `<name>.<motion>.motion3.json` | One per motion; see [[notes-for-sdk]] |
| `<name>.cdi3.json` | Display names of parameters, parts and meshes |
| `<name>.<width>/texture_00.png` etc. | Texture atlas pages |
| `<name>.cmo3` / `<name>.can3` | Cubism Editor project and Animator project |
The window only shows a summary of the texture atlas page size and count; you can jump to the Texture workspace to adjust it; see [[texture-atlas-edit]].
## Differences from Cubism
- Cubism: the Modeler's "Export moc3" and the Animator's "Export motion3.json" are done separately, and the texture atlas is edited at export.
- PSD2Live: the model, physics, motions and model3.json are written at once from one window; the export is the currently committed model and is not regenerated from the PSD.
- The editor's Cubism SDK preview and the moc3 export are the same compilation, so what the preview shows is the export result.
!> The GUIDs in cmo3 differ on every export; a `.can3` only matches the `.cmo3` from the same export, and the two must be kept in the same folder.
@@ other-formats | Exporting Other Formats
Besides Live2D models, PSD2Live can export the same model to other runtimes, players or file formats. Every format starts from the same neutral data, downgrades in its own way, and lists after export what the format cannot keep (loss report).
## Export steps
1. Choose **File → Export as**, and pick a format in the "Runtimes and players", "Images and video" or "Layered PSD" group.
2. In the window that opens, adjust the format's own settings (when there are none it shows "This format has no settings."), choose the **Output folder** and click **Export**.
3. Files are written to a folder named "<project name>-<format>" in the output folder, with a `<name>.<format>.report.json` loss report beside it.
## Runtimes and players
| Menu item | Output | Main limits |
|---|---|---|
| VTube Studio… | All moc3 files + `.vtube.json` | Face tracking maps only standard parameters; one hotkey per motion |
| PSD2Live runtime rig… | `.p2lrt` | Editor-only data is not written; optional compression, name stripping and so on |
| Web player… | `index.html`, player script, WebAssembly runtime and `.p2lrt` | Must be accessed over http |
| Unity prefab… | `.p2lunity`, textures and installer script | Needs Unity 6.0 or later; parameters are baked into Blend Shapes, combinations are additive approximations; masks and physics are not written |
## Images and video, layered PSD
- For images and video see [[exporting-images]].
- **PSD at a pose…**: poses the model at a moment of a motion or at given parameter values, one layer per visible mesh; deformers and parameters are not kept.
- **Source PSD…**: exports the project's source layers, optionally at 1× or an upscale factor, and can include automatically split layers, convenient for touch-ups in painting software (also available with {key:REEXPORT_PSD}).
## Relation to Cubism
Cubism Editor exports only Live2D's own formats. To use a model in VTube Studio, on the web or in Unity, Cubism users usually export data for embedded use and then rely on each platform's Live2D SDK; PSD2Live writes files those platforms can read directly.
*> Spine, DragonBones and glTF are experimental targets that are not in the menu and can only be exported from the command line.
@@ userdata | User Data and Events
User data is an arbitrary string attached to an object for applications to read. PSD2Live supports entering user data on ArtMeshes.
## How to set it
1. Select an ArtMesh.
2. Expand **User Data** at the bottom of the **Inspector** and enter text.
3. Finishing the input records one history step, which can be undone.
## Export
- User data is saved with the project and written into the exported `.cmo3`, where it is visible in Cubism Editor.
- MOC3 export currently does not generate `userdata3.json`; the **Include userdata3.json** checkbox in the export window does not produce the file.
## Differences from Cubism
- Cubism: user data can be set on ArtMeshes and output as `userdata3.json`; in the Animator, Events (user data) can be placed in motions and written to `motion3.json`.
- PSD2Live: user data can only be set on ArtMeshes; Events cannot be placed in motions, and the exported `motion3.json` contains no user data.
@@ physics-playlist | Physics Settings and Playlists
@@ switch-viewer-setting | Physics Settings
Cubism edits physics after switching to the physics settings in the Viewer. PSD2Live has no separate Viewer; physics is edited in the **Physics** panel, which can be opened in any workspace, and there is also a dedicated Physics workspace.
## Opening the physics settings
1. Click **+** at the right of the workspace tab bar and choose the **Physics** preset: the preview canvas takes the left half, with the Parameters panel and Physics panel on the right.
2. Or open the **Physics** panel from the **Windows** menu and use it with any canvas.
3. The physics toggle and physics frame rate are on the toolbar at the left of the preview canvas: **Physics** / **Physics off**, and the **Physics FPS** (30 / 60 / 90 / 120 / Unlimited, 60 by default).
## Differences from Cubism
- Cubism: physics is set up in the Viewer, and the result is output as `physics3.json` and then combined with the model.
- PSD2Live: physics is part of the project, saved and undone with it, and written automatically into `physics3.json` and `.cmo3` at export; the physics frame rate is a project setting and is also written into the exported files.
- The pendulum canvas in the panel can pull the model directly to try it out, without switching modes.
{tutorial=PHYSICS}
@@ physics-operation | About Physics
Physics makes parameters swing with inertia following inputs such as the head and body, used for the sway of hair, accessories, clothes and so on. PSD2Live uses the same pendulum model as Cubism.
## How it works
- **Inputs**: head, body angle and other parameters push the pendulum root (Position X) or tilt the direction of gravity (Angle).
- **Pendulum**: a chain of pendulums hanging from the root, moving by length, sway, reaction speed and convergence speed.
- **Outputs**: one pendulum's swing angle multiplied by the scale is written to an output parameter.
Physics only writes parameters; the swaying shape comes from the output parameter's keyforms. If the output parameter has no end-point shapes, the parameter moves but the model does not.
## Where physics groups come from
Physics groups can come from presets (front hair, back hair, eye jelly), the skeleton, swings, simulation baking or manual creation; see [[physics-group-information]].
*> The physics calculation used by the panel and the preview matches the Cubism Native Framework's calculation item by item, so an exported `physics3.json` behaves in the SDK the same as in the editor.
@@ physical-operation-setting | How to Set Up Physics
After selecting a physics group in the **Physics** panel, below it come the pendulum canvas, the response curve, the pendulum settings, inputs, outputs and advanced settings.
## Pendulum canvas
| Action | Effect |
|---|---|
| Drag left and right on an empty spot | Pulls the model like dragging it in the Viewer; it returns when released |
| Drag the ring at a pendulum's end up and down | Adjusts that pendulum's length; click to select it |
| Drag an output label on the right next to a pendulum | Reads that pendulum's angle instead (Pendulum #) |
| Click an output label | Shows the range fan; drag the fan's edge to adjust the scale |
The response curve below the pendulums shows the reaction to a pull to the right for 1 second and then release, so you can compare overshoot and settling between settings directly.
## Settings
1. **Preset**: pick a preset and click **Load** to replace all pendulums; **Add…** saves the current pendulums as your own preset.
2. **Edit pendulum**: choose **All** or a number to decide which pendulum the sliders below adjust; pendulums can be added, deleted and moved up or down.
3. Sliders: **Total length / Length**, **Sway**, **Reaction**, **Settling**, with the same meanings as in Cubism Editor.
4. **Inputs**: add parameters with **Add**, choose the type **Position X** or **Angle**, and set the **Weight** and inversion.
5. **Outputs**: set the **Pendulum #**, **Scale** and inversion. **Max output** on the right shows the percentage of the parameter's end the output reaches, and **Fit scale to max** brings it to 100%.
6. **Advanced**: the pendulum settings table, output details and **Input normalization**.
## Differences from Cubism
- Cubism: values are entered in tables in the physics settings dialog and checked by dragging the model in the Viewer.
- PSD2Live: length, pendulum number and scale can be changed by dragging directly in the pendulum canvas, which also shows the response curve and the max output.
- Outputs only have the angle type: in the Cubism runtime, Position X outputs are always 0.
- Each drag or input is recorded as one history step on release and can be undone.
{tutorial=PHYSICS}
@@ physics-group-information | Edit Physics Group
The physics group list at the top of the **Physics** panel manages all of the model's physics groups, like the group list in Cubism's physics settings.
## Physics group list
- Groups are listed in calculation order with numbers; the checkbox enables or disables a group, and disabled groups are not exported.
- Each row shows the origin (Preset, Skeleton, Swing, Simulation, Custom and so on) and the state: **Edited**, **Invalid** (parameter missing, no inputs or outputs and so on), **Replaced** (its output is already driven by another group).
- Move up / down in the title bar changes the calculation order: when one group takes another group's output as input, the group being read must come first.
## Creating
Click **New**:
- **Blank pendulum**: inputs head and body sway by default, with no outputs yet.
- **Duplicate as new group**: copies the selected group with its output parameters cleared.
- **Import physics3.json…**: each group in the file becomes a custom group; groups with the same ID are replaced.
- **Swing for the selection…**: creates a swing for the mesh or warp deformer selected on the canvas; see [[auto-generation-of-sway-motion]].
## Changing generated groups
Generated groups can also be edited directly; after editing they are marked **Edited**, and later changes to their origin will not overwrite them; **Reset to generated** in the **More** menu returns to the generated version. Custom groups can be **Rename**d and **Delete**d.
## Differences from Cubism
- Cubism: every physics group must be created and set up by hand.
- PSD2Live: most physics groups are created automatically by generators, and they can follow changes in their origin while keeping your edits.
- A parameter can only be driven by one active group; conflicting groups are marked as replaced and not exported.
@@ simulation | Cloth and Hair Simulation
Simulation runs a real 2D cloth and hair simulation on meshes in the editor: vertices are held by pinned points and driven by gravity and inertia. The Cubism runtime does not do this kind of simulation, so a simulation must be **baked** into parameters, keyforms and Cubism pendulums before it can be exported.
## Basic flow
1. Select the skirt or hair meshes on the canvas, click **New** in the **Simulation** panel and choose **Cloth** or **Hair**. Several meshes in the same body simulate together.
2. Specify the pinned points: click **Paint** on the pin row under **Vertex groups** in the panel, or switch the canvas to Simulate mode ({key:MODE_SIMULATE}) and paint with the weight brush; you can also set glue connected to the waist as **Pin** under **Glue**.
3. Switch the panel's preview toggle to **Reference sim**, turn the head and sway the body in the preview, and adjust the material until it looks natural.
4. Click **Bake** on the body, or **Bake all** on the toolbar. Baking runs in the background and takes a few seconds; when done it is committed as one undo step.
5. Switch back to **Export**, and the preview plays the exported model's motion, to compare with the reference simulation.
## Vertex groups
| Kind | Effect |
|---|---|
| Pin | Vertices held by the rig; the weight is how firmly they are held |
| Stiffness | Scales stretch and bend stiffness per vertex |
| Shape | Scales the pull back toward the original art's shape per vertex |
| Mass | Scales mass per vertex |
| Damping | Scales damping per vertex |
Vertex groups exist only in this software and are not written to exported files.
## Material and baking
- The **Material** section offers named presets (Cotton, Silk, Chiffon, Wool, Denim, Leather, Elastic, Hair, Fine hair, Thick hair, Bangs), and then fine-tuning of mass, stretch, bend, damping, shape and other values.
- **Inputs** are the parameters that shake the body, by default head and body angles and so on; inputs with almost no effect on the body are dropped during baking.
- The **Bake** section sets the number of modes, upper and lower parameters, keys, exaggeration and so on; after baking it shows a visual check against the reference simulation (amplitude, lag, settle, jitter) and R².
- After baking, a pendulum group with the origin "Simulation" appears in the Physics panel, and `ParamSim…` parameters appear in the Parameters panel. When settings, meshes or weights change, the bake is shown as out of date, and export keeps using the old result until you bake again.
*> The **Physics & Simulation** group of the model presets can create and bake simulations for front hair, back hair and clothing in one step; see [[model-presets]].
## Relation to Cubism
Cubism has only pendulum physics; complex swaying of cloth and long hair is usually made by hand with several parameters and keyforms driven by several pendulum groups. PSD2Live's simulation automates this: it simulates in real time first, then fits the result into pendulums, parameters and keyforms that Cubism can play, so the exported model plays in any Cubism runtime. The simulation itself (the reference simulation) is not exported.
{tutorial=SIMULATION}
@@ about-playlists | About Playlist
@@ playlist-settings | How to set up a playlist
@@ external | External Application Integration feature
@@ external-application-integration | External Application Integration
Cubism Editor's external integration lets external applications read and operate the editor through a network interface. PSD2Live's counterpart is a local MCP service: AI hosts such as Claude Code and Codex, or scripts, can read and edit the current project through it.
## Enabling and connecting
1. Open **MCP** in **Settings** ({key:OPEN_SETTINGS}).
2. Under "Service", check **Enable the MCP service**, set the **Port** (1024–65535, 23871 by default), the **Access token** (can be **Regenerate**d) and the **Tools** set (**Compact (recommended)** or **Complete**), then click **Apply and restart**.
3. Under "Connect a host", copy the command or configuration for your host; **Copy setup prompt** produces a prompt that lets the host configure itself.
4. Once the host lists the tools, first call `workspace_inspect` to read the current project's object IDs and state.
## Notes
- The service runs only on this machine and uses Streamable HTTP with Bearer token authentication. The token can edit the current workspace; keep it only in local host configurations.
- Hosts that support only Stdio run `mcp_proxy.py` in the repository root with Python 3.
- After changing the port or token, update the configuration of every connected host.
- Without opening a window, the `mcp` command-line subcommand starts an MCP endpoint on its own, with workspace storage separate from the editor.
## Differences from Cubism
- Cubism: external applications connect to the editor over WebSocket, and the editor permits each application individually.
- PSD2Live: uses the MCP protocol with access token authentication; edits through MCP go through the same commands as the UI, and likewise enter the history and can be undone.
@@ external-application-integration-api | Live2D Cubism Editor External API Integration
PSD2Live's external interface is MCP tools. Every operation has a strict request and result schema; list them with `workspace_list_operations` and inspect one with `workspace_get_operation`.
## Calling conventions
- Arguments go in `{"request": {...}}`.
- Modifying operations need `state` (from `workspace_inspect` or the previous result); the complete tool set also needs `project_id` and a unique `request_id`. The compact tool set may omit the latter two; `request_id` is derived from the arguments, and retrying the same call returns the original result.
- When `state` does not match the current project, a conflict is reported; read again and retry.
- Long-running operations return a background job; get the result with `job_wait` / `job_get`. In the compact tool set, `wait_ms` can return the result directly within the time limit.
- `workspace_apply_edits` commits 1–128 edits as one atomic batch and appends only one history node when all succeed; `workspace_preview_edits` only does a dry run and does not change the project.
## Tool sets
| Tool set | Content |
|---|---|
| Compact (default) | Operations of the same family are merged into one tool (for example `motion` selects `motion_set_key` with `op`); the rest are called through `workspace_call` |
| Complete | One tool per operation, with full input and output schemas |
Every operation can be called under both tool sets, with the same execution and validation.
## Differences from Cubism
- Cubism: JSON messages are sent over WebSocket, mainly for reading and writing parameter values and getting editor state.
- PSD2Live: MCP tools can import, edit, save and export projects, covering the main editing features of the UI.
See [[external-application-integration-api-list]] for the list of operations.
@@ external-application-integration-api-list | List of External API Integration Functions
The main MCP tools are grouped by purpose below. Use `workspace_get_operation` for the full fields.
| Group | Tools | Purpose |
|---|---|---|
| Query | `workspace_overview`, `workspace_inspect` | Read the canvas, parameters, layers, deformers, physics groups, motions and so on |
| Edit | `workspace_apply_edits`, `workspace_preview_edits`, `rig_deform`, `keyform_apply` | Atomic batch edits, dry runs, keyform editing |
| Intent tools | `author_axis`, `author_physics`, `author_compile` | Create parameter axis shapes and physics groups and fit them in one step |
| Parameters and preview | `parameter`, `preview_set`, `preview_reset`, `snapshot_*` | Parameters, preview pose and parameter snapshots |
| Motion | `motion` (`motion_create`, `motion_pose`, `motion_move_keys` and so on) | Motion clips and keyframes |
| Skeleton / paths | `skeleton`, `path` | Skeleton and deform paths |
| Physics / swing / simulation | `physics`, `swing`, `simulation` | Physics groups, swings, cloth and hair simulation and baking |
| Painting | `source_paint` | Source layer pixels |
| Observation | `view` | Rendering and spatial mapping |
| History | `history` | History nodes and annotations |
| Project and export | `project` (`project_open`, `project_save`, `project_export_model`, `project_export_target`, `project_export_psd` and so on) | Open, import, save and export |
| Jobs | `job` (`job_get`, `job_wait`, `job_cancel` and so on) | Background jobs |
| Other | `workspace_call` | Calls operations not published directly as tools, such as `layer_classify` |
## Differences from Cubism
- Cubism's external integration API is listed by message type and is fairly small; PSD2Live's tools cover reading, writing, editing and exporting projects.
- The tool set only decides which tools are published; every operation can be called under both tool sets.
@@ viewer | Preview (Cubism Viewer counterpart)
@@ selection-of-viewer | Selection of Cubism Viewer
Cubism has Viewer (for OW) and Viewer (for Unity) for different uses. PSD2Live has no separate Viewer; you choose in the preview canvas which runtime plays the model.
## Preview runtime
The bottom of the toolbar at the left of the preview canvas lists three runtimes; click one to switch:
| Option | Description |
|---|---|
| Cubism SDK (C) | The exported moc3 plays in the official Cubism 5 runtime |
| p2lrt · Standard (P) | The exported `.p2lrt` plays in the PSD2Live runtime, the same as the web player |
| p2lrt · Advanced (P+) | The PSD2Live runtime's advanced mode: skinning along arcs, exact links, real-time cloth and hair simulation |
- The Cubism SDK preview needs the optional native preview component; when it is unavailable, a software fallback preview is shown.
- When the PSD2Live runtime library cannot be found, only Cubism SDK can be chosen.
## Differences from Cubism
- Cubism: the Viewer is a separate application window that reads the output files.
- PSD2Live: the preview is a canvas in the workspace that plays the export result compiled on the fly from the current project, and it updates right after edits.
@@ viewer-ow | Cubism Viewer (for OW)
@@ cubism3-viewer-for-ow | About Cubism Viewer (for OW)
Cubism Viewer (for OW) is used to check the exported model, set up motions and physics, and output model3.json. In PSD2Live this work is done by the preview canvas, the Animation panel, the Physics panel and the export window respectively.
## Correspondence
| Cubism Viewer feature | PSD2Live |
|---|---|
| Load model and motions | The preview canvas shows the current project directly; see [[load-model-and-motion]] |
| Motion settings | The Animation panel; see [[motion-setting]] |
| Physics settings | The Physics panel; see [[switch-viewer-setting]] |
| Check physics | The preview canvas's physics toggle and the pendulum canvas; see [[check-the-physics]] |
| Output the model settings file | Written automatically at export; see [[export-model3-json]] |
## Preview canvas
- Choose **Preview** ({key:MODE_PREVIEW}) in the canvas mode menu, or use the **Preview** workspace preset (a large preview with only the motion list); {key:QUICK_PREVIEW} switches quickly between editing and preview.
- Left toolbar: **Idle animation**, **Tracking**, **Smooth + body Y**, **Physics**, **Physics FPS**, and the runtime choice (see [[selection-of-viewer]]).
- The menu of the status capsule at the bottom left sets the preview's **Frame rate cap** (follows the display by default), which is independent of the physics frame rate.
## Differences from Cubism
- Cubism: the Viewer is a separate tool, and files must be exported before loading.
- PSD2Live: preview and editing are in the same window, so you can check without exporting; Viewer features such as expressions, pose settings and motion-sync are not yet available.
@@ load-model-and-motion | Loading Models and Motion
Cubism Viewer loads models and motions by dropping in model3.json and motion3.json. PSD2Live's preview canvas always shows the current project, with no files to load.
## How to use
1. After opening a project or importing a PSD, the model is visible in any preview canvas.
2. Click the play button on a motion row in the Animation panel to play it in the preview canvas.
3. After editing the model or motions, the preview updates automatically.
## Differences from Cubism
- Cubism: the Viewer loads exported files and can load several motion files at once.
- PSD2Live: the model comes from the current project; motions come from the project's built-in and custom motions, and external `motion3.json` files cannot be loaded.
*> Existing Cubism models can be imported as projects with **File → New → New project from CMO3…**; see [[cmo3-import]].
@@ motion-setting | Motion Settings
Cubism Viewer sets the group, fades, loop and so on for each motion and writes them to model3.json. PSD2Live sets these properties in the Animation panel and the Animation Editor and writes them automatically at export.
## Settings
| Item | Where |
|---|---|
| Whether to export | The checkbox at the left of a motion row in the Animation panel |
| Name | **Rename** in a custom motion's context menu |
| Loop | **Loop** on the Animation Editor toolbar |
| Duration / frame rate | **Duration** and **FPS** on the Animation Editor toolbar |
| Fade in / fade out | **Fade In (s)** / **Fade Out (s)** after expanding a custom motion; see [[fade-setting]] |
## Motion groups
Motion groups need no manual assignment: looping motions go into the `Idle` group and the other motions each form their own group; see [[notes-for-sdk]].
## Differences from Cubism
- Cubism: the motion group names, sound files and fades are set freely in the Viewer before outputting model3.json.
- PSD2Live: motion groups are decided automatically by rules, sound files cannot be assigned, and fades are set only for custom motions.
@@ pose-setting | Pose Settings
@@ facial-expression-system | Facial Expression Mechanism
@@ create-facial-expressions | Create Facial Expressions in Animation View
@@ setting-and-exporting-facial-expressions | Expression Settings and Export
@@ userdata-event-information | Check User Data and Event Information
@@ export-model3-json | Exporting Model Setting Files
model3.json is the model settings file that ties together the moc3, textures, physics, motions and other files. Cubism outputs it in the Viewer; PSD2Live writes it automatically when exporting the Live2D model.
## How it is written
Check **moc3** in **File → Export Live2D model…** ({key:GENERATE}) and export; `<name>.model3.json` is generated in the output folder; see [[export-moc3-motion3-files]].
## Content
| Item | Content |
|---|---|
| `Moc` / `Textures` | The moc3 file and the texture atlas pages |
| `Physics` | `physics3.json` (when **Include physics3.json** is checked and there is physics) |
| `DisplayInfo` | `cdi3.json` (when **Include cdi3.json** is checked) |
| `Motions` | All motion files listed by motion group |
| `Groups` | `EyeBlink` (when blinking is enabled) and `LipSync` |
## Differences from Cubism
- Cubism: output by hand after setting motions, expressions, poses, hit areas and so on in the Viewer.
- PSD2Live: written automatically with the model, with content determined by the project; expressions, poses and hit areas are not written.
@@ check-the-physics | Check Physics Information
Cubism Viewer checks the effect of physics by dragging the model. PSD2Live checks it in the preview canvas and the Physics panel.
## Checking in the preview canvas
1. Turn on **Physics** on the toolbar at the left of the preview canvas and choose the **Physics FPS**.
2. Turn on **Tracking** and move the pointer, or play a motion, and watch hair and other parts sway; you can also stop the idle animation and drag parameter sliders directly.
3. With the Cubism SDK runtime selected, the preview reads the exported `physics3.json` directly with the official SDK.
## Checking in the Physics panel
- Select a physics group and drag left and right on an empty spot of the pendulum canvas to pull the model, watching the pendulums and outputs.
- The response curve shows each output's maximum amplitude and settle time under a standard pull.
- When **Max output** exceeds 100% it is shown in the warning color, meaning the parameter is clipped at its end; fix it with **Fit scale to max**.
The number of physics groups, the active count and the frame rate are shown at the top of the Physics panel (**n / m active**).
## Differences from Cubism
- Cubism: check by dragging the model in the Viewer and look at the physics settings' values.
- PSD2Live: preview and editing are in the same window; you can try each group in the pendulum canvas, which shows the response curve and the max output.
{tutorial=PHYSICS}
@@ motion-sync-setting-ow | Motion-sync Settings (OW)
@@ texture-setting | Texture settings
