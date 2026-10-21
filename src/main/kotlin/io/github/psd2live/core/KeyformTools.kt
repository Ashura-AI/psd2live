package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.runtime.eval.EPS_KEY
import org.umamo.runtime.eval.cellsByLinearIndex
import org.umamo.runtime.eval.gridCorners
import org.umamo.runtime.model.*

/**
 * The keyform tools a deform session uses beside the brushes, as Live2D editors offer them on a keyform: `reset` puts
 * the shape at a key back to its default-key shape (or a mesh to its undeformed rest), `interpolate` remakes the shape
 * at a key from the keys on either side of it along one parameter, as if the key were not there, and `paste` writes the
 * shape another key (of the same or another object of its kind) shows. Each takes `vertices` to touch only those
 * points (a mesh's vertices, a warp's control points), the rest keeping the shape at the key.
 *
 * Like `mirror`, they are materialized when compiled ([RigAuthoringJournal.compile]): each becomes one ordinary `set` of
 * the whole shape it computes, so replay never reads another key again, and a canvas edit on a keyform a generator owns
 * is captured as a `generated_override` like any other `set`.
 *
 * Fields: `target`, `key` (the key written; it names every directly bound geometry axis), `vertices`; `reset` takes
 * `to` (`default`, the default; `rest`, meshes only) and `along` (the parameters taken to their default, default all of
 * the key's); `interpolate` takes `parameter` (default the one axis the key sits between two keys of); `paste` takes
 * `from` (the source key, missing axes at their defaults) and `destination` (default the target).
 */
internal object KeyformTools {
    const val RESET = "reset"
    const val INTERPOLATE = "interpolate"
    const val PASTE = "paste"
    val OPS = setOf(RESET, INTERPOLATE, PASTE)

    private val FIELDS = mapOf(
        RESET to setOf("op", "target", "key", "vertices", "to", "along"),
        INTERPOLATE to setOf("op", "target", "key", "vertices", "parameter"),
        PASTE to setOf("op", "target", "from", "destination", "key", "vertices"),
    )

    fun compile(model: PuppetModel, command: JsonObject): JsonObject {
        val op = command.text("op")
        val unexpected = command.keys - FIELDS.getValue(op)
        require(unexpected.isEmpty()) { "Unexpected $op field: ${unexpected.first()}" }
        val destinationText = if (op == PASTE) command["destination"]?.jsonPrimitive?.content ?: command.text("target") else command.text("target")
        val destination = Shape.of(model, destinationText)
        val key = command.coordinate("key")
        require(key.isNotEmpty()) { "Specify the exact destination key" }
        val parameters = model.parameters.associateBy { it.id.raw }
        key.keys.forEach { require(it in parameters) { "Unknown parameter: $it" } }
        require(destination.axes.all { it in key }) { "KEY_INCOMPLETE: include all directly bound geometry axes of ${destination.id}" }
        val vertices = command["vertices"]?.jsonArray?.map { it.jsonPrimitive.int }?.toSortedSet()?.also { picked ->
            require(destination.kind != "rotation") { "A rotation has no points to pick; leave out vertices" }
            require(picked.isNotEmpty() && picked.all { it in 0 until destination.pointCount }) {
                "vertices index the ${destination.pointCount} points of ${destination.id}"
            }
        }
        val defaults = parameters.mapValues { it.value.default }
        val written: FloatArray = when (op) {
            RESET -> when (command["to"]?.jsonPrimitive?.content ?: "default") {
                "rest" -> {
                    require(destination.kind == "mesh") { "Only a mesh has a rest shape apart from its default key; use to: default" }
                    require("along" !in command) { "along applies to to: default" }
                    destination.rest()
                }
                "default" -> {
                    val along = command["along"]?.jsonArray?.map { it.jsonPrimitive.content } ?: key.keys.toList()
                    along.forEach { require(it in key) { "along names a parameter of the key; $it is not one" } }
                    destination.at(key + along.associateWith { defaults.getValue(it) })
                }
                else -> throw IllegalArgumentException("to is default or rest")
            }
            INTERPOLATE -> {
                val grid = destination.grid
                val interior = destination.axes.filter { id -> neighbours(grid, id, key.getValue(id)) != null }
                val parameter = command["parameter"]?.jsonPrimitive?.content ?: interior.singleOrNull()
                    ?: throw IllegalArgumentException(if (interior.isEmpty()) "${destination.id} has no keys on both sides of this key"
                        else "The key sits between keys of ${interior.joinToString()}; name the parameter")
                require(parameters[parameter]?.kind == ParameterKind.NORMAL) { "Interpolate reads ordinary keys; $parameter is not one" }
                require(parameter in key) { "parameter must be one of the key's axes" }
                val value = key.getValue(parameter)
                val (below, above) = requireNotNull(neighbours(grid, parameter, value)) { "${destination.id} has no keys of $parameter on both sides of $value" }
                val t = (value - below) / (above - below)
                val low = destination.at(key + (parameter to below))
                val high = destination.at(key + (parameter to above))
                FloatArray(low.size) { low[it] + (high[it] - low[it]) * t }
            }
            else -> {
                val source = Shape.of(model, command.text("target"))
                require(source.kind == destination.kind) { "Paste source and destination must be the same kind (got ${source.kind} and ${destination.kind})" }
                require(source.pointCount == destination.pointCount && source.rows == destination.rows) {
                    "Paste needs the same points: ${source.id} has ${source.pointCount}, ${destination.id} ${destination.pointCount}"
                }
                val from = command.coordinate("from")
                from.keys.forEach { require(it in parameters) { "Unknown parameter: $it" } }
                source.moved(source.at(from), destination)
            }
        }
        val shape = if (vertices == null) written else destination.at(key).also { current ->
            for (v in vertices) { current[v * 2] = written[v * 2]; current[v * 2 + 1] = written[v * 2 + 1] }
        }
        return buildJsonObject {
            put("op", "set"); put("target", "${destination.kind}:${destination.id}")
            put("key", JsonObject(key.mapValues { JsonPrimitive(it.value) }))
            put("geometry", destination.geometry(shape))
        }
    }

