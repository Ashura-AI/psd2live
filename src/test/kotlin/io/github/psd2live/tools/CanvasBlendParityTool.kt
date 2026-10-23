package io.github.psd2live.tools

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.render.*
import io.github.psd2live.scenario.Characters
import io.github.psd2live.ui.SkiaRigPainter
import org.jetbrains.skia.*
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.Drawable
import kotlin.test.*

/**
 * The edit canvas's GPU and Skia painters agree on masks, colours and every blend: one part of a generated head
 * takes each variant in turn and both painters draw it.
 * PSD2LIVE_TOOLS=1 ./gradlew test --tests '*CanvasBlendParityTool'; images in build/tools/canvas-blend-parity.
 */
class CanvasBlendParityTool {
	@Test fun gpuAndSkiaPaintersAgree() {
		requireTools()
		val document = Characters.head(false)
		val model = kotlinx.coroutines.runBlocking { WorkspacePreviewBuilder().build(document) }
		val puppet = model.rig.puppet
		val pose = RigCanvasSupport.evaluateExact(model)
		val shown = puppet.drawables.filter { it.mesh != null && it.id in pose.worldPositions }
			.sortedBy { RigCanvasSupport.displayOrder(model, it, pose, emptyMap()) }
		// The frontmost part over the largest one, which masks it in the mask variants.
		val part = shown.last()
		val under = shown.maxBy { (it.mesh?.positions?.size ?: 0) }
		val variants = buildList<Pair<String, (Drawable) -> Drawable>> {
			for (blend in org.umamo.runtime.model.BlendMode.entries) add("blend-$blend" to { d -> d.copy(blendMode = blend) })
			for (alpha in AlphaBlendMode.entries) add("alpha-$alpha" to { d -> d.copy(blendMode = org.umamo.runtime.model.BlendMode.Overlay, alphaBlendMode = alpha) })
			add("colours" to { d -> d.copy(multiplyColor = ColorRgb(0.4f, 0.9f, 0.6f), screenColor = ColorRgb(0.1f, 0f, 0.3f)) })
			add("mask" to { d -> d.copy(maskedBy = listOf(under.id)) })
			add("mask-inverted" to { d -> d.copy(maskedBy = listOf(under.id), invertMask = true) })
			add("mask-multiply" to { d -> d.copy(maskedBy = listOf(under.id), blendMode = org.umamo.runtime.model.BlendMode.Multiply) })
		}
		val size = 256
		val canvasWidth = model.analysis.source.widthPx.toFloat()
		val canvasHeight = model.analysis.source.heightPx.toFloat()
		val scale = size / maxOf(canvasWidth, canvasHeight).toDouble()
		val viewport = CanvasViewport(scale, 0.0, 0.0, canvasWidth, canvasHeight)
		val out = output("canvas-blend-parity")
		val report = StringBuilder()
		val failures = ArrayList<String>()
		assertTrue(glfwInit())
		try {
			glfwDefaultWindowHints()
			glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
			glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
			glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
			glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
			val window = glfwCreateWindow(size, size, "Blend parity", 0, 0)
			assertNotEquals(0L, window)
			try {
				glfwMakeContextCurrent(window)
				GL.createCapabilities()
				GlCanvasRenderer(mipmaps = false).use { renderer ->
					SkiaRigPainter(model.atlas).use { painter ->
						for ((name, change) in variants) {
							val changed = puppet.copy(drawables = puppet.drawables.map { if (it.id == part.id) change(it) else it })
							val rig = model.rig.copy(puppet = changed)
							val current = model.copy(rig = rig, sources = PreviewRigSources.of(rig))
							val geometry = RigCanvasSupport.evaluateExact(current)
							val draws = ArtworkDrawList.build(current, geometry, ArtworkOptions())
							val gpu = renderer.render("parity", CanvasScene(size, size, viewport, current, geometry, draws)).use { bitmap ->
								IntArray(size * size) { bitmap.getColor(it % size, it / size) }
							}
							val skia = Surface.makeRaster(ImageInfo(size, size, ColorType.RGBA_8888, ColorAlphaType.PREMUL)).use { surface ->
								painter.paint(surface.canvas, current, geometry, viewport, draws)
								surface.makeImageSnapshot().use { image ->
									Bitmap().use { bitmap ->
										bitmap.allocPixels(image.imageInfo)
										image.readPixels(bitmap, 0, 0)
										IntArray(size * size) { bitmap.getColor(it % size, it / size) }
									}
								}
							}
							// Silhouettes cover differently (the GPU frame is not antialiased), so only pixels whose
							// neighbourhood is flat in both frames count.
							var worst = 0; var differing = 0; var compared = 0
							for (y in 1 until size - 1) for (x in 1 until size - 1) {
								val i = y * size + x
								val flat = listOf(-1, 1, -size, size).all { o ->
									(0 until 4).all { abs8(gpu[i], gpu[i + o], it * 8) <= 6 && abs8(skia[i], skia[i + o], it * 8) <= 6 }
								}
								if (!flat) continue
								compared++
								val d = (0 until 4).maxOf { abs8(gpu[i], skia[i], it * 8) }
								worst = maxOf(worst, d)
								if (d > 4) differing++
							}
							report.appendLine("$name: $compared flat pixels, worst $worst, off by more than 4: $differing")
							save(out.resolve("$name-gpu.png"), gpu, size)
							save(out.resolve("$name-skia.png"), skia, size)
							// The painters sample a tile's last texels a little differently, which a blend such as lighten
							// shows along the part's outline: a band a few pixels wide may stray.
							if (differing > compared / 100) failures += "$name: $differing pixels differ (worst $worst)"
						}
						assertEquals(GL11.GL_NO_ERROR, GL11.glGetError())
					}
				}
			} finally { glfwDestroyWindow(window); GL.setCapabilities(null) }
		} finally { glfwTerminate() }
		out.resolve("report.txt").writeText(report.toString())
		assertTrue(failures.isEmpty(), failures.joinToString("\n"))
	}

	/** One channel's difference, premultiplied: a transparent pixel's colour does not count. */
	private fun abs8(a: Int, b: Int, shift: Int): Int {
		fun premultiplied(c: Int) = if (shift == 24) c ushr 24 else (c ushr shift and 0xff) * (c ushr 24) / 255
		return kotlin.math.abs(premultiplied(a) - premultiplied(b))
	}

	private fun save(file: java.io.File, argb: IntArray, size: Int) {
		val image = java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB)
		image.setRGB(0, 0, size, size, argb, 0, size)
		javax.imageio.ImageIO.write(image, "png", file)
	}
}
