package io.github.psd2live.ui.views

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.psd2live.render.CompositedView
import io.github.psd2live.render.WindowGpu
import org.jetbrains.skia.Picture
import org.jetbrains.skia.PictureRecorder
import org.jetbrains.skia.Rect
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A canvas the window compositor draws ([CompositedView]) while [enabled] and the window composites: its Compose
 * draw only clears a hole ([clear]) and leaves [onDraw] to the compositor, which records it once into a picture and
 * draws that picture under the window's cached Compose picture every frame. The snapshot state [onDraw] reads is
 * observed here, so a change asks the window for a frame and records again without invalidating Compose's scene:
 * a pose, a hover or a dragged point no longer replays the whole window. Otherwise it draws as a [androidx.compose.foundation.Canvas].
 *
 * Views of one place paint from the lowest [order] up. A canvas over another composited one passes [clear] false: the
 * one under it cleared the hole already, and clearing again would erase whatever Compose drew between them. Canvases of
 * one [layer] paint together into one multisampled texture ([CompositedView.layer]), so the one over blends with the
 * one under it as it did in Compose.
 */
@Composable
internal fun CompositedCanvas(
	modifier: Modifier,
	gpu: WindowGpu?,
	viewId: String,
	enabled: Boolean,
	origin: State<Offset>,
	order: Int = 0,
	clear: Boolean = true,
	layer: String? = null,
	onDraw: DrawScope.() -> Unit,
) {
	val draw = rememberUpdatedState(onDraw)
	val on = rememberUpdatedState(enabled)
	val view = remember(gpu, viewId, layer) { CompositedDraw(gpu, viewId, order, clear, layer, draw, on, origin) }
	DisposableEffect(view) {
		view.start()
		onDispose { view.dispose() }
	}
	// The draw lambda is the remembered view's: a recomposition hands the modifier the same one, so Compose does
	// not draw (and the compositor replay) the window again just because this canvas composed.
	Spacer(modifier.drawBehind(view.composeDraw))
}

private class CompositedDraw(
	private val gpu: WindowGpu?,
	private val viewId: String,
	private val order: Int,
	private val clear: Boolean,
	private val layer: String?,
	private val draw: State<DrawScope.() -> Unit>,
	private val enabled: State<Boolean>,
	private val origin: State<Offset>,
) {
	private val stale = AtomicBoolean(true)
	private val observer = SnapshotStateObserver { change ->
		if (javax.swing.SwingUtilities.isEventDispatchThread()) change() else javax.swing.SwingUtilities.invokeLater(change)
	}
	private val onChanged: (Any) -> Unit = {
		stale.set(true)
		gpu?.requestRedraw()
	}
	private var shown = false
	private var picture: Picture? = null

	fun start() = observer.start()

	fun dispose() {
		observer.stop()
		observer.clear()
		hide()
		picture?.close()
		picture = null
	}

	private fun hide() {
		if (shown) gpu?.hideView(viewId)
		shown = false
	}

	val composeDraw: DrawScope.() -> Unit = {
		val gpu = gpu
		if (gpu != null && enabled.value && gpu.composited.value && gpu.compositing) {
			val at = origin.value
			val drawDensity = Density(density, fontScale)
			val direction = layoutDirection
			val holeSize = size
			shown = true
			gpu.showView(viewId, CompositedView(at.x, at.y, holeSize.width, holeSize.height, order, layer,
				stale = { stale.get() || picture == null }) { target, _ -> paint(target, drawDensity, direction, holeSize) })
			// The view is new: its size or density may differ from the one recorded.
			stale.set(true)
			if (clear) drawRect(Color.Transparent, blendMode = BlendMode.Clear)
		} else {
			hide()
			draw.value(this)
		}
	}

	private fun paint(target: org.jetbrains.skia.Canvas, density: Density, direction: LayoutDirection, size: Size) {
		if (stale.getAndSet(false) || picture == null) {
			val recorder = PictureRecorder()
			val recording = recorder.beginRecording(Rect.makeWH(size.width, size.height))
			try {
				observer.observeReads(this, onChanged) {
					CanvasDrawScope().draw(density, direction, recording.asComposeCanvas(), size) { draw.value(this) }
				}
			} finally {
				picture?.close()
				picture = recorder.finishRecordingAsPicture()
				recorder.close()
			}
		}
		picture?.let(target::drawPicture)
	}
}
