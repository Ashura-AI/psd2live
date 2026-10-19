package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.core.interior.InteriorGuide
import io.github.psd2live.core.interior.InteriorGuidePurpose
import io.github.psd2live.core.interior.InteriorPoint
import io.github.psd2live.core.mesh.Point
import io.github.psd2live.core.mesh.distanceSquaredToSegment
import io.github.psd2live.core.mesh.structure.*
import io.github.psd2live.project.WorkspaceDocument
import kotlinx.serialization.json.*
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh

/**
 * Rebuilds the inside of one mesh along the structure drawn into its texture ([TextureStructure], [StructureMesher]):
 * the outline row and edge band stay, the rest follows creases, shading boundaries and pattern lines. The result is
 * an ordinary `canvas_mesh_rebuild` record, so keyforms, blend shapes, paths, weights and glue migrate as for any
 * rebuild and replay never analyses pixels again. On a mesh with an interior effect it also refreshes the effect's
 * detected guides; the effect is baked again by the `interior_put` that follows it in the same batch.
 */
internal object WorkspaceStructureMeshEdits {
	const val OP = "layer_mesh_structure"
	const val PREVIEW = "layer_mesh_structure_preview"

	val settingsFields: Map<String, JsonObject> by lazy {
		val s = WorkspaceResultSchema
		fun describe(value: JsonObject, text: String) = JsonObject(value + ("description" to JsonPrimitive(text)))
		mapOf(
			"sensitivity" to describe(s.number(0, 1), "0 keeps only strong, high-contrast lines; 1 also keeps faint ones. Default 0.5."),
			"min_length" to describe(s.number(2, 512), "Shortest line kept, in source pixels. Default 16."),
			"near_density" to describe(s.number(0.25, 4), "Vertex spacing along lines, relative to the spacing of the kept edge rows. Default 1."),
			"fill" to describe(s.boolean(), "Add vertices between the lines, thinning out away from them. Default false: vertices only on the edge rows and the lines."),
			"far_density" to describe(s.number(0.5, 3), "With fill, edge length away from lines, relative to the mesh's current inner edge length. Default 1."),
			"bands" to describe(s.boolean(), "Straddle each drawn crease with a row on either side and rungs across. Default false: one row on the crease."),
			"kinds" to describe(s.array(s.choices("FOLD", "SHADE", "PATTERN"), 0, 3), "Line kinds the mesh follows. Default all three."),
			"max_lines" to describe(s.integer(1, 64), "Most lines kept, strongest first. Default 48."),
			"max_vertices" to describe(s.integer(16, 16000), "The rebuild fails rather than exceed this many vertices. Default 4000."),
		)
	}

	fun settings(request: JsonObject): StructureMeshSettings {
		val o = request["settings"]?.jsonObject ?: JsonObject(emptyMap())
		val unknown = o.keys - settingsFields.keys
		require(unknown.isEmpty()) { "Unknown structure settings: ${unknown.sorted().joinToString()}" }
		val defaults = StructureMeshSettings()
		fun number(key: String, default: Double) = o[key]?.jsonPrimitive?.double ?: default
		return StructureMeshSettings(
			StructureSettings(
				sensitivity = number("sensitivity", defaults.structure.sensitivity),
				minLength = number("min_length", defaults.structure.minLength),
				kinds = o["kinds"]?.jsonArray?.mapTo(LinkedHashSet()) { StructureKind.valueOf(it.jsonPrimitive.content.uppercase()) }
					?: defaults.structure.kinds,
				maxLines = o["max_lines"]?.jsonPrimitive?.int ?: defaults.structure.maxLines,
			),
			near = number("near_density", defaults.near),
			far = number("far_density", defaults.far),
			fill = o["fill"]?.jsonPrimitive?.boolean ?: defaults.fill,
			bands = o["bands"]?.jsonPrimitive?.boolean ?: defaults.bands,
			maxVertices = o["max_vertices"]?.jsonPrimitive?.int ?: defaults.maxVertices,
		)
	}

	/** Everything one remesh decides, before anything is written. */
	class Plan(val id: DrawableId, val previous: DrawableMesh, val replacement: DrawableMesh, val analysis: StructureAnalysis,
	           val mesh: StructureMesh, val rim: PreservedRim, val canvas: (Point) -> FloatArray)

