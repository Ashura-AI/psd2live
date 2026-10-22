package io.github.psd2live.core

import io.github.psd2live.project.ProjectFormatV2
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RigHitAreasTest {
	@TempDir lateinit var temp: Path

	/** A unit square of two triangles at [x], [y]. */
	private fun square(x: Float, y: Float) = floatArrayOf(x, y, x + 1f, y, x + 1f, y + 1f, x, y + 1f)
	private val quad = intArrayOf(0, 1, 2, 0, 2, 3)

	@Test fun aPointIsCoveredInsideAndOnTheEdgesOfATriangle() {
		val points = square(0f, 0f)
		assertTrue(RigHitAreas.covers(points, quad, 0.5f, 0.5f))
		assertTrue(RigHitAreas.covers(points, quad, 1f, 0.5f))
		assertTrue(RigHitAreas.covers(points, quad, 0f, 0f))
		assertFalse(RigHitAreas.covers(points, quad, 1.5f, 0.5f))
		assertFalse(RigHitAreas.covers(points, intArrayOf(), 0.5f, 0.5f))
	}

	@Test fun theFirstAreaWithAShownMeshAtThePointWins() {
		val head = RigHitArea("HitAreaHead", "Head", listOf("face"))
		val body = RigHitArea("HitAreaBody", "Body", listOf("top", "face"))
		val positions = mapOf("face" to square(0f, 0f), "top" to square(0f, 2f))
		val indices = mapOf("face" to quad, "top" to quad)
		val shown = mapOf("face" to 1f, "top" to 1f)
		assertEquals(head, RigHitAreas.areaAt(listOf(head, body), positions, indices, shown, 0.5f, 0.5f))
		assertEquals(body, RigHitAreas.areaAt(listOf(body, head), positions, indices, shown, 0.5f, 0.5f))
		assertEquals(body, RigHitAreas.areaAt(listOf(head, body), positions, indices, shown, 0.5f, 2.5f))
		assertNull(RigHitAreas.areaAt(listOf(head, body), positions, indices, shown, 5f, 5f))
		// A fully transparent mesh, or one not evaluated (hidden), is not hit: the runtime's rule.
		assertNull(RigHitAreas.areaAt(listOf(head, body), positions, indices, shown + ("face" to 0f), 0.5f, 0.5f))
		assertEquals(body, RigHitAreas.areaAt(listOf(head, body), positions, indices, shown + ("face" to 0f), 0.5f, 2.5f))
		assertNull(RigHitAreas.areaAt(listOf(head), positions - "face", indices, shown, 0.5f, 0.5f))
	}

	@Test fun anAreaNeedsAnIdOfCubismsAlphabetANameAndDistinctMeshes() {
		assertFailsWith<IllegalArgumentException> { RigHitArea("1Head", "Head", listOf("face")) }
		assertFailsWith<IllegalArgumentException> { RigHitArea("Hit-Head", "Head", listOf("face")) }
		assertFailsWith<IllegalArgumentException> { RigHitArea("HitAreaHead", " ", listOf("face")) }
		assertFailsWith<IllegalArgumentException> { RigHitArea("HitAreaHead", "Head", emptyList()) }
		assertFailsWith<IllegalArgumentException> { RigHitArea("HitAreaHead", "Head", listOf("face", "face")) }
		val head = RigHitArea("HitAreaHead", "Head", listOf("face"))
		assertFailsWith<IllegalArgumentException> { RigEditOverlay(hitAreas = listOf(head, head.copy(name = "Again"))) }
	}

	@Test fun meshReplacementsMapOneMeshToManyInPlace() {
		val areas = listOf(RigHitArea("HitAreaHead", "Head", listOf("face", "ear")), RigHitArea("HitAreaBody", "Body", listOf("top")))
		// A split: the pieces in the original's place.
		assertEquals(listOf(listOf("face_l", "face_r", "ear"), listOf("top")),
			RigHitAreas.replaceMeshes(areas, mapOf("face" to listOf("face_l", "face_r"))).map { it.meshes })
		// A deletion: out of the area, and an area left without meshes goes.
		assertEquals(listOf("HitAreaHead"), RigHitAreas.replaceMeshes(areas, mapOf("top" to emptyList())).map { it.id })
		// An ID conversion: one new ID; two meshes reaching one ID keep it once.
		assertEquals(listOf("Face01", "ear"), RigHitAreas.renameMeshes(areas, mapOf("face" to "Face01")).first().meshes)
		assertEquals(listOf("ear"), RigHitAreas.renameMeshes(areas, mapOf("face" to "ear")).first().meshes)
		assertTrue(RigHitAreas.replaceMeshes(areas, mapOf("other" to listOf("x"))) === areas)
	}

	@Test fun theNextIdIsTheFirstFreeNumber() {
		assertEquals("HitArea1", RigHitAreas.nextId(emptyList()))
		assertEquals("HitArea2", RigHitAreas.nextId(listOf(RigHitArea("HitArea1", "1", listOf("a")), RigHitArea("HitArea3", "3", listOf("b")))))
	}

	@Test fun areasRoundTripThroughJsonAndTheArchiveNodes() {
		val areas = listOf(RigHitArea("HitAreaHead", "头部", listOf("face")), RigHitArea("HitAreaBody", "Body", listOf("top", "skirt")))
		assertEquals(areas, areas.map(RigHitAreas::toJson).map(RigHitAreas::fromJson))
		val snapshot = buildJsonObject {
			putJsonObject("settings") { put("atlasSize", 256) }
			putJsonObject("rigEdits") {
				putJsonArray("authoringJournal") {}
				putJsonArray("motions") {}
				put("hitAreas", JsonArray(areas.map(RigHitAreas::toJson)))
			}
		}
		val index = ProjectFormatV2.split(temp, snapshot)
		assertEquals(snapshot, ProjectFormatV2.join(temp, index))
		// The rig node holding them is schema 5, which older readers refuse rather than drop them; without them it stays 1.
		val rig = ProjectFormatV2.schemaOf("rig", buildJsonObject { putJsonObject("rigEdits") { put("hitAreas", JsonArray(areas.map(RigHitAreas::toJson))) } })
		assertEquals(ProjectFormatV2.NODE_SCHEMA_HIT_AREAS, rig)
		assertEquals(ProjectFormatV2.NODE_SCHEMA, ProjectFormatV2.schemaOf("rig", buildJsonObject { putJsonObject("rigEdits") { putJsonArray("motions") {} } }))
		// The areas are part of the rig definitions, not a node of their own.
		assertEquals(setOf("settings", "rig", "journal", "document"), index.getValue("nodes").jsonObject.keys)
	}
}
