package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.umamo.format.art.SourceArt
import org.umamo.format.psd.PsdReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** New source projects use one isolated read/rebuild/install boundary in both adapters. */
internal class WorkspaceSourceImporter(
    private val runtime: WorkspaceRuntime<RigPreviewModel>,
    private val build: suspend (WorkspaceDocument) -> RigPreviewModel = { WorkspacePreviewBuilder().build(it) },
    private val readPsd: suspend (Path) -> SourceArt = { path -> runInterruptible(Dispatchers.IO) {
        val bytes = Files.readAllBytes(path)
        require(PsdReader.matches(bytes)) { "Invalid PSD file" }
        PsdReader.read(bytes)
    } },
    private val readArtwork: suspend (JsonObject) -> Pair<WorkspaceSourceArt, Map<String, LayerClassificationOverride>> = {
        runInterruptible(Dispatchers.IO) { sourceArtwork(it) }
    },
    private val newProjectId: () -> String = { UUID.randomUUID().toString() },
    private val initialWorkspaceIds: () -> List<String> = { emptyList() },
) {
    suspend fun importPsd(path: Path, projectId: String?, state: String, discardUnsaved: Boolean = false,
                         initialConfig: PipelineConfig = PipelineConfig(),
                         sourceAuxiliary: JsonObject = JsonObject(emptyMap()),
                         project: (WorkspaceDocument, RigPreviewModel, String) -> Unit = { _, _, _ -> }): WorkspaceCommit<RigPreviewModel> {
        return install(projectId, state, discardUnsaved, initialConfig, "Imported PSD ${path.fileName}",
            prepare = {
                require(path.isAbsolute && Files.isRegularFile(path) && path.fileName.toString().endsWith(".psd", true)) { "Provide an absolute local PSD path" }
                readPsd(path) to emptyMap()
            }, project = project, sourceAuxiliary = sourceAuxiliary, materialFolder = path.fileName.toString().substringBeforeLast('.'))
    }

    suspend fun createArtwork(arguments: JsonObject, projectId: String?, state: String, discardUnsaved: Boolean = false,
                              initialConfig: PipelineConfig = PipelineConfig(),
                              project: (WorkspaceDocument, RigPreviewModel, String) -> Unit = { _, _, _ -> }): WorkspaceCommit<RigPreviewModel> =
        install(projectId, state, discardUnsaved, initialConfig, "Created source artwork", { readArtwork(arguments) }, project)

    /** A new empty canvas of [width] x [height], not generated: materials are placed first and the rig generated later. */
    suspend fun createLayout(width: Int, height: Int, projectId: String?, state: String, discardUnsaved: Boolean = false,
                             initialConfig: PipelineConfig = PipelineConfig(),
                             project: (WorkspaceDocument, RigPreviewModel, String) -> Unit = { _, _, _ -> }): WorkspaceCommit<RigPreviewModel> {
        require(width in 1..8192 && height in 1..8192 && width.toLong() * height <= 16_777_216) { "Canvas must be 1..8192 per side and at most 16 megapixels" }
        return install(projectId, state, discardUnsaved, initialConfig, "Created layout project",
            { WorkspaceSourceArt(width, height, emptyList(), emptyList()) to emptyMap() }, project, layout = true)
    }

    private suspend fun install(projectId: String?, state: String, discardUnsaved: Boolean, initialConfig: PipelineConfig,
                                summary: String, prepare: suspend () -> Pair<SourceArt, Map<String, LayerClassificationOverride>>,
                                project: (WorkspaceDocument, RigPreviewModel, String) -> Unit,
                                sourceAuxiliary: JsonObject = JsonObject(emptyMap()), layout: Boolean = false,
                                materialFolder: String? = null): WorkspaceCommit<RigPreviewModel> {
        val before = runtime.state.value
        currentCoroutineContext().ensureActive()
        // Capture before source I/O. Another edit or reload cannot be hidden by taking a newer token.
        WorkspaceExecution(projectId, state, MutationAuthor.AGENT).check(before.capture?.projectId, before.state)
        if (before.capture?.dirty == true && !discardUnsaved) throw WorkspaceUnsavedChanges()
        suspend fun progress(value: Float, message: String) {
            currentCoroutineContext().ensureActive()
            currentCoroutineContext()[WorkspaceJobContext]?.progress(value, message)
        }
        progress(0.1f, "Reading source artwork")
        val (source, overrides) = prepare()
        progress(0.4f, "Preparing new source document")
        val config = initialConfig.copy(layerVisibility = emptyMap(), deletedLayerIds = emptySet(),
            layerOverrides = overrides, parentOverrides = emptyMap(), drawOrderOverrides = emptyMap(), meshOverrides = emptyMap(),
            rigEdits = RigEditOverlay.Empty, generationSource = null, meshSource = null, hairSimulationFront = false, hairSimulationBack = false)
        val created = WorkspaceDocument(source, emptyMap(), emptySet(), overrides, emptyMap(), RigEditOverlay.Empty,
            WorkspaceSettingsCodec.encode(config),
            layout = if (layout) WorkspaceLayout(generation = WorkspaceGeneration.NotGenerated) else WorkspaceLayout.Empty)
        // An imported file's layers are also materials of the new project, each layer showing its own: they can be
        // replaced, placed again or exported from the material browser. The rig reads only the source, as before.
        val document = materialFolder?.let { name -> WorkspaceMaterialEdits.addLinked(created, name) } ?: created
        progress(0.6f, "Rebuilding new source project")
        val built = build(document)
        if (layout) {
            progress(0.9f, "Installing new layout project")
            val id = newProjectId()
            val installed = runtime.install(state, id, document, built,
                auxiliary = JsonObject(sourceAuxiliary + WorkspaceAuxiliaryCodec.encode(WorkspaceAuxiliaryData()) +
                    PreviewSessions.initial(built.rig.puppet.parameters, initialWorkspaceIds()) +
                    ("assetCatalog" to WorkspaceAssetCatalog().encode())), discardUnsaved = discardUnsaved, dirty = true) {
                project(document, built, id)
            }
            val result = WorkspaceCommit(installed, true)
            currentCoroutineContext()[WorkspaceJobCompletion]?.committed(WorkspaceOperationOutput(result.mutation(summary).lifecycleResult()))
            return result
        }
        // A new project keeps its first layout: the atlas is arranged on request, not on every edit. The stored
        // arrangement is that layout exactly, so the model it builds is this one.
        val arrangement = AtlasLayout.frozen(built.atlas)
        val preview = built.copy(config = built.config.copy(atlasArrangement = arrangement), atlas = built.atlas.copy(arranged = true))
        // The preview was generated from effective settings; the document keeps the raw ones it was given.
        val prepared = document.copy(rigEdits = preview.config.rigEdits,
            settings = AtlasArrangementCodec.with(WorkspaceSettingsCodec.encode(WorkspaceSettingsPolicy.restoreRaw(preview.config, config)), arrangement))
        progress(0.9f, "Installing new source project")
        val id = newProjectId()
        val installed = runtime.install(state, id, prepared, preview,
            auxiliary = JsonObject(sourceAuxiliary + WorkspaceAuxiliaryCodec.encode(WorkspaceAuxiliaryData()) +
                PreviewSessions.initial(preview.rig.puppet.parameters, initialWorkspaceIds()) +
                ("assetCatalog" to WorkspaceAssetCatalog().encode())), discardUnsaved = discardUnsaved, dirty = true) {
            project(prepared, preview, id)
        }
        val result = WorkspaceCommit(installed, true)
        currentCoroutineContext()[WorkspaceJobCompletion]?.committed(WorkspaceOperationOutput(result.mutation(summary).sourceResult()))
        return result
    }
}
