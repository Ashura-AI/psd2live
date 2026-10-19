package io.github.psd2live.ui.utils

import org.junit.jupiter.api.io.TempDir
import java.awt.Point
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopDropTargetTest {
	@TempDir lateinit var temp: Path

	@Test
	fun dropPointsScaleToComposeWindowPixels() {
		// AWT reports logical units; at 150% Compose lays out in 1.5x device pixels.
		assertEquals(Point(150, 300), DesktopDropTarget.windowPixels(Point(1100, 1200), Point(1000, 1000), 1.5, 1.5))
		assertEquals(Point(100, 200), DesktopDropTarget.windowPixels(Point(1100, 1200), Point(1000, 1000), 1.0, 1.0))
	}

	@Test
	fun aCmo3OpensAsAProject() {
		val cmo3 = temp.resolve("model.cmo3").toFile().apply { writeBytes(byteArrayOf(0)) }
		assertEquals(DesktopDropTarget.DroppedAction.OpenCmo3(cmo3), DesktopDropTarget.resolveDropAction(listOf(cmo3)))
		val project = temp.resolve("work.psd2live").toFile().apply { writeBytes(byteArrayOf(0)) }
		assertEquals(DesktopDropTarget.DroppedAction.OpenProject(project), DesktopDropTarget.resolveDropAction(listOf(cmo3, project)))
	}
}
