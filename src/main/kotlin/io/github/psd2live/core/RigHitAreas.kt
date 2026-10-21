package io.github.psd2live.core

import io.github.psd2live.format.model.HitAreaIR
import kotlinx.serialization.json.*
import org.umamo.render.eval.DeformedGeometry
import org.umamo.runtime.model.PuppetModel

/**
 * A hit area the user set up: a named region a runtime host hit-tests (Cubism's model3 `HitAreas`, the `.p2lrt`
 * `HITA` chunk), covering what its [meshes] draw. [id] is the area's own ID (`HitAreaHead`), [name] what a host asks
 * for (`Head`). Areas are tested in list order, the first one hit winning.
 */
data class RigHitArea(val id: String, val name: String, val meshes: List<String>) {
	init {
		require(RigHitAreas.ID.matches(id)) { "Hit area ID $id must be 1-64 ASCII letters, digits or underscores and start with a letter" }
		require(name.isNotBlank() && name.length <= RigHitAreas.MAX_NAME) { "Hit area name must be 1..${RigHitAreas.MAX_NAME} characters" }
		require(meshes.isNotEmpty() && meshes.all { it.isNotBlank() }) { "Hit area $id needs at least one mesh" }
		require(meshes.distinct().size == meshes.size) { "Hit area $id names a mesh twice" }
	}
}

object RigHitAreas {
	/** Cubism's ID alphabet, as parameter IDs use it. */
	val ID = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")
	const val MAX_NAME = 64
	const val MAX_AREAS = 64

	/** Whether [areas] can be a document's: distinct IDs, at most [MAX_AREAS]. */
	fun problem(areas: List<RigHitArea>): String? = when {
		areas.size > MAX_AREAS -> "At most $MAX_AREAS hit areas"
		areas.map { it.id }.distinct().size != areas.size -> "Duplicate hit area IDs"
		else -> null
	}

	fun toJson(area: RigHitArea): JsonObject = buildJsonObject {
		put("id", area.id); put("name", area.name)
		putJsonArray("meshes") { area.meshes.forEach { add(JsonPrimitive(it)) } }
	}

	fun fromJson(json: JsonObject): RigHitArea = RigHitArea(
		json.getValue("id").jsonPrimitive.content, json.getValue("name").jsonPrimitive.content,
		json.getValue("meshes").jsonArray.map { it.jsonPrimitive.content })

	/** [areas] with each mesh in [renames] under its new ID: what an ID conversion of meshes carries over. */
	fun renameMeshes(areas: List<RigHitArea>, renames: Map<String, String>): List<RigHitArea> =
		if (renames.isEmpty() || areas.none { area -> area.meshes.any { it in renames } }) areas
		else areas.map { area -> area.copy(meshes = area.meshes.map { renames[it] ?: it }.distinct()) }

	/** The meshes of [area] that [model] still has, in the area's order. */
	fun present(area: RigHitArea, model: PuppetModel): List<String> {
		val ids = model.drawables.mapTo(HashSet()) { it.id.raw }
		return area.meshes.filter { it in ids }
	}

	/** [areas] as the export carries them: each with the meshes [model] has, areas left with none dropped. */
	fun toIr(areas: List<RigHitArea>, model: PuppetModel): List<HitAreaIR> {
		val ids = model.drawables.mapTo(HashSet()) { it.id.raw }
		return areas.mapNotNull { area -> area.meshes.filter { it in ids }.takeIf { it.isNotEmpty() }?.let { HitAreaIR(area.id, area.name, it) } }
	}

	/**
	 * The Head and Body areas a generated rig suggests ([RuntimeExtras.hitAreas]): the face, and the top, else the neck or
	 * the bottom. Named as Cubism's samples name them, so hosts find them by the usual names.
	 */
	fun presets(model: PuppetModel, analysis: PipelineAnalysis, layerIdByDrawable: Map<String, String>): List<RigHitArea> =
		RuntimeExtras.hitAreas(model, analysis, layerIdByDrawable).map { area ->
			RigHitArea(area.id, area.id.removePrefix("HitArea"), area.meshes)
		}

	/** The first `HitArea<n>` (from 1) no area of [areas] has. */
	fun nextId(areas: List<RigHitArea>): String {
		val taken = areas.mapTo(HashSet()) { it.id }
		return generateSequence(1) { it + 1 }.map { "HitArea$it" }.first { it !in taken }
	}

	/**
	 * Whether the triangles [indices] of a mesh at [positions] (x, y interleaved) contain ([x], [y]), edges included:
	 * the runtime's own test (`hit_area_at`), so the editor and the runtime agree.
	 */
	fun covers(positions: FloatArray, indices: IntArray, x: Float, y: Float): Boolean {
		var i = 0
		while (i + 2 < indices.size) {
			val a = indices[i] * 2; val b = indices[i + 1] * 2; val c = indices[i + 2] * 2
			i += 3
			if (maxOf(a, b, c) + 1 >= positions.size) continue
			fun side(p: Int, q: Int) = (positions[q] - positions[p]) * (y - positions[p + 1]) - (positions[q + 1] - positions[p + 1]) * (x - positions[p])
			val d1 = side(a, b); val d2 = side(b, c); val d3 = side(c, a)
			val negative = d1 < 0f || d2 < 0f || d3 < 0f
			val positive = d1 > 0f || d2 > 0f || d3 > 0f
			if (!(negative && positive)) return true
		}
		return false
	}

	/**
	 * The first of [areas] with a mesh drawn at ([x], [y]), as the runtime tests it: a mesh counts where it is evaluated
	 * ([positions], [indices]) with an [opacity] above zero. Null on a miss.
	 */
	fun areaAt(areas: List<RigHitArea>, positions: Map<String, FloatArray>, indices: Map<String, IntArray>,
	           opacity: Map<String, Float>, x: Float, y: Float): RigHitArea? =
		areas.firstOrNull { area ->
			area.meshes.any { mesh ->
				val points = positions[mesh] ?: return@any false
				val triangles = indices[mesh] ?: return@any false
				(opacity[mesh] ?: 0f) > 0f && covers(points, triangles, x, y)
			}
		}

	/** [areaAt] on [model] evaluated to [geometry]; ([worldX], [worldY]) is a point in world space (canvas x, negated canvas y). */
	fun areaAt(areas: List<RigHitArea>, model: PuppetModel, geometry: DeformedGeometry, worldX: Float, worldY: Float): RigHitArea? {
		if (areas.isEmpty()) return null
		val wanted = areas.flatMapTo(HashSet()) { it.meshes }
		val drawn = model.drawables.filter { it.id.raw in wanted && it.isVisible && it.mesh != null }
		return areaAt(areas,
			drawn.mapNotNull { d -> geometry.worldPositions[d.id]?.let { d.id.raw to it } }.toMap(),
			drawn.associate { it.id.raw to it.mesh!!.indices },
			drawn.associate { it.id.raw to (geometry.opacity[it.id] ?: it.opacity) },
			worldX, worldY)
	}
}
