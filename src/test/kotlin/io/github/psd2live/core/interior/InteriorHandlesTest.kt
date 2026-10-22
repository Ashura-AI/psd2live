package io.github.psd2live.core.interior

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.art.*

class InteriorHandlesTest {
	@TempDir lateinit var directory: Path

	private val frame = InteriorFrame(10f, 20f, 110f, 220f)

	private fun near(a: Float, b: Float, eps: Float = .011f) = assertTrue(abs(a - b) <= eps, "$a != $b")

	@Test
	fun surfaceHandlesReadBackTheValuesTheyWerePlacedFor() {
		for (s in listOf(
			InteriorSurfaceSettings(),
			InteriorSurfaceSettings(0f, 2f, 0f, 2f, 1f, .5f),
			InteriorSurfaceSettings(1.37f, .03f, 1.99f, .42f, 0f, 0f),
		)) {
			val handles = InteriorHandles.surface(frame, s)
			for (handle in InteriorHandle.SURFACE) {
				val read = InteriorHandles.dragSurface(handle, frame, s, handles.getValue(handle))
				near(s.bulge, read.bulge); near(s.press, read.press); near(s.horizontal, read.horizontal); near(s.vertical, read.vertical)
				assertEquals(s.folds, read.folds); assertEquals(s.contour, read.contour)
			}
		}
		// The cross: bulge up, press left, the gains right and down; zero keeps an arm clear of the centre.
		val zero = InteriorHandles.surface(frame, InteriorSurfaceSettings(0f, 0f, 0f, 0f, 0f, 0f))
		assertTrue(zero.getValue(InteriorHandle.BULGE).y < frame.cy)
		assertTrue(zero.getValue(InteriorHandle.PRESS).x < frame.cx)
		assertTrue(zero.getValue(InteriorHandle.HORIZONTAL).x > frame.cx)
		assertTrue(zero.getValue(InteriorHandle.VERTICAL).y > frame.cy)
		assertEquals(zero.values.size, zero.values.distinct().size)
		// Past the frame's edge the value holds at 2, past the centre at 0; the other values stay.
		val s = InteriorSurfaceSettings()
		assertEquals(2f, InteriorHandles.dragSurface(InteriorHandle.BULGE, frame, s, InteriorPoint(frame.cx, -500f)).bulge)
		assertEquals(0f, InteriorHandles.dragSurface(InteriorHandle.HORIZONTAL, frame, s, InteriorPoint(-500f, frame.cy)).horizontal)
		assertEquals(s.copy(press = 2f), InteriorHandles.dragSurface(InteriorHandle.PRESS, frame, s, InteriorPoint(frame.x0, frame.cy)))
	}

	@Test
	fun propagationEndsMoveAloneTheMiddleMovesBothAndTheDirectionIsNeverLost() {
		val m = InteriorMotionSettings()
		val handles = InteriorHandles.propagation(frame, m)
		assertEquals(frame.at(.5f, .05f), handles[InteriorHandle.FROM])
		val from = handles.getValue(InteriorHandle.FROM)
		val moved = InteriorHandles.dragPropagation(InteriorHandle.FROM, frame, m, from, frame.at(.2f, .3f))!!
		assertEquals(InteriorPoint(.2f, .3f), moved.from); assertEquals(m.to, moved.to)
		// An end stays in the frame.
		val clamped = InteriorHandles.dragPropagation(InteriorHandle.TO, frame, m, handles.getValue(InteriorHandle.TO), frame.at(1.5f, 2f))!!
		assertEquals(InteriorPoint(1f, 1f), clamped.to)
		// Ends closer than the least span are refused, not saved.
		assertNull(InteriorHandles.dragPropagation(InteriorHandle.FROM, frame, m, from, frame.at(.5f, .94f)))
		// The middle carries both ends and stops at the edge without changing the span.
		val middle = handles.getValue(InteriorHandle.DIRECTION)
		val shifted = InteriorHandles.dragPropagation(InteriorHandle.DIRECTION, frame, m, middle,
			InteriorPoint(middle.x + 1000f, middle.y + 1000f))!!
		assertEquals(InteriorPoint(1f, .1f), shifted.from); assertEquals(InteriorPoint(1f, 1f), shifted.to)
		near(hypot(m.to.x - m.from.x, m.to.y - m.from.y), hypot(shifted.to.x - shifted.from.x, shifted.to.y - shifted.from.y))
	}

	private fun guide(id: String, vararg xy: Float, purpose: InteriorGuidePurpose = InteriorGuidePurpose.FOLD) =
		InteriorGuide(id, (xy.indices step 2).map { InteriorPoint(xy[it], xy[it + 1]) }, purpose)

