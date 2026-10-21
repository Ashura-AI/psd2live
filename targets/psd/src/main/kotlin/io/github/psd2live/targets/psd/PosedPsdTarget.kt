package io.github.psd2live.targets.psd

import io.github.psd2live.format.compile.*
import io.github.psd2live.format.model.AlphaBlend
import io.github.psd2live.format.model.ColorBlend
import io.github.psd2live.format.model.RigIR
import org.umamo.format.art.ChannelMask
import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceLayer
import org.umamo.format.psd.PsdWriter

/**
 * A layered PSD of the rig at one pose: every visible mesh rendered deformed onto its own layer, bottom to
 * top in rest draw order, so a pose can go back to the art workflow. Each mesh is rendered on its own as a normal
 * mesh, and its color blend becomes its layer's blend, so the PSD composites the layer as the rig composites the mesh.
 *
 * Settings: `clip` and `time` (seconds) pose the rig from a clip, or `pose` sets parameters directly
 * (`ParamAngleX=20,ParamEyeLOpen=0`; it overrides the clip); `scale` (0.25..2, default 1) sets the resolution
 * against the canvas.
 */
public class PosedPsdTarget(private val renderer: FrameRenderer) : ExportTarget {
	override val id: String = "psd-pose"
	override val family: TargetFamily = TargetFamily.TIMELINE
	override val description: String = "Layered PSD at a pose"
	override val capabilities: CapabilityProfile = CapabilityProfile(structure = false)
	override val settings: List<TargetSetting> = listOf(
		TargetSetting.ClipChoice("clip", rest = true), TargetSetting.Number("time", 0.0, 0.0, 3600.0, 0.1, 2),
		TargetSetting.Text("pose", "ParamAngleX=20,ParamEyeLOpen=0"), TargetSetting.Number("scale", 1.0, 0.25, 2.0, 0.25, 2),
	)

	override fun plan(ir: RigIR, options: ExportOptions): LoweredExport {
		val clip = options.setting("clip")?.let { wanted -> ir.clips.firstOrNull { it.id == wanted } ?: throw IllegalArgumentException("Unknown clip: $wanted") }
		val time = options.float("time", 0f)
		val pose = (clip?.let { ClipSampler.valuesAt(it, time) } ?: emptyMap()) + parsePose(options.setting("pose"))
		val known = ir.parameters.mapTo(HashSet()) { it.id }
		require(pose.keys.all(known::contains)) { "Unknown parameters in pose: ${(pose.keys - known).sorted()}" }
		val scale = options.float("scale", 1f)
		require(scale in 0.25f..2f) { "Scale must be within 0.25..2" }
		val width = (ir.canvas.width * scale).toInt().coerceAtLeast(1)
		val height = (ir.canvas.height * scale).toInt().coerceAtLeast(1)
		val frame = FrameSpec(0f, 0f, ir.canvas.width, ir.canvas.height, width, height)
		val meshes = ir.meshes.filter { it.visible && it.geometry != null }.sortedBy { it.drawOrder }
		// On the empty backdrop of a mesh rendered alone a W3C blend with over alpha draws the mesh's own pixels, as normal
		// does; Cubism's add and multiply before 5.3 and the other alpha modes would keep the backdrop's alpha, nothing.
		// So every mesh is rendered normal and its blend goes to the layer.
		val plain = ir.copy(meshes = ir.meshes.map { if (it.blend == ColorBlend.NORMAL && it.alphaBlend == AlphaBlend.OVER) it else it.copy(blend = ColorBlend.NORMAL, alphaBlend = AlphaBlend.OVER) })
		val layers = renderer.open(plain, physics = false).use { session ->
			session.renderEach(pose, frame, meshes.map { it.id }).mapIndexedNotNull { index, placed ->
				placed?.let { PosedLayer(meshes[index].id, meshes[index].name, meshes.size - index, LayerBounds(it.left, it.top, it.image.width, it.image.height), rgba(it.image),
					layerBlend(meshes[index].blend)) }
			}
		}
		val losses = buildList {
			add(LossEntry("*", Feature.STRUCTURE, Handling.BAKED, note = "Each mesh is rendered at the pose; deformers and parameters are not kept"))
			ir.meshes.filter { io.github.psd2live.format.model.Channel.DRAW_ORDER in it.channels }.forEach {
				add(LossEntry(it.id, Feature.KEYED_DRAW_ORDER, Handling.APPROXIMATED, note = "Layers follow the rest draw order"))
			}
			ir.meshes.filter { it.maskedBy.isNotEmpty() }.forEach {
				add(LossEntry(it.id, Feature.MASK, Handling.BAKED, note = "The mask is applied to the layer's pixels"))
			}
			meshes.filter { it.blend == ColorBlend.ADD_PREMULTIPLIED || it.blend == ColorBlend.MULTIPLY_PREMULTIPLIED }.forEach {
				add(LossEntry(it.id, Feature.BLEND_MODE, Handling.APPROXIMATED,
					note = "Cubism's ${if (it.blend == ColorBlend.ADD_PREMULTIPLIED) "add" else "multiply"} before 5.3 becomes the layer's " +
						"${if (it.blend == ColorBlend.ADD_PREMULTIPLIED) "linear dodge (add)" else "multiply"}: the same color over opaque art, " +
						"but the layer's own pixels count where nothing lies beneath"))
			}
			meshes.filter { it.alphaBlend != AlphaBlend.OVER }.forEach {
				add(LossEntry(it.id, Feature.BLEND_MODE, Handling.APPROXIMATED,
					note = "Alpha blend ${it.alphaBlend.name.lowercase()} has no PSD layer counterpart; the layer composites over"))
			}
		}
		return object : LoweredExport {
			override val losses: List<LossEntry> = losses
			override fun write(sink: OutputSink) = sink.write("${options.baseName}.psd", PsdWriter.write(width, height, layers))
		}
	}