    private fun JsonObject.text(key: String) = requireNotNull(this[key]?.jsonPrimitive?.contentOrNull) { "Missing $key" }

    private fun JsonObject.coordinate(key: String) = requireNotNull(this[key] as? JsonObject) { "Missing $key" }.mapValues { it.value.jsonPrimitive.float }

    /** The keys of [parameter] on [grid] just below and just above [value], or null when it has none on one side. */
    private fun neighbours(grid: KeyformGrid<*>?, parameter: String, value: Float): Pair<Float, Float>? {
        val keys = grid?.axes?.firstOrNull { it.parameterId.raw == parameter }?.keys ?: return null
        val below = keys.filter { it < value - EPS_KEY }.maxOrNull() ?: return null
        val above = keys.filter { it > value + EPS_KEY }.minOrNull() ?: return null
        return below to above
    }

    /**
     * One object's shape as the tools read and write it: a mesh's positions, a warp's control points, a rotation's
     * origin, angle and scale - each at a key, interpolated as the canvas shows it.
     */
    private class Shape(val model: PuppetModel, val kind: String, val id: String) {
        val grid: KeyformGrid<*>? = when (kind) {
            "mesh" -> model.drawables.first { it.id.raw == id }.geometryGrid
            "warp" -> (model.deformers.first { it.id.raw == id } as Deformer.Warp).geometryGrid
            else -> (model.deformers.first { it.id.raw == id } as Deformer.Rotation).geometryGrid
        }
        val axes: List<String> = grid?.axes.orEmpty().map { it.parameterId.raw }
        private val rest = if (kind == "rotation") null else RigGeometryTools.geometry(model, kind, id, emptyMap())
        val rows: Int? = rest?.rows
        val pointCount: Int = rest?.let { it.base.size / 2 } ?: 0

        /** The shape at [key]: points for a mesh or warp, `originX, originY, angle, scale` for a rotation. */
        fun at(key: Map<String, Float>): FloatArray {
            if (kind != "rotation") return RigGeometryTools.geometry(model, kind, id, key).points
            val grid = requireNotNull((model.deformers.first { it.id.raw == id } as Deformer.Rotation).geometryGrid) { "Rotation $id has no keyforms" }
            val defaults = model.parameters.associate { it.id to it.default }
            val cells = cellsByLinearIndex(grid)
            val corners = requireNotNull(gridCorners(grid) { key[it.raw] ?: defaults[it] ?: 0f }) { "No rotation keyform at the key" }
            val form = FloatArray(4)
            for (corner in corners) requireNotNull(cells[corner.linearIndex]).form.let {
                form[0] += corner.weight * it.originX; form[1] += corner.weight * it.originY
                form[2] += corner.weight * it.angle; form[3] += corner.weight * it.scale
            }
            return form
        }

        /** A mesh's undeformed positions. */
        fun rest(): FloatArray = requireNotNull(rest).base.copyOf()

        /** [shape] of this object carried onto [other]: a mesh's moves from rest, a warp's or rotation's form as it is. */
        fun moved(shape: FloatArray, other: Shape): FloatArray {
            if (kind != "mesh") return shape
            val from = requireNotNull(rest).base
            val to = requireNotNull(other.rest).base
            return FloatArray(shape.size) { to[it] + shape[it] - from[it] }
        }

        fun geometry(shape: FloatArray): JsonObject = buildJsonObject {
            when (kind) {
                "mesh" -> put("positionDeltas", JsonArray(shape.indices.map { JsonPrimitive(shape[it] - requireNotNull(rest).base[it]) }))
                "warp" -> put("controlPoints", JsonArray(shape.map(::JsonPrimitive)))
                else -> { put("originX", shape[0]); put("originY", shape[1]); put("angle", shape[2]); put("scale", shape[3]) }
            }
        }

        companion object {
            fun of(model: PuppetModel, text: String): Shape {
                val ref = RigAuthoringJournal.target(model, text)
                val kind = when (ref.kind) {
                    RigTargetKind.ART_MESH -> "mesh"
                    RigTargetKind.WARP_DEFORMER -> "warp"
                    RigTargetKind.ROTATION_DEFORMER -> "rotation"
                    else -> throw IllegalArgumentException("Keyform tools need a mesh, warp or rotation target")
                }
                require(when (kind) {
                    "mesh" -> model.drawables.any { it.id.raw == ref.id && it.mesh != null }
                    "warp" -> model.deformers.any { it.id.raw == ref.id && it is Deformer.Warp }
                    else -> model.deformers.any { it.id.raw == ref.id && it is Deformer.Rotation }
                }) { "${kind.replaceFirstChar(Char::uppercase)} not found: ${ref.id}" }
                return Shape(model, kind, ref.id)
            }
        }
    }
}
