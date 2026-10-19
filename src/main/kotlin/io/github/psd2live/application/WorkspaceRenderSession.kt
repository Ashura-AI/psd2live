package io.github.psd2live.application

import io.github.psd2live.core.PreviewRenderer
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.project.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.umamo.format.art.SourceLayer
import org.umamo.format.art.isEffectivelyVisible
import java.awt.AlphaComposite
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/** Pure captured image preparation; the host only stores the resulting observation resource. */
internal class WorkspaceRenderSession(
    private val read: WorkspaceReadCapture<RigPreviewModel>,
    private val remember: (WorkspaceRenderedView) -> WorkspaceRenderedView,
    visibleLayerIds: Set<String>? = null,
) : WorkspaceImageRenderer {
    private val visibleLayerIds = visibleLayerIds?.toSet()
    private fun capture() = checkNotNull(read.runtime.capture) { "No workspace is loaded" }

    private suspend fun rendered(build: () -> WorkspaceRenderedView) = withContext(Dispatchers.Default) {
        ensureActive()
        val view = build()
        ensureActive()
        remember(view)
    }

    override suspend fun renderLayer(layerId: String, background: WorkspaceViewBackground, output: WorkspaceViewOutputSpec): WorkspaceRenderedView {
        val captured = capture()
        val analysis = captured.model.analysis
        val layer = analysis.layers.firstOrNull { it.source.id.raw == layerId }?.source
            ?: throw IllegalArgumentException("Layer not found: $layerId")
        return rendered { WorkspaceViewRenderer.isolatedLayer(layer, analysis.source.widthPx, analysis.source.heightPx,
            captured.revision, background, output) }
    }

    override suspend fun renderContext(layerId: String, objectScale: Float, aspectRatio: Float, background: WorkspaceViewBackground,
                                       output: WorkspaceViewOutputSpec): WorkspaceRenderedView {
        val captured = capture()
        val analysis = captured.model.analysis
        val classified = analysis.layers.firstOrNull { it.source.id.raw == layerId }
            ?: throw IllegalArgumentException("Layer not found: $layerId")
        return rendered { WorkspaceViewRenderer.context(composite(captured), classified.source, classified.bounds,
            captured.revision, objectScale, aspectRatio, background, output) }
    }

    override suspend fun renderModel(request: WorkspaceModelViewRequest): WorkspaceRenderedView {
        val captured = capture()
        val reference = request.reference?.let { view ->
            val set = captured.document.layout.reference ?: throw IllegalArgumentException("No reference image is set; layout_set_reference sets one")
            val item = requireNotNull(captured.document.layout.materials.item(set.materialId)) { "Reference material not found: ${set.materialId}" }
            ViewReferenceImage(PreviewRenderer.rasterImage(item.raster.width, item.raster.height, item.raster.rgba), set.rect, view.mode,
                view.opacity ?: set.opacity)
        }
        val outlines = request.outlineMaterialIds.map { id ->
            val item = captured.document.layout.materials.item(id) ?: throw IllegalArgumentException("Material item not found: $id")
            val place = item.placement?.takeIf { item.placementWidth == captured.document.source.widthPx && item.placementHeight == captured.document.source.heightPx }
                ?: throw IllegalArgumentException("$id has no place on this canvas to outline; outline drawings and crops")
            ViewOutline(id, PreviewRenderer.rasterImage(item.raster.width, item.raster.height, item.raster.rgba), place)
        }
        return renderModel(captured.model, captured.revision, request.copy(includeLayerIds =
            request.includeLayerIds ?: visibleLayerIds ?: captured.document.renderVisibleLayers(captured.model)), reference = reference, outlines = outlines,
            referencePins = captured.document.layout.referencePins)
    }

    override suspend fun renderPad(request: WorkspacePadViewRequest): WorkspaceWorkflowResult {
        val captured = capture()
        return withContext(Dispatchers.Default) {
            WorkspacePadSheet.render(captured.model, captured.document, captured.revision, request, visibleLayerIds) { ensureActive() }
        }
    }

    /** Historical and sampled views reuse the same validation and captured resource destination. */
    internal suspend fun renderModel(model: RigPreviewModel, revision: String, request: WorkspaceModelViewRequest,
                                     worldPositions: Map<org.umamo.runtime.model.DrawableId, FloatArray> = emptyMap(),
                                     reference: ViewReferenceImage? = null, outlines: List<ViewOutline> = emptyList(),
                                     referencePins: Map<String, Map<String, Float>> = emptyMap()): WorkspaceRenderedView {
        val parameters = model.rig.puppet.parameters.associateBy { it.id.raw }
        val unknown = request.parameters.keys - parameters.keys
        require(unknown.isEmpty()) { "Unknown parameter IDs: ${unknown.sorted().joinToString()}" }
        require(request.parameters.values.all(Float::isFinite)) { "Parameter values must be finite" }
        val applied = parameters.values.associate { it.id.raw to it.default } + request.parameters
        // Reference layers pinned to another keyform stay out of this pose.
        val hiddenReferences = ReferencePins.hidden(referencePins, model.rig.puppet.parameters, applied::get)
        return rendered { WorkspaceViewRenderer.modelComposite(model, revision, applied, requireNotNull(request.includeLayerIds),
            request.annotateLayerIds, request.frame, request.background, request.output,
            request.annotateDeformerIds, request.annotatePathIds, request.annotatePathWidth,
            request.annotatePathHardness, request.annotatePathRadius, request.pointIndices, worldPositions, reference, request.density,
            request.grid, outlines, hiddenReferences) }
    }

    private fun composite(captured: WorkspaceCapture<RigPreviewModel>): BufferedImage {
        val analysis = captured.model.analysis
        val canvas = BufferedImage(analysis.source.widthPx, analysis.source.heightPx, BufferedImage.TYPE_INT_ARGB)
        val graphics = canvas.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            for (classified in analysis.layers) {
                val layer = classified.source
                if (!captured.document.renderLayerVisible(layer, captured.model) || layer.opacity <= 0f) continue
                graphics.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, layer.opacity.coerceIn(0f, 1f))
                PreviewRenderer.drawLayer(graphics, layer)
            }
        } finally { graphics.dispose() }
        return canvas
    }
}

private fun WorkspaceDocument.renderLayerVisible(layer: SourceLayer, model: RigPreviewModel): Boolean {
    val id = layer.id.raw
    if (id in deletedLayerIds) return false
    val parent = id.takeIf { it.endsWith(":l") || it.endsWith(":r") }?.dropLast(2)
    return layerVisibility[id] ?: parent?.let(layerVisibility::get) ?: model.analysis.source.isEffectivelyVisible(layer)
}

internal fun WorkspaceDocument.renderVisibleLayers(model: RigPreviewModel): Set<String> =
    model.analysis.layers.filter { renderLayerVisible(it.source, model) }.mapTo(HashSet()) { it.source.id.raw }
