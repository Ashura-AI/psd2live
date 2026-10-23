package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.runtime.model.*

/**
 * Cubism's mesh duplication: other ArtMeshes take one mesh's vertices and triangles, where that mesh has them on the
 * canvas, each sampling its own texture there. A request compiles to one `canvas_mesh_rebuild` record per target
 * ([RasterMeshJournal]), so each target's keyforms, blend shapes, paths, vertex groups and glue migrate onto the copied
 * topology as for any rebuild, and replay never reads the source mesh again.
 *
 * `{"op": "mesh_duplicate", "source": "mesh:ID", "targets": ["mesh:ID", ...]}`
 */
internal object RigMeshDuplication {
	const val OP = "mesh_duplicate"

	/** One target's copy: its replacement mesh, and how many copied vertices fall outside its artwork. */
	class Target(val id: DrawableId, val mesh: DrawableMesh, val canvas: FloatArray, val outside: Int)

	private fun mesh(text: String) = text.removePrefix("mesh:")

	fun plan(model: PuppetModel, request: JsonObject): List<Target> {
		require(request.keys.all { it in setOf("op", "source", "targets") }) { "Unknown mesh duplication field" }
		val sourceId = mesh(request.getValue("source").jsonPrimitive.content)
		val targets = request.getValue("targets").jsonArray.map { mesh(it.jsonPrimitive.content) }
		require(targets.size in 1..128 && targets.distinct().size == targets.size) { "Name 1..128 distinct target meshes" }
		require(sourceId !in targets) { "The source mesh cannot be its own target" }
		val source = model.drawables.singleOrNull { it.id.raw == sourceId } ?: throw RigRefusal(RigRefusal.Code.NOT_FOUND, "Mesh not found: $sourceId", "mesh:$sourceId")
		val sourceMesh = requireNotNull(source.mesh) { "Mesh $sourceId has no mesh" }
		val canvas = RasterMeshJournal.TextureCoordinates(model, source).toCanvas(sourceMesh.uvs)
		return targets.map { id ->
			val target = model.drawables.singleOrNull { it.id.raw == id } ?: throw RigRefusal(RigRefusal.Code.NOT_FOUND, "Mesh not found: $id", "mesh:$id")
			val previous = requireNotNull(target.mesh) { "Mesh $id has no mesh" }
			val texture = RasterMeshJournal.TextureCoordinates(model, target)
			// The target's own triangles map its artwork onto its rest positions; each copied vertex lands where that
			// map takes its canvas point, extended past the target's outline along the nearest triangle.
			val placed = texture.toCanvas(previous.uvs)
			val positions = FloatArray(canvas.size)
			for (v in 0 until canvas.size / 2) {
				val w = DeformPathTools.bind(placed, previous.indices, canvas[v * 2], canvas[v * 2 + 1])
				for (k in 0..1) positions[v * 2 + k] = w.wa * previous.positions[w.a * 2 + k] + w.wb * previous.positions[w.b * 2 + k] +
					w.wc * previous.positions[w.c * 2 + k]
			}
			Target(target.id, DrawableMesh(positions, texture.toUvs(canvas), sourceMesh.indices.copyOf()), canvas, outside(canvas, texture.layer))
		}
	}

	private fun outside(canvas: FloatArray, layer: ArtSourceLayer): Int = (0 until canvas.size / 2).count { v ->
		val x = canvas[v * 2]; val y = canvas[v * 2 + 1]
		x < layer.left || y < layer.top || x > layer.left + layer.width || y > layer.top + layer.height
	}

	fun compile(model: PuppetModel, request: JsonObject): List<JsonObject> = plan(model, request).map { target ->
		val xs = target.canvas.indices.step(2).map { target.canvas[it] }; val ys = target.canvas.indices.step(2).map { target.canvas[it + 1] }
		RasterMeshJournal.encode(model, target.id, target.mesh, neutralBounds = Bounds(xs.min(), ys.min(), xs.max(), ys.max()))
	}
}
