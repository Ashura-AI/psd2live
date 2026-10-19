package io.github.psd2live.ui.views

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import io.github.psd2live.core.CanvasViewport
import io.github.psd2live.project.LayoutReference
import io.github.psd2live.ui.*

internal fun LayoutReference.contains(pointer: Offset, viewport: CanvasViewport): Boolean {
    val frame = transformFrame(viewport)
    return frame.bounds.contains(pointer.intoTransformFrame(frame.pivot, frame.angleDeg))
}

/** Canvas-owned input and preview. Neither the tree nor the transform frame owns a second selection. */
internal class ReferenceCanvasInteraction {
    var draft by mutableStateOf<LayoutReference?>(null)
        private set
    var hovered by mutableStateOf(BoundingHandle.NONE)
        private set
    var pointer by mutableStateOf<Offset?>(null)
        private set
    var activeHandle by mutableStateOf(BoundingHandle.NONE)
        private set
    private var anchorId: String? = null
    private var anchorUv by mutableStateOf<Offset?>(null)
    private var gesture: Gesture? = null
    val active get() = activeHandle != BoundingHandle.NONE
    val referenceId get() = gesture?.source?.id ?: draft?.id
    private data class Gesture(val source: LayoutReference, val frame: TransformFrame, val drag: TransformDrag, val scale: Float)

    fun frame(reference: LayoutReference, viewport: CanvasViewport): TransformFrame =
        (draft?.takeIf { it.id == reference.id } ?: reference).transformFrame(viewport)
            .withAnchor(anchorUv.takeIf { anchorId == reference.id })

    fun press(reference: LayoutReference, viewport: CanvasViewport, at: Offset, handles: TransformHandles): Boolean {
        if (reference.locked) return false
        val frame = frame(reference, viewport)
        val handle = transformHandleAt(at, frame, handles)
        if (handle == BoundingHandle.NONE) return false
        if (anchorId != reference.id) { anchorId = reference.id; anchorUv = null }
        val source = draft?.takeIf { it.id == reference.id } ?: reference
        gesture = Gesture(source, frame, TransformDrag(handle, frame.bounds, frame.pivot, frame.angleDeg, at, frame.anchor), viewport.scale.toFloat())
        activeHandle = handle
        pointer = at
        return true
    }

    fun move(at: Offset, shift: Boolean, alt: Boolean, handles: TransformHandles) {
        pointer = at
        val press = gesture ?: return
        if (activeHandle == BoundingHandle.ANCHOR) anchorUv = press.frame.anchorDraggedTo(at, handles.unit)
        else draft = press.source.transformed(press.frame, press.drag.apply(at, null, shift, alt), press.scale)
    }

    fun hover(reference: LayoutReference?, viewport: CanvasViewport, at: Offset, handles: TransformHandles) {
        pointer = at
        hovered = reference?.takeUnless { it.locked }?.let { transformHandleAt(at, frame(it, viewport), handles) } ?: BoundingHandle.NONE
    }

    /** Return a single placement to commit on release, keeping the preview until the document catches up. */
    fun release(): LayoutReference? {
        val press = gesture ?: return null
        val changed = draft?.takeIf { it.id == press.source.id && it != press.source && activeHandle != BoundingHandle.ANCHOR }
        gesture = null
        activeHandle = BoundingHandle.NONE
        return changed
    }

    fun settle() { if (!active) draft = null }
    fun cancel() { gesture = null; draft = null; activeHandle = BoundingHandle.NONE; hovered = BoundingHandle.NONE; pointer = null }
}
