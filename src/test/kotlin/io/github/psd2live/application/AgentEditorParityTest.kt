package io.github.psd2live.application

import io.github.psd2live.core.RigGeometryTools
import kotlinx.serialization.json.*
import java.awt.image.BufferedImage
import kotlin.test.*

/** Editor gestures an agent asks for in a request: soft selection in rig_deform and a mirrored paint stroke. */
class AgentEditorParityTest {
	@Test fun softSelectionCarriesThePointsAroundThePickedOnes() {
		// A row of five points along x in a 0..1 domain; point 0 is picked and moved one bounds-width right.
		val domain = floatArrayOf(0f, 0f, 0.1f, 0f, 0.2f, 0f, 0.5f, 0f, 1f, 0f)
		val geometry = RigGeometryTools.Geometry(domain.copyOf(), domain.copyOf(), domain, null, null, emptyList(), 1, "row", null)
		val moved = RigGeometryTools.transform(geometry, buildJsonArray { add(buildJsonObject {
			put("type", "translate"); putJsonArray("delta") { add(1f); add(0f) }
		}) }, buildJsonObject {
			putJsonArray("indices") { add(0) }; put("soft_radius", 0.3f); put("falloff", "linear")
		})
		val shift = (0 until 5).map { moved[it * 2] - domain[it * 2] }
		assertEquals(1f, shift[0], 1e-4f, "the picked point moves fully")
		assertEquals(2f / 3f, shift[1], 1e-3f, "a point a third of the way out follows by the linear falloff")
		assertTrue(shift[2] in 0.01f..shift[1], "a farther point follows less: $shift")
		assertEquals(0f, shift[3], 1e-6f); assertEquals(0f, shift[4], 1e-6f)
		assertFails("soft_radius without indices") {
			RigGeometryTools.transform(geometry, buildJsonArray { add(buildJsonObject { put("type", "translate"); putJsonArray("delta") { add(1f); add(0f) } }) },
				buildJsonObject { put("soft_radius", 0.3f) })
		}
	}

	@Test fun aSymmetricStrokeLandsOnBothHalvesOnce() {
		fun stroke(symmetry: String?, points: List<Pair<Int, Int>>): BufferedImage {
			val image = BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB)
			paintRasterGesture(image, buildJsonObject {
				put("mode", "brush"); put("radius", 3f); put("opacity", 0.5f)
				putJsonArray("color") { add(255); add(0); add(0); add(255) }
				putJsonArray("points") { points.forEach { (x, y) -> add(buildJsonArray { add(x); add(y) }) } }
				symmetry?.let { put("symmetry", it) }
			}, symmetryAxes = 32f to 16f)
			return image
		}
		fun alpha(image: BufferedImage, x: Int, y: Int) = image.getRGB(x, y) ushr 24
		val mirrored = stroke("vertical", listOf(10 to 16))
		assertTrue(alpha(mirrored, 10, 16) > 0 && alpha(mirrored, 54, 16) == alpha(mirrored, 10, 16), "the mirror image did not land alike")
		// A stroke across the axis meets its own image there; the overlap lands once, not twice as dark.
		val crossing = stroke("vertical", listOf(28 to 16, 36 to 16))
		val plain = stroke(null, listOf(28 to 16, 36 to 16))
		assertEquals(alpha(plain, 32, 16), alpha(crossing, 32, 16), "the overlap on the axis landed twice")
		assertEquals(0, alpha(stroke(null, listOf(10 to 16)), 54, 16))
	}
}