	fun plan(model: RigPreviewModel, overlay: RigEditOverlay, target: String, settings: StructureMeshSettings,
	         checkpoint: () -> Unit = {}): Plan {
		val raw = target.removePrefix("mesh:")
		val puppet = model.rig.puppet
		val drawable = requireNotNull(puppet.drawables.singleOrNull { it.id.raw == raw }) { "Mesh not found: $raw" }
		val mesh = requireNotNull(drawable.mesh) { "Mesh not found: $raw" }
		val layerId = model.rig.layerIdByDrawableId[raw] ?: raw
		val layer = requireNotNull(model.analysis.layers.singleOrNull { it.source.id.raw == layerId }) { "Source pixels unavailable for $raw" }
		val resolved = LayerTexture.of(puppet, drawable)
		val pixels = layer.source.textureLayer.raster
		val xy = FloatArray(mesh.positions.size)
		for (i in 0 until mesh.vertexCount) {
			val p = resolved.texture.rasterOfUv(mesh.uvs[i * 2], mesh.uvs[i * 2 + 1])
			xy[i * 2] = p[0]; xy[i * 2 + 1] = p[1]
		}
		val rows = when (RigBuilder.meshSettings(layer, model.config).first.edgeMode) {
			MeshEdgeMode.SINGLE -> 0; MeshEdgeMode.DOUBLE -> 1; MeshEdgeMode.TRIPLE -> 2
		}
		val rim = PreservedRim.of(xy, mesh.indices, rows)
		require(rim.components.isNotEmpty()) { "Mesh $raw has no interior inside its edge band to rebuild" }
		checkpoint()
		val detected = TextureStructure.analyze(pixels.width, pixels.height, pixels.rgba, settings.structure, rim.domain, checkpoint)
		// Lines the author drew on an interior effect lead: a guide marked a fold is a crease to straddle.
		val authored = overlay.interiorEffects.singleOrNull { it.target == raw }?.guides.orEmpty()
			.filter { it.detected == null || it.purpose == InteriorGuidePurpose.FOLD }
			.map { guide -> StructureLine("guide:${guide.id}", guide.points.map { Point(it.x.toDouble(), it.y.toDouble()) },
				if (guide.purpose == InteriorGuidePurpose.FOLD) StructureKind.FOLD else StructureKind.PATTERN,
				Double.MAX_VALUE, (guide.radius / 4.0).coerceIn(1.0, 6.0), false, 0, guide.purpose == InteriorGuidePurpose.FOLD) }
		val lines = authored + detected.lines.filter { line -> authored.none { overlaps(line, it) } }
		val analysis = StructureAnalysis(detected.width, detected.height, detected.scale, lines)
		val rebuilt = StructureMesher.mesh(rim, analysis, settings, checkpoint)
		// New vertices take position and texture coordinates from the old triangle they fall in; mapping is affine
		// per triangle, so what the mesh showed stays where it was.
		fun weights(p: Point) = DeformPathTools.bind(xy, mesh.indices, p.x.toFloat(), p.y.toFloat())
		val positions = FloatArray(rebuilt.points.size * 2); val uvs = FloatArray(rebuilt.points.size * 2)
		for (i in rebuilt.points.indices) {
			checkpoint()
			val old = rebuilt.kept[i]
			if (old >= 0) {
				positions[i * 2] = mesh.positions[old * 2]; positions[i * 2 + 1] = mesh.positions[old * 2 + 1]
				uvs[i * 2] = mesh.uvs[old * 2]; uvs[i * 2 + 1] = mesh.uvs[old * 2 + 1]
			} else {
				val w = weights(rebuilt.points[i])
				for (k in 0..1) {
					positions[i * 2 + k] = w.wa * mesh.positions[w.a * 2 + k] + w.wb * mesh.positions[w.b * 2 + k] + w.wc * mesh.positions[w.c * 2 + k]
					uvs[i * 2 + k] = w.wa * mesh.uvs[w.a * 2 + k] + w.wb * mesh.uvs[w.b * 2 + k] + w.wc * mesh.uvs[w.c * 2 + k]
				}
			}
		}
		val texture = RasterMeshJournal.TextureCoordinates(puppet, drawable)
		val canvas = { p: Point ->
			val w = weights(p)
			val u = w.wa * mesh.uvs[w.a * 2] + w.wb * mesh.uvs[w.b * 2] + w.wc * mesh.uvs[w.c * 2]
			val v = w.wa * mesh.uvs[w.a * 2 + 1] + w.wb * mesh.uvs[w.b * 2 + 1] + w.wc * mesh.uvs[w.c * 2 + 1]
			texture.toCanvas(floatArrayOf(u, v))
		}
		return Plan(drawable.id, mesh, DrawableMesh(positions, uvs, rebuilt.triangles), analysis, rebuilt, rim, canvas)
	}

	private fun overlaps(line: StructureLine, guide: StructureLine): Boolean =
		line.points.count { p -> guide.points.zipWithNext().any { (a, b) -> distanceSquaredToSegment(p, a, b) < 16.0 } } >= .7 * line.points.size

