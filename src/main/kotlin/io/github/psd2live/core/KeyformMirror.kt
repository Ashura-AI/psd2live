package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.render.eval.DeformerWorld
import org.umamo.runtime.model.*
import kotlin.math.*

/**
 * The `mirror` authoring command: a keyform reflected across a vertical canvas line, onto the opposite key of the same
 * object (a head turned right made from the head turned left), onto the object on the other side (the right eye from
 * the left), or in place.
 *
 * Like `deform`, it is materialized when compiled ([RigAuthoringJournal.compile]): each mirrored keyform becomes an
 * ordinary `set` of the points it computes, so replay never reflects anything again. The reflection is of the
 * object's own deformation, in a frame fixed at rest - the parents at their defaults - since a parent's keyforms are
 * mirrored as its own: every source point's move from rest, taken onto the canvas, is reflected and given to the
 * destination point whose rest reflects onto it, interpolated over the source's triangles (a warp's cells split in
 * two), and taken back into the destination's parent space. A symmetric pair, or one object symmetric about the
 * line, maps point onto point.
 *
 * Fields: `target` (the source), `destination` (default the target; `auto` finds the opposite-side object by its id
 * or name), `from` (the source key) or `all_keys`, `key` (the destination key, default [mirroredKey] of `from`),
 * `axis_x` (the canvas line, default midway between the two objects' rest centers), `reflect` (the parameters whose
 * values flip about their default, default [reflects]), `side` (with `all_keys` on one object: which side's keys are
 * the source, `positive` or `negative`) and `channels` (`geometry` and the scalar channels `copy` takes; default
 * geometry only).
 */
internal object KeyformMirror {
    const val OP = "mirror"

    private val CHANNELS = setOf("geometry", "opacity", "drawOrder", "draw_order", "multiplyColor", "multiply_color",
        "screenColor", "screen_color", "glueIntensity", "glue_intensity", "flipX", "flip_x", "flipY", "flip_y")

