package io.github.psd2live.ui.state

import io.github.psd2live.application.ImageLayerInsertion
import io.github.psd2live.ui.utils.DesktopDropTarget
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileDropRouterTest {
	@TempDir lateinit var temp: Path

	private fun file(name: String): File = temp.resolve(name).toFile().apply { writeBytes(byteArrayOf(0)) }

	private fun router() = FileDropRouter(
		fallback = { carried -> carried.opens?.let { FileDropIntent.Open(it, "open") } },
		hint = { "hint" },
	)

	/** A zone covering x in [from, to) that takes images. */
	private fun FileDropRouter.images(id: String, from: Float, to: Float, priority: Int = 0, intent: FileDropIntent) =
		register(id, priority) { carried, x, _ -> intent.takeIf { x >= from && x < to && carried.rasters.isNotEmpty() } }

	@Test
	fun theZoneUnderThePointerDecides() {
		val router = router()
		val tree = FileDropIntent.InsertLayers(ImageLayerInsertion("Head"), "tree", TreeDropMark.INTO)
		val canvas = FileDropIntent.PlaceOnCanvas(1f to 2f, "canvas")
		router.images("tree", 0f, 100f, intent = tree)
		router.images("canvas", 100f, 500f, intent = canvas)
		router.images("slot", 200f, 300f, priority = 10, intent = FileDropIntent.SetReference(ReferenceMode.FOREGROUND, "slot"))
		val png = listOf(file("a.png"))
		assertTrue(router.move(png, 50f, 0f))
		assertEquals(tree, router.drag.value?.intent)
		assertEquals("tree", router.drag.value?.zone)
		router.move(png, 150f, 0f)
		assertEquals(canvas, router.drag.value?.intent)
		router.move(png, 250f, 0f)
		assertEquals("slot", router.drag.value?.zone)
		assertIs<FileDropIntent.SetReference>(router.drop(png, 250f, 0f))
		assertNull(router.drag.value)
	}

	@Test
	fun filesNoZoneTakesFallBackToOpeningOrAHint() {
		val router = router()
		router.images("tree", 0f, 100f, intent = FileDropIntent.InsertLayers(ImageLayerInsertion(), "tree", TreeDropMark.ROOT))
		// A PSD over the tree opens, as anywhere else no zone takes it.
		val psd = listOf(file("art.psd"))
		assertTrue(router.move(psd, 50f, 0f))
		assertIs<FileDropIntent.Open>(router.drag.value?.intent)
		assertEquals(DesktopDropTarget.DroppedAction.OpenPsd(psd.single()), (router.drop(psd, 50f, 0f) as FileDropIntent.Open).action)
		// An image off every zone: nothing to do, and the pointer says where to go instead.
		val png = listOf(file("a.png"))
		assertFalse(router.move(png, 900f, 0f))
		assertNull(router.drag.value?.intent)
		assertEquals("hint", router.drag.value?.hint)
		assertNull(router.drop(png, 900f, 0f))
	}

}