	fun apply(document: WorkspaceDocument, model: RigPreviewModel, request: JsonObject,
	          work: WorkspaceRasterWork = WorkspaceRasterWork.Direct): WorkspaceDocument {
		val target = request.getValue("target").jsonPrimitive.content
		work.progress(.1f, "Reading texture structure")
		val plan = plan(model, document.rigEdits, target, settings(request), work::checkpoint)
		work.progress(.8f, "Recording mesh")
		val record = RasterMeshJournal.encode(model.rig.puppet, plan.id, plan.replacement, checkpoint = work::checkpoint)
		val journal = RigAuthoringJournal.compile(model.rig.puppet, JsonArray(listOf(record))).second
		val effects = document.rigEdits.interiorEffects.map { effect ->
			if (effect.target != plan.id.raw) effect else effect.withGuides(guides(effect.guides, plan.analysis.lines))
		}
		work.progress(1f, "Recorded mesh")
		return document.copy(rigEdits = document.rigEdits.copy(authoringJournal = document.rigEdits.authoringJournal + journal,
			interiorEffects = effects))
	}

	/**
	 * [current] with its detected topology lines replaced by [lines]: drawn guides and detected lines the author
	 * marked a fold stay, and a new line over one of them is left out.
	 */
	fun guides(current: List<InteriorGuide>, lines: List<StructureLine>): List<InteriorGuide> {
		val kept = current.filter { it.detected == null || it.purpose == InteriorGuidePurpose.FOLD }
		val room = (64 - kept.size).coerceAtLeast(0)
		val fresh = lines.filter { !it.id.startsWith("guide:") }.take(room)
		val added = ArrayList<InteriorGuide>()
		for (line in fresh) {
			val points = line.points.let { pts -> if (pts.size <= 256) pts else List(256) { pts[it * (pts.size - 1) / 255] } }
				.map { InteriorPoint(it.x.toFloat(), it.y.toFloat()) }
				.fold(ArrayList<InteriorPoint>()) { list, p -> if (list.lastOrNull() != p) list += p; list }
			if (points.size < 2) continue
			val id = generateSequence(1) { it + 1 }.map { "line$it" }.first { id -> (kept + added).none { it.id == id } }
			added += InteriorGuide(id, points, InteriorGuidePurpose.TOPOLOGY, (3 * line.width + 8).toFloat().coerceIn(8f, 48f), 1f,
				false, line.kind)
		}
		return kept + added
	}

	/** What [plan] would build, for overlays and inspection: lines and mesh in source pixels and canvas units. */
	fun preview(model: RigPreviewModel, overlay: RigEditOverlay, request: JsonObject, checkpoint: () -> Unit = {}): JsonObject {
		val plan = plan(model, overlay, request.getValue("target").jsonPrimitive.content, settings(request), checkpoint)
		fun pair(x: Number, y: Number) = buildJsonArray { add(x); add(y) }
		val placed = plan.mesh.lines.associateBy { it.line.id }
		return buildJsonObject {
			put("target", plan.id.raw)
			put("rows", plan.rim.rows)
			put("vertices_before", plan.previous.vertexCount)
			put("vertices_after", plan.replacement.vertexCount)
			put("triangles", plan.replacement.indices.size / 3)
			putJsonArray("lines") {
				for (line in plan.analysis.lines) add(buildJsonObject {
					put("id", line.id); put("kind", line.kind.name); put("strength", line.strength.coerceAtMost(1e6))
					put("width", line.width); put("closed", line.closed); put("free_ends", line.freeEnds)
					put("followed", line.id in placed); put("banded", placed[line.id]?.banded ?: false)
					putJsonArray("points") { line.points.forEach { add(pair(it.x, it.y)) } }
					putJsonArray("canvas") { line.points.forEach { p -> plan.canvas(p).let { add(pair(it[0], it[1])) } } }
				})
			}
			putJsonArray("canvas_points") {
				plan.mesh.points.forEach { p -> plan.canvas(p).let { add(pair(it[0], it[1])) } }
			}
			putJsonArray("triangle_indices") { plan.replacement.indices.forEach { add(it) } }
		}
	}

	val previewResult: JsonObject by lazy {
		val s = WorkspaceResultSchema
		s.obj(mapOf(
			"project_id" to s.handle(), "state" to s.handle(), "revision" to s.handle(),
			"target" to s.handle(), "rows" to s.integer(0, 4), "vertices_before" to s.integer(0), "vertices_after" to s.integer(0),
			"triangles" to s.integer(0),
			"lines" to s.array(s.obj(mapOf(
				"id" to s.handle(), "kind" to s.choices("FOLD", "SHADE", "PATTERN"), "strength" to s.number(0), "width" to s.number(0),
				"closed" to s.boolean(), "free_ends" to s.integer(0, 2), "followed" to s.boolean(), "banded" to s.boolean(),
				"points" to s.array(s.vector(2)), "canvas" to s.array(s.vector(2))))),
			"canvas_points" to s.array(s.vector(2)),
			"triangle_indices" to s.array(s.integer(0)),
		))
	}
}
