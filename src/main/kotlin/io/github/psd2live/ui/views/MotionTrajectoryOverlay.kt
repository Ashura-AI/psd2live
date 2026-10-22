package io.github.psd2live.ui.views

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import io.github.psd2live.core.CanvasViewport
import io.github.psd2live.core.MotionTrajectory
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.umamo.runtime.model.ParameterId

/**
 * The selected object's path through the motion open in the editor, over the preview canvas: a line through every
 * frame, a dot per frame and a ring at the playhead. Shown while the animation editor's Trajectory is on.
 */
@Composable
internal fun MotionTrajectoryOverlay(
	viewModel: PSD2LiveViewModel,
	model: RigPreviewModel,
	pose: Map<ParameterId, Float>,
	locked: Set<ParameterId>,
	selectedLayerIds: Set<String>,
	selectedDeformerId: String?,
	viewport: () -> CanvasViewport,
) {
	val editor = viewModel.motionEditor
	if (!editor.showTrajectory) return
	val state by viewModel.uiState
	val clip = viewModel.editingMotionClip(state) ?: return
	val frozen = viewModel.openMotionAids(state).frozen.mapKeys { ParameterId(it.key) }
	val drawables = model.rig.layerIdByDrawableId.filterValues { it in selectedLayerIds }.keys
	if (drawables.isEmpty() && selectedDeformerId == null) return
	val probes = remember(model, drawables, selectedDeformerId) { MotionTrajectory.probes(model.rig.puppet, drawables, selectedDeformerId) } ?: return
	// The motion's own tracks and the frozen ones come from the motion, so only the other parameters move the path;
	// while playing the base is held, the frame changing every tick.
	val driven = clip.curves.mapTo(HashSet()) { ParameterId(it.parameterId) } + frozen.keys
	val current = pose.filterKeys { it !in driven || it in locked }
	val held = remember { mutableStateOf(current) }
	if (!editor.playing) held.value = current
	val base = held.value
	val track by produceState<MotionTrajectory.Track?>(null, probes, clip, base, frozen, locked) {
		value = withContext(Dispatchers.Default) {
			MotionTrajectory.sample(probes, clip, base, frozen, locked) { !coroutineContext.isActiveSafe() }
		}
	}
	val path = track ?: return
	val colors = LocalToolColors.current
	val playhead = editor.playhead
	Canvas(Modifier.fillMaxSize()) {
		val vp = viewport()
		fun at(i: Int) = Offset(vp.x(path.points[i * 2]).toFloat(), vp.yFromWorld(path.points[i * 2 + 1]).toFloat())
		val line = Path()
		for (i in 0 until path.size) { val p = at(i); if (i == 0) line.moveTo(p.x, p.y) else line.lineTo(p.x, p.y) }
		drawPath(line, colors.panelBackground.copy(alpha = 0.6f), style = Stroke(3.dp.toPx()))
		drawPath(line, colors.accent, style = Stroke(1.4.dp.toPx()))
		for (i in 0 until path.size) drawCircle(colors.accent, 1.8.dp.toPx(), at(i))
		val current = at(path.indexAt(playhead))
		drawCircle(colors.panelBackground, 5.dp.toPx(), current, style = Stroke(3.dp.toPx()))
		drawCircle(colors.accent, 5.dp.toPx(), current, style = Stroke(1.6.dp.toPx()))
	}
}

private fun kotlin.coroutines.CoroutineContext.isActiveSafe(): Boolean = this[kotlinx.coroutines.Job]?.isActive != false
