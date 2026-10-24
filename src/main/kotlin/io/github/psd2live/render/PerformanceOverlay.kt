package io.github.psd2live.render

import io.github.psd2live.i18n.tr
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.Paint
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Rect
import org.jetbrains.skia.paragraph.FontCollection
import org.jetbrains.skia.paragraph.Paragraph
import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.jetbrains.skia.paragraph.ParagraphStyle
import org.jetbrains.skia.paragraph.TextStyle

/**
 * A window's last frames as the performance overlay shows them: the time between frame starts and how long the
 * UI thread spent rendering each (Compose's recording and its replay; the buffer swap and the GPU's own work come
 * after). Touched only on the thread that renders the window.
 */
internal class FrameStats(private val capacity: Int = 120) {
	private val intervals = FloatArray(capacity)
	private val renders = FloatArray(capacity)
	private var next = 0
	private var lastStart = 0L
	/** How many of the slots hold a frame. */
	var count = 0
		private set

	/** A frame that started rendering at [start] and finished at [end] ([System.nanoTime]). */
	fun record(start: Long, end: Long) {
		if (lastStart != 0L) {
			val gap = (start - lastStart) / 1e6f
			// A pause (nothing to draw) is not a slow frame: the window draws only when something changed.
			if (gap < 250f) {
				intervals[next] = gap
				renders[next] = (end - start) / 1e6f
				next = (next + 1) % capacity
				if (count < capacity) count++
			}
		}
		lastStart = start
	}

	/** The frame intervals, oldest first. */
	fun intervals(): FloatArray = ordered(intervals)

	fun renders(): FloatArray = ordered(renders)

	private fun ordered(values: FloatArray) = FloatArray(count) { values[(next - count + it + capacity) % capacity] }
}

/**
 * The frame rate and frame times drawn over a window's top right corner when the graphics settings ask for it
 * ([GraphicsOptions.performanceOverlay]). Drawn by the window's render delegate straight onto its canvas after
 * everything else, not through Compose: showing it adds no recomposition and no redraw, and a window with nothing
 * changing keeps its last numbers.
 */
internal object PerformanceOverlay {
	/** Colours from the theme ([ui.theme.ToolColors]), handed over by the UI as it changes; ARGB. */
	class Palette(val background: Int, val border: Int, val text: Int, val muted: Int, val accent: Int, val warning: Int)

	@Volatile var palette = Palette(0xE6323438.toInt(), 0xFF3E4147.toInt(), 0xFFDFE1E5.toInt(), 0xFF868A91.toInt(), 0xFF4B7EE8.toInt(), 0xFFD69D36.toInt())

	/** The system's fonts with per-glyph fallback, as Compose lays text out: the labels draw in every language. */
	private val fonts: FontCollection by lazy { FontCollection().setDefaultFontManager(FontMgr.default) }

	private fun line(text: String, size: Float, color: Int): Paragraph =
		ParagraphBuilder(ParagraphStyle(), fonts).use { builder ->
			builder.pushStyle(TextStyle().setFontSize(size).setColor(color).setFontFamilies(arrayOf("sans-serif")))
			builder.addText(text)
			builder.build()
		}.apply { layout(Float.POSITIVE_INFINITY) }

	fun draw(canvas: Canvas, width: Int, stats: FrameStats, scale: Float, renderer: String) {
		val intervals = stats.intervals()
		if (intervals.isEmpty()) return
		val renders = stats.renders()
		val colors = palette
		val sorted = intervals.sortedArray()
		val median = sorted[sorted.size / 2]
		val lines = listOf(
			tr("settings.graphics.fps", "%.0f".format(1000f / intervals.average().toFloat().coerceAtLeast(0.01f))),
			tr("overlay.frameTime", "%.1f".format(intervals.average()), "%.1f".format(sorted.last())),
			tr("overlay.uiThread", "%.1f".format(renders.average()), "%.1f".format(renders.max())),
			// The card's name; the driver's version string after it is long and says little here.
			renderer.substringBefore(" | "),
		)
		val paragraphs = lines.mapIndexed { i, text -> line(text, 11f * scale, if (i == 0) colors.text else colors.muted) }
		val pad = 8f * scale
		val lineHeight = 15f * scale
		val graphHeight = 28f * scale
		val panelWidth = maxOf(190f * scale, paragraphs.maxOf { it.maxIntrinsicWidth } + pad * 2)
		val panelHeight = pad * 2 + lineHeight * lines.size + graphHeight + 4f * scale
		val left = width - panelWidth - 10f * scale
		val top = 10f * scale
		Paint().use { paint ->
			paint.isAntiAlias = true
			paint.color = colors.background
			val panel = RRect.makeXYWH(left, top, panelWidth, panelHeight, 6f * scale)
			canvas.drawRRect(panel, paint)
			paint.mode = org.jetbrains.skia.PaintMode.STROKE
			paint.strokeWidth = scale
			paint.color = colors.border
			canvas.drawRRect(panel, paint)
			paint.mode = org.jetbrains.skia.PaintMode.FILL
			for ((i, paragraph) in paragraphs.withIndex()) paragraph.paint(canvas, left + pad, top + pad + lineHeight * i)
			// One bar per frame, as tall as its interval against twice the median; a frame half again as long as
			// the median (a missed refresh) shows in the warning colour.
			val graphTop = top + pad + lineHeight * lines.size + 4f * scale
			val barWidth = (panelWidth - pad * 2) / intervals.size.coerceAtLeast(60)
			val ceiling = median * 2f
			for ((i, value) in intervals.withIndex()) {
				val h = (value / ceiling).coerceIn(0.04f, 1f) * graphHeight
				paint.color = if (value > median * 1.5f) colors.warning else colors.accent
				canvas.drawRect(Rect.makeXYWH(left + pad + i * barWidth, graphTop + graphHeight - h, maxOf(barWidth - scale * 0.5f, scale * 0.5f), h), paint)
			}
		}
		paragraphs.forEach { it.close() }
	}
}