	@Test
	fun guidePointEditsKeepEveryGuideValid() {
		val guides = listOf(guide("fold0", 0f, 0f, 10f, 0f, 20f, 0f), guide("line1", 0f, 5f, 9f, 5f, purpose = InteriorGuidePurpose.TOPOLOGY))
		assertEquals(InteriorPoint(4f, 4f), InteriorGuideEdits.move(guides, 0, 1, InteriorPoint(4f, 4f))!![0].points[1])
		assertNull(InteriorGuideEdits.move(guides, 0, 1, InteriorPoint(0f, 0f)), "a point onto its neighbour")
		val inserted = InteriorGuideEdits.insert(guides, 1, 0, InteriorPoint(3f, 6f))!!
		assertEquals(listOf(InteriorPoint(0f, 5f), InteriorPoint(3f, 6f), InteriorPoint(9f, 5f)), inserted[1].points)
		assertEquals(guides[0], inserted[0])
		assertNull(InteriorGuideEdits.insert(guides, 1, 0, InteriorPoint(0f, 5f)))
		// Removing a point of a two-point guide removes the guide; equal neighbours left behind merge.
		assertEquals(listOf(guides[0]), InteriorGuideEdits.remove(guides, 1, 0))
		val bent = listOf(guide("fold0", 0f, 0f, 5f, 5f, 0f, 0f, 9f, 9f))
		assertEquals(listOf(InteriorPoint(0f, 0f), InteriorPoint(9f, 9f)), InteriorGuideEdits.remove(bent, 0, 1)[0].points)
		// A drawn line becomes a fold with the first free ID; repeats collapse, one point is not a line.
		val added = InteriorGuideEdits.add(guides, listOf(InteriorPoint(1f, 1f), InteriorPoint(1f, 1f), InteriorPoint(2f, 3f)))!!
		assertEquals("fold2", added.last().id)
		assertEquals(InteriorGuidePurpose.FOLD, added.last().purpose)
		assertEquals(2, added.last().points.size)
		assertNull(InteriorGuideEdits.add(guides, listOf(InteriorPoint(1f, 1f), InteriorPoint(1f, 1f))))
		val full = List(64) { guide("g$it", 0f, 0f, 1f, 1f) }
		assertNull(InteriorGuideEdits.add(full, listOf(InteriorPoint(0f, 0f), InteriorPoint(1f, 1f))))
		// The radius handle stands off the longest segment's middle by the radius, and reads back from there.
		val (mid, handle) = InteriorGuideEdits.radiusHandle(guides[0])
		assertEquals(InteriorPoint(5f, 0f), mid)
		near(16f, hypot(handle.x - mid.x, handle.y - mid.y), .001f)
		assertEquals(30f, InteriorGuideEdits.dragRadius(guides, 0, InteriorPoint(5f, 30f))[0].radius)
		assertEquals(1f, InteriorGuideEdits.dragRadius(guides, 0, mid)[0].radius)
	}

	@Test
	fun aDraftBackAtTheSavedValuesIsCleanAndFollowsTheDocumentAgain() {
		val p = preview()
		val target = p.authored.rig.puppet.drawables.first { it.mesh != null }.id.raw
		val overlay = NativeInteriorAuthoring.put(p, RigEditOverlay(), buildJsonObject {
			put("target", target)
			put("motion", InteriorMotionSettings(enabled = false).toJson())
		})
		val saved = overlay.interiorEffects.single()
		val guide = guide("fold0", 30f, 30f, 50f, 100f)
		val draft = io.github.psd2live.ui.state.InteriorDraft(saved)
		// A released drag whose commit has not landed yet, then another drag cancelled back to it.
		draft.guides = listOf(guide)
		assertTrue(draft.dirty)
		draft.settle(saved)
		assertTrue(draft.dirty, "values not saved yet stay unsaved")
		val landed = NativeInteriorAuthoring.put(p, overlay, NativeInteriorAuthoring.request(target, true, saved.settings, saved.motion,
			saved.sources.map { it.id.raw }, listOf(guide))).interiorEffects.single()
		draft.sync(landed)
		assertFalse(draft.dirty, "the commit landed with these values")
		// Clean again, it follows an undo.
		draft.sync(saved)
		assertEquals(emptyList(), draft.guides)
		assertFalse(draft.dirty)
	}

	@Test
	fun theCanvasMapCarriesRasterPointsOverAnyPlacementAndBack() {
		val raster = floatArrayOf(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f)
		val map = InteriorCanvasMap(raster, intArrayOf(0, 1, 2, 0, 2, 3))
		// Placed at half size, turned a quarter and moved, as a zoomed screen could show it.
		val placed = FloatArray(raster.size) { i ->
			val x = raster[i - i % 2]; val y = raster[i - i % 2 + 1]
			if (i % 2 == 0) 300f - y * .5f else 40f + x * .5f
		}
		for (p in listOf(InteriorPoint(25f, 75f), InteriorPoint(90f, 10f), InteriorPoint(130f, 50f))) {
			val (x, y) = map.place(map.bind(p)!!, placed)
			near(300f - p.y * .5f, x, .001f); near(40f + p.x * .5f, y, .001f)
			val back = map.toRaster(placed, x, y)!!
			near(p.x, back.x, .01f); near(p.y, back.y, .01f)
		}
		// A mesh drawn with no area left names no raster point, rather than failing the hover or press.
		assertNull(map.toRaster(FloatArray(raster.size) { 7f }, 7f, 7f))
		assertNull(InteriorCanvasMap(FloatArray(raster.size), intArrayOf(0, 1, 2, 0, 2, 3)).bind(InteriorPoint(1f, 1f)))
		assertTrue(map.hasArea())
		assertFalse(InteriorCanvasMap(FloatArray(raster.size), intArrayOf(0, 1, 2, 0, 2, 3)).hasArea())
		// One flat triangle does not make the mesh unusable when another spans an area.
		assertTrue(InteriorCanvasMap(floatArrayOf(0f, 0f, 1f, 0f, 2f, 0f, 0f, 3f), intArrayOf(0, 1, 2, 0, 1, 3)).hasArea())
	}

	private fun preview(): RigPreviewModel {
		val w = 96
		val h = 144
		val raster = LayerRaster(w, h, ByteArray(w * h * 4) { if (it % 4 == 3) 255.toByte() else 140.toByte() })
		val layer = WorkspaceSourceLayer(LayerId("cloth"), "cloth", "", SourceLayerKind.Raster, true, 0, LayerBounds(12, 8, w, h), 1f,
			false, LayerBlend.Normal, ChannelMask.ALL, raster, null, null, false)
		return PSD2LivePipeline().buildPreview(WorkspaceSourceArt(128, 168, listOf(layer), emptyList()),
			PipelineConfig(atlasSize = 512, meshSpacing = 16))
	}

}
