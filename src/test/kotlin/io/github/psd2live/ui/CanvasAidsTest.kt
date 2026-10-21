package io.github.psd2live.ui

import io.github.psd2live.application.WorkspaceAuxiliaryEdit
import io.github.psd2live.application.WorkspaceAuxiliaryEdits
import io.github.psd2live.project.CanvasGuide
import io.github.psd2live.project.WorkspaceAuxiliaryCodec
import io.github.psd2live.project.WorkspaceAuxiliaryData
import kotlinx.serialization.json.JsonObject
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CanvasAidsTest {
	@Test fun gridShowsItsMinorCellsOnlyWhenTheyAreFarEnoughApart() {
		// 100 px cells in 4: 25 px minor lines, 12.5 screen px apart at half size.
		assertEquals(GridStep(25f, 4), CanvasGrid.step(100f, 4, 0.5))
		// At a tenth they would be 2.5 px apart, so only the majors show, 10 px apart.
		assertEquals(GridStep(100f, 1), CanvasGrid.step(100f, 4, 0.1))
		// Far out even the majors double until they are 6 px apart.
		assertEquals(GridStep(400f, 1), CanvasGrid.step(100f, 4, 0.02))
	}

	@Test fun gridLinesKeepTheOriginOnAMajorLine() {
		val lines = CanvasGrid.lines(100f, 4, -30f, 110f, 1.0)
		assertEquals(listOf(-25f, 0f, 25f, 50f, 75f, 100f), lines.map { it.position })
		assertEquals(listOf(false, true, false, false, false, true), lines.map { it.major })
		assertTrue(CanvasGrid.lines(100f, 4, 10f, 5f, 1.0).isEmpty())
	}

	@Test fun gridSnapsToTheLineItDraws() {
		assertEquals(50f, CanvasGrid.nearest(47f, 100f, 4, 1.0))
		// Zoomed out the minor lines are gone, so the nearest drawn line is a major one.
		assertEquals(100f, CanvasGrid.nearest(70f, 100f, 4, 0.1))
	}

	@Test fun rulerNumbersEveryOneTwoOrFiveTimesAPowerOfTen() {
		assertEquals(100f, CanvasRuler.labelStep(1.0))
		assertEquals(50f, CanvasRuler.labelStep(2.0))
		assertEquals(500f, CanvasRuler.labelStep(0.2))
		val ticks = CanvasRuler.ticks(0f, 100f, 1.0)
		assertEquals(listOf(0f, 100f), ticks.filter { it.label }.map { it.position })
		assertEquals(6, ticks.size)
	}

	@Test fun pointerSnapsPerAxisToTheNearestLineInReach() {
		val guides = listOf(CanvasGuide("guide-1", vertical = true, position = 103f), CanvasGuide("guide-2", vertical = false, position = 40f))
		val grid = { v: Float -> Math.round(v / 50f) * 50f }
		// x: the guide at 103 is nearer than the grid line at 100; y: the guide at 40 is out of reach, the line at 50 is not.
		assertEquals(103f to 50f, CanvasSnap.snap(102f, 47f, guides, grid, tolerance = 4f))
		// Out of reach of everything, the point stays.
		assertEquals(120f to 70f, CanvasSnap.snap(120f, 70f, guides, grid, tolerance = 4f))
		// Without the grid only the guides draw it.
		assertEquals(103f to 47f, CanvasSnap.snap(102f, 47f, guides, null, tolerance = 4f))
	}

	@Test fun guidesAreHitByTheirOwnAxisAndNumberedOnward() {
		val guides = listOf(CanvasGuide("guide-1", true, 10f), CanvasGuide("guide-4", false, 20f), CanvasGuide("custom", true, 0f))
		assertEquals("guide-5", CanvasGuides.nextId(guides))
		assertEquals("guide-1", CanvasGuides.nextId(emptyList()))
		assertEquals("guide-4", CanvasGuides.hit(guides, 50f, 22f, 3f)?.id)
		assertNull(CanvasGuides.hit(guides, 50f, 30f, 3f))
		val moved = CanvasGuides.put(guides, CanvasGuide("guide-1", true, 12f))
		assertEquals(listOf(12f, 20f, 0f), moved.map { it.position })
		assertEquals(4, CanvasGuides.put(guides, CanvasGuide("guide-5", false, 1f)).size)
	}

	@Test fun guidesSaveAsProjectDataAndAreValidated() {
		val guides = listOf(CanvasGuide("guide-1", true, 12.5f), CanvasGuide("guide-2", false, -3f))
		val next = WorkspaceAuxiliaryEdits.apply(WorkspaceAuxiliaryData(), WorkspaceAuxiliaryEdit.SetCanvasGuides(guides), emptyList(), emptySet())
		assertEquals(guides, next.canvasGuides)
		assertEquals(next, WorkspaceAuxiliaryCodec.decode(WorkspaceAuxiliaryCodec.encode(next)))
		// Projects saved before guides read none.
		assertEquals(emptyList(), WorkspaceAuxiliaryCodec.decode(JsonObject(emptyMap())).canvasGuides)
		assertFailsWith<IllegalArgumentException> {
			WorkspaceAuxiliaryEdits.apply(next, WorkspaceAuxiliaryEdit.SetCanvasGuides(guides + guides.first()), emptyList(), emptySet())
		}
		assertFailsWith<IllegalArgumentException> {
			WorkspaceAuxiliaryEdits.apply(next, WorkspaceAuxiliaryEdit.SetCanvasGuides(listOf(CanvasGuide("g", true, Float.NaN))), emptyList(), emptySet())
		}
	}

	@Test fun randomPoseStaysInRangeAroundRestAndLeavesLocksAlone() {
		val angle = Parameter(ParameterId("ParamAngleX"), "Angle X", -30f, 30f, 0f)
		val eye = Parameter(ParameterId("ParamEyeLOpen"), "Eye", 0f, 1f, 1f)
		val fixed = Parameter(ParameterId("ParamFixed"), "Fixed", 2f, 2f, 2f)
		val locked = Parameter(ParameterId("ParamLocked"), "Locked", -1f, 1f, 0f)
		val parameters = listOf(angle, eye, fixed, locked)
		val random = Random(7)
		repeat(200) {
			val pose = RandomPose.sample(parameters, null, setOf(locked.id), 0.5f, random)
			assertEquals(setOf(angle.id, eye.id), pose.keys)
			assertTrue(pose.getValue(angle.id) in -15f..15f)
			assertTrue(pose.getValue(eye.id) in 0.5f..1f)
		}
		assertEquals(setOf(eye.id), RandomPose.sample(parameters, setOf(eye.id), emptySet(), 1f, random).keys)
		// No reach keeps every parameter at rest.
		assertEquals(mapOf(angle.id to 0f, eye.id to 1f), RandomPose.sample(parameters, setOf(angle.id, eye.id), emptySet(), 0f, random))
	}

	@Test fun onionKeysAreTheNearestOnEachSide() {
		val keys = listOf(-30f, -15f, 0f, 15f, 30f)
		assertEquals(listOf(OnionGhost(-1, 0f), OnionGhost(-2, -15f), OnionGhost(1, 30f)),
			OnionSkin.keyGhosts(keys, 15f, before = 2, after = 3))
		// Between keys, the neighbours are the keys either side.
		assertEquals(listOf(OnionGhost(-1, 0f), OnionGhost(1, 15f)), OnionSkin.keyGhosts(keys, 7f, 1, 1))
		assertEquals(emptyList(), OnionSkin.keyGhosts(keys, 0f, 0, 0))
	}

	@Test fun onionFramesStepAroundThePlayheadAndWrapOnlyWhenLooping() {
		val frames = OnionSkin.frameGhosts(playhead = 0.1f, fps = 30f, step = 3, before = 2, after = 1, duration = 1f, loop = false)
		assertEquals(listOf(-1, 1), frames.map { it.offset })
		assertEquals(0f, frames[0].value, 1e-5f)
		assertEquals(0.2f, frames[1].value, 1e-5f)
		val looped = OnionSkin.frameGhosts(0.1f, 30f, 3, 2, 1, 1f, loop = true)
		assertEquals(listOf(-1, -2, 1), looped.map { it.offset })
		assertEquals(0.9f, looped[1].value, 1e-5f)
		assertTrue(OnionSkin.frameGhosts(0.1f, 0f, 1, 1, 1, 1f, false).isEmpty())
	}

	@Test fun onionGhostsFadeWithDistance() {
		assertEquals(0.3f, OnionSkin.alpha(-1, 3, 0.3f), 1e-6f)
		assertEquals(0.1f, OnionSkin.alpha(3, 3, 0.3f), 1e-6f)
	}

	@Test fun aidOptionsAreKeptToWhatTheCanvasDraws() {
		val wild = CanvasAidOptions(gridSpacing = 0f, gridSubdivisions = 99, onionBefore = -2, onionAfter = 40, onionOpacity = Float.NaN,
			onionFrameStep = 0).normalized()
		assertEquals(CanvasAidOptions.MIN_GRID_SPACING, wild.gridSpacing)
		assertEquals(CanvasAidOptions.MAX_GRID_SUBDIVISIONS, wild.gridSubdivisions)
		assertEquals(0, wild.onionBefore)
		assertEquals(CanvasAidOptions.MAX_ONION_GHOSTS, wild.onionAfter)
		assertEquals(0.35f, wild.onionOpacity)
		assertEquals(1, wild.onionFrameStep)
		assertTrue(!CanvasAidOptions(snap = true).snaps)
		assertTrue(CanvasAidOptions(snap = true, showGuides = true).snaps)
	}
}