    fun compile(model: PuppetModel, command: JsonObject): List<JsonObject> {
        require((command.keys - setOf("op", "target", "destination", "from", "key", "all_keys", "axis_x", "reflect", "side", "channels")).isEmpty()) {
            "Unexpected mirror field: ${(command.keys - setOf("op", "target", "destination", "from", "key", "all_keys", "axis_x", "reflect", "side", "channels")).first()}"
        }
        val source = RigAuthoringJournal.target(model, command.text("target"))
        val sourceKind = kindName(source.kind)
        requireExists(model, sourceKind, source.id)
        val destinationText = command["destination"]?.jsonPrimitive?.content
        val destination = when (destinationText) {
            null -> source
            "auto" -> requireNotNull(counterpart(model, source)) { "No opposite-side ${sourceKind} found for ${source.id}; name the destination" }
            else -> RigAuthoringJournal.target(model, destinationText)
        }
        val kind = kindName(destination.kind)
        require(kind == sourceKind) { "Mirror source and destination must be the same kind (got $sourceKind and $kind)" }
        requireExists(model, kind, destination.id)
        val same = destination.id == source.id
        val channels = command["channels"]?.jsonArray?.map { it.jsonPrimitive.content } ?: listOf("geometry")
        require(channels.isNotEmpty() && channels.all { it in CHANNELS }) { "Mirror channels are geometry, opacity, drawOrder, multiplyColor, screenColor, glueIntensity, flipX and flipY" }
        val parameters = model.parameters.associateBy { it.id.raw }
        val reflect = command["reflect"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet()?.also { ids ->
            ids.forEach { id -> require(id in parameters) { "Unknown parameter: $id" } }
        } ?: parameters.values.filter { reflects(model, it) }.mapTo(HashSet()) { it.id.raw }
        val destinationAxes = axes(model, kind, destination.id)
        val allKeys = command["all_keys"]?.jsonPrimitive?.boolean == true
        val pairs: List<Pair<Map<String, Float>, Map<String, Float>>> = if (allKeys) {
            require("from" !in command && "key" !in command) { "all_keys takes every source key; leave out from and key" }
            val side = command["side"]?.jsonPrimitive?.content ?: "positive"
            require(side in setOf("positive", "negative")) { "side is positive or negative" }
            cellKeys(model, sourceKind, source.id).mapNotNull { from ->
                if (same && sideOf(model, from, reflect) != side) return@mapNotNull null
                from to completed(model, mirroredKey(model, from, destinationAxes, reflect), destinationAxes)
            }.also { require(it.isNotEmpty()) { if (same) "${source.id} has no keys on the $side side of ${reflect.sorted()}" else "${source.id} has no keys to mirror" } }
        } else {
            require("side" !in command) { "side applies only with all_keys" }
            val from = command.coordinate("from")
            val key = if ("key" in command) command.coordinate("key").also { key ->
                require(destinationAxes.all { it in key }) { "KEY_INCOMPLETE: include all directly bound geometry axes of ${destination.id}" }
            } else completed(model, mirroredKey(model, from, destinationAxes, reflect), destinationAxes)
            listOf(from to key)
        }
        for ((from, key) in pairs) for (id in from.keys + key.keys) {
            val parameter = requireNotNull(parameters[id]) { "Unknown parameter: $id" }
            require(parameter.kind == ParameterKind.NORMAL) { "Mirror reads ordinary keyforms; $id is a blend shape" }
        }
        val frame = Frame(model, sourceKind, source.id, destination.id, command["axis_x"]?.jsonPrimitive?.float)
        val scalars = channels.filter { it != "geometry" }
        return pairs.flatMap { (from, key) ->
            buildList {
                if ("geometry" in channels) add(frame.set(command.getValue("target"), destination, from, key, destinationText))
                if (scalars.isNotEmpty()) add(buildJsonObject {
                    put("op", "copy"); put("target", command.getValue("target")); put("from", from.json())
                    put("destination", reference(destination)); put("key", key.json()); put("channels", JsonArray(scalars.map(::JsonPrimitive)))
                })
            }
        }
    }

    /**
     * Whether [parameter] reads as a left-right one whose value a horizontal mirror flips about its default: an
     * ordinary parameter whose range is symmetric about the default, and that is a 2D pad's horizontal axis or ends in
     * `X` or `Z` - the Cubism names of a turn and a tilt (`ParamAngleX`, `ParamBodyAngleZ`, `ParamEyeBallX`).
     */
    fun reflects(model: PuppetModel, parameter: Parameter): Boolean =
        parameter.kind == ParameterKind.NORMAL && symmetric(parameter.min, parameter.default, parameter.max) &&
            (model.parameterLinks.any { it.horizontal == parameter.id } || HORIZONTAL.containsMatchIn(parameter.id.raw))

    /** [reflects] read from an ordinary parameter's id and range alone, for a caller without the model's 2D pads. */
    fun reflectsByName(id: String, min: Float, default: Float, max: Float): Boolean =
        symmetric(min, default, max) && HORIZONTAL.containsMatchIn(id)

    private fun symmetric(min: Float, default: Float, max: Float) =
        abs((max - default) - (default - min)) <= max(max - min, 1e-6f) * 1e-4f

    private val HORIZONTAL = Regex("[a-z0-9_][XZ]$")

    /**
     * The key that mirrors [from] onto an object binding [destinationAxes]: each parameter in [reflect] flipped about
     * its default, and a sided parameter (`ParamEyeLOpen`) swapped for its opposite (`ParamEyeROpen`) when the model has
     * one and the destination binds it, or binds neither.
     */
    fun mirroredKey(model: PuppetModel, from: Map<String, Float>, destinationAxes: Set<String>, reflect: Set<String>): Map<String, Float> {
        val parameters = model.parameters.associateBy { it.id.raw }
        val result = LinkedHashMap<String, Float>()
        for ((id, value) in from) {
            val parameter = requireNotNull(parameters[id]) { "Unknown parameter: $id" }
            val opposite = swapSide(id)?.takeIf { it in parameters && (it in destinationAxes || id !in destinationAxes) }
            val mirrored = if (id in reflect) 2 * parameter.default - value else value
            val written = opposite ?: id
            val bounds = parameters.getValue(written)
            require(mirrored in bounds.min - 1e-4f..bounds.max + 1e-4f) { "Mirrored $id = $mirrored leaves the range of $written (${bounds.min}..${bounds.max})" }
            result[written] = mirrored.coerceIn(bounds.min, bounds.max)
        }
        return result
    }

    /** [text] with its side token swapped (`L`/`R` as an id part, `Left`/`Right`, `左`/`右`), or null when it has none. */
    fun swapSide(text: String): String? {
        var found = false
        val swapped = SIDE.replace(text) { match ->
            found = true
            when (val token = match.value) {
                "L" -> "R"; "R" -> "L"; "l" -> "r"; "r" -> "l"
                "Left" -> "Right"; "Right" -> "Left"; "left" -> "right"; "right" -> "left"; "LEFT" -> "RIGHT"; "RIGHT" -> "LEFT"
                "左" -> "右"; "右" -> "左"
                else -> token
            }
        }
        return if (found && swapped != text) swapped else null
    }

    private val SIDE = Regex("Left|Right|left|right|LEFT|RIGHT|左|右|(?<=[a-z0-9_ .-])[LR](?=[A-Z0-9_ .-]|$)|(?<=[_ .-])[lr](?=[0-9_ .-]|$)")

    /**
     * The object on the other side of [ref]: the one of its kind whose id is [ref]'s with the side swapped, else the
     * single one whose name is; null when neither identifies one.
     */
    fun counterpart(model: PuppetModel, ref: RigTargetRef): RigTargetRef? {
        val objects: List<Pair<String, String>> = when (ref.kind) {
            RigTargetKind.ART_MESH -> model.drawables.filter { it.mesh != null }.map { it.id.raw to it.name }
            RigTargetKind.WARP_DEFORMER -> model.deformers.filterIsInstance<Deformer.Warp>().map { it.id.raw to it.name }
            RigTargetKind.ROTATION_DEFORMER -> model.deformers.filterIsInstance<Deformer.Rotation>().map { it.id.raw to it.name }
            else -> return null
        }
        swapSide(ref.id)?.let { id -> if (objects.any { it.first == id }) return ref.copy(id = id) }
        val name = objects.firstOrNull { it.first == ref.id }?.second ?: return null
        val opposite = swapSide(name) ?: return null
        return objects.filter { it.second == opposite && it.first != ref.id }.singleOrNull()?.let { ref.copy(id = it.first) }
    }

    /** The side of [key] the first parameter of [reflect] off its default is on, `positive` or `negative`; null when all are at it. */
    fun sideOf(model: PuppetModel, key: Map<String, Float>, reflect: Set<String>): String? {
        val parameters = model.parameters.associateBy { it.id.raw }
        for ((id, value) in key) {
            if (id !in reflect) continue
            val default = parameters[id]?.default ?: continue
            if (abs(value - default) > org.umamo.runtime.eval.EPS_KEY) return if (value > default) "positive" else "negative"
        }
        return null
    }

    /** [key] with each of [axes] it leaves out at its default. */
    private fun completed(model: PuppetModel, key: Map<String, Float>, axes: Set<String>): Map<String, Float> {
        val defaults = model.parameters.associate { it.id.raw to it.default }
        return key + axes.filter { it !in key }.associateWith { defaults.getValue(it) }
    }

    private fun kindName(kind: RigTargetKind) = when (kind) {
        RigTargetKind.WARP_DEFORMER -> "warp"
        RigTargetKind.ROTATION_DEFORMER -> "rotation"
        RigTargetKind.ART_MESH -> "mesh"
        else -> throw IllegalArgumentException("Mirror needs a mesh, warp or rotation target")
    }

    private fun reference(ref: RigTargetRef) = "${kindName(ref.kind)}:${ref.id}"

    private fun requireExists(model: PuppetModel, kind: String, id: String) = require(when (kind) {
        "mesh" -> model.drawables.any { it.id.raw == id && it.mesh != null }
        "warp" -> model.deformers.any { it.id.raw == id && it is Deformer.Warp }
        else -> model.deformers.any { it.id.raw == id && it is Deformer.Rotation }
    }) { "${kind.replaceFirstChar(Char::uppercase)} not found: $id" }

    private fun grid(model: PuppetModel, kind: String, id: String): KeyformGrid<*>? = when (kind) {
        "mesh" -> model.drawables.first { it.id.raw == id }.geometryGrid
        else -> model.deformers.first { it.id.raw == id }.let { (it as? Deformer.Warp)?.geometryGrid ?: (it as? Deformer.Rotation)?.geometryGrid }
    }

    private fun axes(model: PuppetModel, kind: String, id: String): Set<String> =
        grid(model, kind, id)?.axes?.mapTo(LinkedHashSet()) { it.parameterId.raw }.orEmpty()

    /** The key of every keyform cell [id] has. */
    private fun cellKeys(model: PuppetModel, kind: String, id: String): List<Map<String, Float>> {
        val grid = grid(model, kind, id) ?: return emptyList()
        return grid.cells.map { cell -> grid.axes.withIndex().associate { (i, axis) -> axis.parameterId.raw to axis.keys[cell.coordinate[i]] } }
            .filter { it.isNotEmpty() }
    }

    private fun Map<String, Float>.json() = JsonObject(mapValues { JsonPrimitive(it.value) })

    private fun JsonObject.text(key: String) = requireNotNull(this[key]?.jsonPrimitive?.contentOrNull) { "Missing $key" }

    private fun JsonObject.coordinate(key: String): Map<String, Float> =
        requireNotNull(this[key] as? JsonObject) { "Missing $key" }.mapValues { it.value.jsonPrimitive.float }
            .also { require(it.isNotEmpty() && it.values.all(Float::isFinite)) { "$key needs at least one finite parameter value" } }

    /**
     * The fixed frame one mirror command works in: both objects' rest geometry on the canvas, the line, and which
     * source points each destination point reads ([weights]), all computed once for every key the command mirrors.
     */
    private class Frame(val model: PuppetModel, val kind: String, val sourceId: String, val destinationId: String, axisX: Float?) {
        private val worlds = SkeletonRig.worlds(model, emptyMap())
        private val sourceWorld = parentWorld(sourceId)
        private val destinationWorld = parentWorld(destinationId)
        private val sourceRestLocal = RigGeometryTools.geometry(model, kind, sourceId, emptyMap()).points
        private val destinationRestLocal = RigGeometryTools.geometry(model, kind, destinationId, emptyMap()).points
        private val sourceRest = toCanvas(sourceWorld, sourceRestLocal)
        private val destinationRest = toCanvas(destinationWorld, destinationRestLocal)
        val axis: Float = axisX?.also { require(it.isFinite()) { "axis_x must be finite" } } ?: ((center(sourceRest) + center(destinationRest)) / 2f)

        /** Per destination point, three source points and their weights: where its reflected rest falls among the source's. */
        private val weights: Pair<IntArray, FloatArray> by lazy {
            val triangles = triangles(model, kind, sourceId)
            val count = destinationRest.size / 2
            val index = IntArray(count * 3)
            val weight = FloatArray(count * 3)
            for (j in 0 until count) {
                val (i, w) = locate(sourceRest, triangles, 2 * axis - destinationRest[j * 2], destinationRest[j * 2 + 1])
                i.copyInto(index, j * 3); w.copyInto(weight, j * 3)
            }
            index to weight
        }

        private fun parentWorld(id: String): DeformerWorld? {
            val parent = if (kind == "mesh") model.drawables.first { it.id.raw == id }.parentDeformerId
                else model.deformers.first { it.id.raw == id }.parent
            return parent?.let { requireNotNull(worlds[it]) { "The parent of $id is hidden at the default pose" } }
        }

        fun set(target: JsonElement, destination: RigTargetRef, from: Map<String, Float>, key: Map<String, Float>, destinationText: String?): JsonObject {
            val shown = toCanvas(sourceWorld, RigGeometryTools.geometry(model, kind, sourceId, from).points)
            val geometry = buildJsonObject {
                if (kind == "rotation") {
                    val sourceForm = rotation(sourceId, from)
                    val sourceRestForm = rotation(sourceId, emptyMap())
                    val restForm = rotation(destinationId, emptyMap())
                    val local = back(0, -(shown[0] - sourceRest[0]), shown[1] - sourceRest[1])
                    put("originX", local[0]); put("originY", local[1])
                    put("angle", restForm[2] - (sourceForm[2] - sourceRestForm[2]))
                    put("scale", restForm[3] + (sourceForm[3] - sourceRestForm[3]))
                } else {
                    val (index, weight) = weights
                    val points = FloatArray(destinationRest.size)
                    for (j in 0 until destinationRest.size / 2) {
                        var dx = 0f; var dy = 0f
                        for (k in 0 until 3) {
                            val i = index[j * 3 + k]; val w = weight[j * 3 + k]
                            dx += w * (shown[i * 2] - sourceRest[i * 2]); dy += w * (shown[i * 2 + 1] - sourceRest[i * 2 + 1])
                        }
                        back(j, -dx, dy).copyInto(points, j * 2)
                    }
                    if (kind == "warp") put("controlPoints", JsonArray(points.map(::JsonPrimitive)))
                    else {
                        val base = model.drawables.first { it.id.raw == destinationId }.mesh!!.positions
                        put("positionDeltas", JsonArray(points.indices.map { JsonPrimitive(points[it] - base[it]) }))
                    }
                }
            }
            return buildJsonObject {
                put("op", "set")
                put("target", if (destinationText == null) target else JsonPrimitive(reference(destination)))
                put("key", key.json()); put("geometry", geometry)
            }
        }

        /** Destination point [j] moved by ([dx], [dy]) on the canvas from its rest, in its parent's space. */
        private fun back(j: Int, dx: Float, dy: Float): FloatArray {
            val restX = destinationRestLocal[j * 2]; val restY = destinationRestLocal[j * 2 + 1]
            // An unmoved point keeps its exact rest, so a mirror leaves no noise where nothing moved.
            if (abs(dx) < 1e-6f && abs(dy) < 1e-6f) return floatArrayOf(restX, restY)
            val x = destinationRest[j * 2] + dx; val y = destinationRest[j * 2 + 1] + dy
            val world = destinationWorld ?: return floatArrayOf(x, y)
            return SkeletonRig.inverse(world, x, y, floatArrayOf(restX, restY))
        }

        /** A rotation's form at [key]: origin, angle and scale. */
        private fun rotation(id: String, key: Map<String, Float>): FloatArray {
            val geometry = RigGeometryTools.geometry(model, "rotation", id, key)
            val rotation = model.deformers.first { it.id.raw == id } as Deformer.Rotation
            val p = geometry.points
            val scale = hypot(p[2] - p[0], p[3] - p[1]) / RigGeometryTools.rotationHandleLength(model, rotation)
            return floatArrayOf(p[0], p[1], geometry.rotationAngle ?: 0f, scale)
        }

        private fun toCanvas(world: DeformerWorld?, local: FloatArray): FloatArray {
            if (world == null) return local.copyOf()
            val out = FloatArray(local.size)
            for (i in local.indices step 2) world.apply(local[i], local[i + 1], out, i)
            return out
        }

        private fun center(points: FloatArray): Float {
            if (kind == "rotation") return points[0]
            var low = Float.POSITIVE_INFINITY; var high = Float.NEGATIVE_INFINITY
            for (i in points.indices step 2) { low = min(low, points[i]); high = max(high, points[i]) }
            return (low + high) / 2f
        }
    }

    /** The source's triangles: a mesh's own, a warp's cells cut corner to corner. */
    private fun triangles(model: PuppetModel, kind: String, id: String): IntArray = when (kind) {
        "mesh" -> model.drawables.first { it.id.raw == id }.mesh!!.indices
        else -> {
            val warp = model.deformers.first { it.id.raw == id } as Deformer.Warp
            val stride = warp.columns + 1
            IntArray(warp.rows * warp.columns * 6).also { out ->
                var n = 0
                for (r in 0 until warp.rows) for (c in 0 until warp.columns) {
                    val a = r * stride + c; val b = a + 1; val d = a + stride; val e = d + 1
                    for (v in intArrayOf(a, b, e, a, e, d)) out[n++] = v
                }
            }
        }
    }

    /**
     * Where ([x], [y]) falls among [points]' [triangles]: three point indices and their barycentric weights, of the
     * triangle holding it, else of the nearest point on the nearest triangle's edges, so a point past the source's
     * outline reads the move of the outline nearest it.
     */
    internal fun locate(points: FloatArray, triangles: IntArray, x: Float, y: Float): Pair<IntArray, FloatArray> {
        var best = Double.POSITIVE_INFINITY
        var index = intArrayOf(0, 0, 0)
        var weight = floatArrayOf(1f, 0f, 0f)
        for (t in 0 until triangles.size / 3) {
            val a = triangles[t * 3]; val b = triangles[t * 3 + 1]; val c = triangles[t * 3 + 2]
            val ax = points[a * 2].toDouble(); val ay = points[a * 2 + 1].toDouble()
            val bx = points[b * 2].toDouble(); val by = points[b * 2 + 1].toDouble()
            val cx = points[c * 2].toDouble(); val cy = points[c * 2 + 1].toDouble()
            val det = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
            if (abs(det) > 1e-12) {
                val l1 = ((by - cy) * (x - cx) + (cx - bx) * (y - cy)) / det
                val l2 = ((cy - ay) * (x - cx) + (ax - cx) * (y - cy)) / det
                val l3 = 1 - l1 - l2
                if (l1 >= -1e-9 && l2 >= -1e-9 && l3 >= -1e-9)
                    return intArrayOf(a, b, c) to floatArrayOf(l1.toFloat(), l2.toFloat(), l3.toFloat())
            }
            for ((p, q) in listOf(a to b, b to c, c to a)) {
                val px = points[p * 2].toDouble(); val py = points[p * 2 + 1].toDouble()
                val ex = points[q * 2] - px; val ey = points[q * 2 + 1] - py
                val length = ex * ex + ey * ey
                val s = if (length < 1e-18) 0.0 else (((x - px) * ex + (y - py) * ey) / length).coerceIn(0.0, 1.0)
                val distance = (px + s * ex - x).pow(2) + (py + s * ey - y).pow(2)
                if (distance < best) { best = distance; index = intArrayOf(p, q, q); weight = floatArrayOf((1 - s).toFloat(), s.toFloat(), 0f) }
            }
        }
        return index to weight
    }
}