	/** The PSD layer blend that composites a layer by the color formula of [blend]. */
	private fun layerBlend(blend: ColorBlend): LayerBlend = when (blend) {
		ColorBlend.NORMAL -> LayerBlend.Normal
		ColorBlend.ADD, ColorBlend.ADD_PREMULTIPLIED -> LayerBlend.Add
		ColorBlend.ADD_GLOW -> LayerBlend.AddGlow
		ColorBlend.DARKEN -> LayerBlend.Darken
		ColorBlend.MULTIPLY, ColorBlend.MULTIPLY_PREMULTIPLIED -> LayerBlend.Multiply
		ColorBlend.COLOR_BURN -> LayerBlend.ColorBurn
		ColorBlend.LINEAR_BURN -> LayerBlend.LinearBurn
		ColorBlend.LIGHTEN -> LayerBlend.Lighten
		ColorBlend.SCREEN -> LayerBlend.Screen
		ColorBlend.COLOR_DODGE -> LayerBlend.ColorDodge
		ColorBlend.OVERLAY -> LayerBlend.Overlay
		ColorBlend.SOFT_LIGHT -> LayerBlend.SoftLight
		ColorBlend.HARD_LIGHT -> LayerBlend.HardLight
		ColorBlend.LINEAR_LIGHT -> LayerBlend.LinearLight
		ColorBlend.HUE -> LayerBlend.Hue
		ColorBlend.COLOR -> LayerBlend.Color
	}

	private class PosedLayer(id: String, override val name: String, override val order: Int,
	                         override val bounds: LayerBounds, override val raster: LayerRaster, override val blend: LayerBlend) : SourceLayer {
		override val id: LayerId = LayerId(id)
		override val groupPath: String = ""
		override val opacity: Float = 1f
		override val clipped: Boolean = false
		override val channelMask: ChannelMask = ChannelMask.ALL
	}

	private fun parsePose(text: String?): Map<String, Float> = text?.split(',')?.filter { it.isNotBlank() }?.associate { pair ->
		val (key, value) = pair.split('=').takeIf { it.size == 2 } ?: throw IllegalArgumentException("Pose entries are id=value: $pair")
		key.trim() to (value.trim().toFloatOrNull()?.takeIf(Float::isFinite) ?: throw IllegalArgumentException("Pose value is not a number: $pair"))
	}.orEmpty()

	/** [image]'s pixels as straight RGBA. */
	private fun rgba(image: RasterImage): LayerRaster {
		val rgba = ByteArray(image.argb.size * 4)
		for (i in image.argb.indices) {
			val argb = image.argb[i]
			rgba[i * 4] = (argb shr 16).toByte(); rgba[i * 4 + 1] = (argb shr 8).toByte(); rgba[i * 4 + 2] = argb.toByte(); rgba[i * 4 + 3] = (argb ushr 24).toByte()
		}
		return LayerRaster(image.width, image.height, rgba)
	}
}
