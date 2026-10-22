package io.github.psd2live.tools

import io.github.psd2live.core.Bounds
import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.SeeThroughLayers
import kotlin.test.Test

/**
 * The features shown through the bangs on PSD2LIVE_SAMPLE's face: off, brows only, brows and eyes (the default), at
 * full opacity, turned and with the brows raised, to check the copies sit over the bangs, the eyes' window clips to
 * them, and both move with their originals. Run with PSD2LIVE_TOOLS=1; writes build/tools/see-through/<sample>.png.
 */
class SeeThroughPreviewTool {
	@Test fun renderSeeThrough() {
		requireTools()
		val sample = Sample.fromEnvironment()
		val on = PSD2LivePipeline().buildPreview(sample.path)
		fun with(config: io.github.psd2live.core.PipelineConfig) = PSD2LivePipeline().buildPreview(on.analysis, config)
		val off = with(on.config.copy(seeThroughBrows = false, seeThroughEyes = false))
		val full = with(on.config.copy(seeThroughOpacity = 1f))
		val brows = with(on.config.copy(seeThroughEyes = false))
		for ((label, model) in listOf("brows" to brows, "default" to on)) {
			for (copy in model.rig.puppet.drawables.filter { it.id.raw in model.rig.seeThroughCopies })
				println("$label ${copy.id.raw}: order ${copy.drawOrder}, opacity ${copy.opacity}, masks ${copy.maskedBy.map { it.raw }}")
			for ((bang, parts) in model.rig.seeThroughWindows) println("$label window in $bang: $parts")
		}
		val rig = on.rig
		val rect = Bounds(rig.faceCenterX - rig.faceRadiusX * 1.7f, rig.faceCenterY - rig.faceRadiusY * 1.9f,
			rig.faceCenterX + rig.faceRadiusX * 1.7f, rig.faceCenterY + rig.faceRadiusY * 1.1f)
		val browUp = on.rig.puppet.parameters.filter { it.id.raw.startsWith("ParamBrow") && it.id.raw.endsWith("Y") }.associate { it.id.raw to it.max }
		sheet(listOf(
			"Off" to Renderer(off, 420).render(emptyMap(), rect),
			"Brows only" to Renderer(brows, 420).render(emptyMap(), rect),
			"Brows and eyes (default)" to Renderer(on, 420).render(emptyMap(), rect),
			"Opacity 1" to Renderer(full, 420).render(emptyMap(), rect),
			"Angle X 30" to Renderer(on, 420).render(mapOf("ParamAngleX" to 30f), rect),
			"Angle X -30" to Renderer(on, 420).render(mapOf("ParamAngleX" to -30f), rect),
			"Angle Y 30" to Renderer(on, 420).render(mapOf("ParamAngleY" to 30f), rect),
			"Brows up" to Renderer(full, 420).render(browUp, rect),
		), output("see-through").resolve("${sample.name}.png"), columns = 4)
		// Close up on the brows and eyes.
		val features = on.analysis.layers.filter { it.semantic.tag in setOf(io.github.psd2live.core.SemanticTag.EYEBROW,
			io.github.psd2live.core.SemanticTag.EYELASH, io.github.psd2live.core.SemanticTag.EYEWHITE) && it.opaquePixels > 0 }.map { it.bounds }
		if (features.isEmpty()) return
		val pad = rig.faceRadiusX * 0.25f
		val close = Bounds(features.minOf { it.left } - pad, features.minOf { it.top } - pad, features.maxOf { it.right } + pad, features.maxOf { it.bottom } + pad)
		sheet(listOf(
			"Off" to Renderer(off, 640).render(emptyMap(), close),
			"Brows only" to Renderer(brows, 640).render(emptyMap(), close),
			"Brows and eyes (default)" to Renderer(on, 640).render(emptyMap(), close),
			"Opacity 1" to Renderer(full, 640).render(emptyMap(), close),
			"Angle X 30, opacity 1" to Renderer(full, 640).render(mapOf("ParamAngleX" to 30f), close),
			"Brows up, opacity 1" to Renderer(full, 640).render(browUp, close),
			"Eyes half shut, opacity 1" to Renderer(full, 640).render(mapOf("ParamEyeLOpen" to 0.5f, "ParamEyeROpen" to 0.5f), close),
			"Eyes shut, opacity 1" to Renderer(full, 640).render(mapOf("ParamEyeLOpen" to 0f, "ParamEyeROpen" to 0f), close),
		), output("see-through").resolve("${sample.name}-close.png"), columns = 2)
	}
}
