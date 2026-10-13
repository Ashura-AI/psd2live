package io.github.psd2live.ui.state

/**
 * Content that requires Save/Discard. Presentation (camera, focus, selection, filters, panels,
 * view options and playback controls) is still captured in archives, but never requires saving.
 * Kept as immutable values rather than serializing the entire presentation on every preview frame.
 */
internal data class ProjectSaveIdentity(
    val source: org.umamo.format.art.SourceArt?,
    val config: io.github.psd2live.core.PipelineConfig,
    val outputPath: String,
    val snapshots: List<io.github.psd2live.project.ParameterSnapshot>,
    val annotations: Map<String, io.github.psd2live.project.HistoryAnnotation>,
    /** Assets and workflow records also live outside the document. */
    val auxiliaryVersion: Long,
    val workspaces: List<SavedWorkspace>,
) {
    data class SavedWorkspace(
        val id: String,
        val name: String,
        val preset: WorkspacePreset,
        val values: Map<org.umamo.runtime.model.ParameterId, Float>,
        val locks: Set<org.umamo.runtime.model.ParameterId>,
        val canvases: Map<String, List<io.github.psd2live.application.CanvasVisibility>>,
    )

    companion object {
        fun of(state: PSD2LiveState) = ProjectSaveIdentity(
            state.analysis?.source, state.rawConfig(), state.outputPath,
            state.parameterSnapshots, state.historyAnnotations, state.projectAuxiliaryVersion,
            state.workspaces.map { workspace ->
                val pose = if (workspace.id == state.activeWorkspace.id) WorkspacePose.capture(state)
                    else workspace.pose ?: WorkspacePose.capture(workspace.activeCanvas.presentation)
                SavedWorkspace(workspace.id, workspace.name, workspace.preset, pose.parameterValues, pose.lockedParameters,
                    workspace.canvases.associate { canvas -> canvas.id to listOf(
                        canvas.editSession.presentation.canvasVisibility(), canvas.previewSession.presentation.canvasVisibility()) })
            },
        )
    }
}
