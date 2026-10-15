package io.github.psd2live.application

import io.github.psd2live.core.CubismRuntimeBundle
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.project.WorkspaceAuxiliaryData
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.WorkspaceHistorySnapshot
import kotlinx.serialization.json.JsonObject
import org.umamo.runtime.model.ParameterId
import java.nio.file.Path

/**
 * What a [WorkspaceHost] reads of the workspace's presentation and what it tells it, for one kind of host: the desktop
 * editor shows the workspace in Compose state, the headless host shows nothing. [V] is the host's snapshot of its
 * presentation, captured when an operation starts and handed back with each notification so the host can tell
 * whether the presentation moved on meanwhile.
 *
 * The runtime's document is authoritative. A presentation may hold its own copy of the document while the editor
 * shows it ([document]); then the host projects each commit into it before the commit lands ([project]).
 */
internal interface WorkspaceHostPresentation<V> {
    fun current(): V

    /** [read] of the current snapshot while the presentation holds still. */
    fun <T> withCurrent(read: (V) -> T): T

    fun facts(view: V): WorkspaceHostFacts

    /** The document [view] shows, or null when it shows the runtime's own. */
    fun document(view: V): WorkspaceDocument?

    /** The layers [view] shows of [capture], or null for the document's visible layers. */
    fun visibleLayers(capture: WorkspaceCapture<RigPreviewModel>, view: V): Set<String>?

    /** Auxiliary data the presentation holds for a project it installs: parameter snapshots, poses, canvases. */
    fun auxiliary(view: V): JsonObject

    /** [view] over [capture] as the presentation a project archive or recovery checkpoint stores. */
    fun encode(capture: WorkspaceCapture<RigPreviewModel>, view: V, checkpoint: Boolean): JsonObject

    /** A snapshot read back from an archive's or checkpoint's [presentation]; the current one when null. */
    fun decode(presentation: JsonObject?): V

    /** The settings [view] starts a new source from when no project is open. */
    fun config(view: V): PipelineConfig

    /** The model [view] already built, and its draw orders, to build an installed document against. */
    fun basis(view: V): Pair<RigPreviewModel?, Map<String, Float>>

    /**
     * Shows [next] built as [preview] if the presentation still shows [expected] as [view] did; false refuses the
     * commit. Called inside the runtime's commit, so the presentation and the history change together.
     */
    fun project(view: V, preview: RigPreviewModel, expected: WorkspaceDocument?, next: WorkspaceDocument, status: String,
                restoreCheckedOutPose: Boolean = false): Boolean

    fun historyChanged(history: WorkspaceHistorySnapshot)

    /** The history head the presentation shows, to repair one left behind by a failed notification. */
    fun shownHistoryHead(): String?

    fun modelPublished(model: RigPreviewModel)
    fun posesChanged(view: V, poses: Map<String, WorkspacePose>)
    fun playbackFrame(frame: JsonObject)
    fun authoredPose(view: V, pose: WorkspacePose, changed: Boolean, commit: PendingPoseCommit?, projectDocument: () -> Unit)
    fun previewSession(view: V, pose: WorkspacePose, changed: Boolean, commit: PendingPoseCommit? = null)
    fun swingPreview(preview: WorkspaceSwingPreview)
    fun simulationPreview(preview: WorkspaceSimulationPreview)
    fun canvasVisibility(view: V, address: CanvasAddress, value: CanvasVisibility, changed: Boolean)
    fun savedDataInstalled(view: V, before: WorkspaceAuxiliaryData, next: WorkspaceAuxiliaryData)
    fun auxiliaryChanged()
    fun discardDrafts()

    fun sourceInstalled(view: V, document: WorkspaceDocument, preview: RigPreviewModel, path: Path?, projectId: String)
    fun cmo3Installed(view: V, document: WorkspaceDocument, preview: RigPreviewModel, path: Path, projectId: String, replacing: Boolean)

    /** Shows the project read from [file] (or restored) as [view], replacing [expected]. */
    fun projectInstalled(view: V, expected: V, projectId: String, file: Path?, source: Path, document: WorkspaceDocument,
                         preview: RigPreviewModel, dirty: Boolean)

    /** A source the presentation analysed itself, to install as the workspace's project; null when it holds none. */
    fun importedSource(view: V): WorkspaceImportedSource?

    fun projectProgress(step: WorkspaceProjectStep, path: Path, fraction: Float)
    fun projectProgressCleared()
    fun saveStarted()
    fun saveFinished(path: Path, head: String, baseline: V, runtimeState: String, runtimeSaved: Boolean)
    fun saveFailed(failure: Throwable, view: V)

    fun <T> simulationBake(id: String, action: ((Float) -> Unit, () -> Boolean) -> T): T
    suspend fun sampleMotion(bundle: CubismRuntimeBundle, parameters: List<ParameterId>, frames: Int, fps: Int,
                             progress: (Float) -> Unit, cancelled: () -> Boolean): List<Map<ParameterId, Float>>
}

/** What a presentation snapshot says about the workspace, for the host's checks and queries. */
internal data class WorkspaceHostFacts(
    val projectId: String?,
    val openGeneration: Long = 0,
    val analyzing: Boolean = false,
    val generating: Boolean = false,
    val saving: Boolean = false,
    val dirty: Boolean = false,
    val canvasEditBusy: Boolean = false,
    val workspaceEditBusy: Boolean = false,
    val activeWorkspaceId: String,
    val workspaceIds: List<String>,
    /** The canvases of every workspace, by display mode; computed when asked. */
    val canvasAddresses: () -> List<CanvasAddress>,
    val mouseTracking: Boolean = false,
    val smoothTracking: Boolean = false,
    val sourceName: String? = null,
    val inputPath: String? = null,
    val projectFile: String? = null,
    val status: String = "",
    val selectedLayerId: String? = null,
    val saveError: String? = null,
    /** Whatever else the presentation counts as the project's saved content, compared when a project opens. */
    val saveIdentity: () -> Any? = { null },
) {
    val busy: Boolean get() = analyzing || generating
}

/** A source analysed outside the runtime, with the identity its legacy history was stored under. */
internal data class WorkspaceImportedSource(
    val projectId: String,
    val legacyProjectId: String,
    val document: WorkspaceDocument,
    val preview: RigPreviewModel,
    val legacySettings: JsonObject,
    val dirty: Boolean,
)

/** The stages of opening and saving a project archive, for progress. */
internal enum class WorkspaceProjectStep { CAPTURING, STAGING, PACKING, EXTRACTING, READING, BUILDING }
