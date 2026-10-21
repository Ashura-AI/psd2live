package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.runtime.eval.EPS_KEY
import org.umamo.runtime.eval.cellsByLinearIndex
import org.umamo.runtime.eval.gridCorners
import org.umamo.runtime.model.*
import kotlin.math.abs

/**
 * The keyform tools a deform session uses beside the brushes, as Live2D editors offer them on a keyform: `reset` puts
 * the shape at a key back to its default-key shape (or a mesh to its undeformed rest) - with `all_keys` every key of
 * the object, restoring a deformer to the shape it had when it was first keyed; `interpolate` remakes the shape at a
 * key from the keys on either side of it along one parameter, as if the key were not there; `paste` writes the shape
 * another key (of the same or another object of its kind) shows, optionally blended in by a ratio, flipped, or with
 * its opacity, draw order and colours; and `multi_key` sets the opacity, draw order or colours of many keyforms of
 * many objects at once. Shape tools take `vertices` to touch only those points (a mesh's vertices, a warp's control
 * points), the rest keeping the shape at the key.
 *
 * Like `mirror`, they are materialized when compiled ([RigAuthoringJournal.compile]): each becomes ordinary `set`s of
 * what it computes, so replay never reads another key again, and an edit of a keyform a generator owns is captured as
 * a `generated_override` like any other `set`.
 *
 * Fields: `target`, `key` (the key written; it names every directly bound geometry axis), `vertices`; `reset` takes
 * `to` (`default`, the default; `rest`, meshes only), `along` (the parameters taken to their default, default all of
 * the key's) and `all_keys` (in place of `key`); `interpolate` takes `parameter` (default the one axis the key sits
 * between two keys of); `paste` takes `from` (the source key, missing axes at their defaults), `destination` (default
 * the target), `ratio` (0..1, default 1: how far the key moves toward the pasted shape), `flip` (`horizontal` or
 * `vertical`, about `flip_center`, default the pasted shape's own center) and `channels` (`geometry`, `opacity`,
 * `drawOrder`, `multiplyColor`, `screenColor`; default geometry). `multi_key` takes `targets`, `keys` (partial keys a
 * keyform must match one of; default every keyform), `ranges` (per parameter, the values a keyform on it may have), the values `opacity`, `drawOrder`, `multiplyColor`,
 * `screenColor`, and `method` (`overwrite`, the default; `add`, `multiply`, `screen`).
 */
internal object KeyformTools {
    const val RESET = "reset"
    const val INTERPOLATE = "interpolate"
    const val PASTE = "paste"
    const val MULTI_KEY = "multi_key"
    val OPS = setOf(RESET, INTERPOLATE, PASTE, MULTI_KEY)

    /** The channels besides geometry that paste and multi_key carry, by their command name. */
    val CHANNELS = linkedMapOf("opacity" to FormChannel.OPACITY, "drawOrder" to FormChannel.DRAW_ORDER,
        "multiplyColor" to FormChannel.MULTIPLY_COLOR, "screenColor" to FormChannel.SCREEN_COLOR)

    private val FIELDS = mapOf(
        RESET to setOf("op", "target", "key", "vertices", "to", "along", "all_keys"),
        INTERPOLATE to setOf("op", "target", "key", "vertices", "parameter"),
        PASTE to setOf("op", "target", "from", "destination", "key", "vertices", "ratio", "flip", "flip_center", "channels"),
        MULTI_KEY to setOf("op", "targets", "keys", "ranges", "method") + CHANNELS.keys,
    )

    fun compile(model: PuppetModel, command: JsonObject): List<JsonObject> {
        val op = command.text("op")
        val unexpected = command.keys - FIELDS.getValue(op)
        require(unexpected.isEmpty()) { "Unexpected $op field: ${unexpected.first()}" }
        if (op == MULTI_KEY) return multiKey(model, command)
        if (op == RESET && command["all_keys"]?.jsonPrimitive?.boolean == true) {
            require("key" !in command && "vertices" !in command) { "all_keys resets every key; leave out key and vertices" }
            val shape = Shape.of(model, command.text("target"))
            val keys = cellKeys(shape.grid)
            require(keys.isNotEmpty()) { "${shape.id} has no keyforms to restore" }
            return keys.map { key -> shapeTool(model, JsonObject(command - "all_keys" + ("key" to key.json()))) }
        }
        return listOfNotNull(shapeTool(model, command).takeIf { op != PASTE || channelsOf(command).contains("geometry") }) +
            (if (op == PASTE) pastedChannels(model, command) else emptyList())
    }

    private fun channelsOf(command: JsonObject): List<String> =
        (command["channels"]?.jsonArray?.map { it.jsonPrimitive.content } ?: listOf("geometry")).also { channels ->
            require(channels.isNotEmpty() && channels.all { it == "geometry" || it in CHANNELS }) {
                "channels are geometry, ${CHANNELS.keys.joinToString()}"
            }
        }

    /** The `set` of the shape [command] (a reset, interpolate or paste at one key) computes. */
    private fun shapeTool(model: PuppetModel, command: JsonObject): JsonObject {
        val op = command.text("op")
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
                var pasted = source.moved(source.at(from), destination)
                command["flip"]?.jsonPrimitive?.content?.let { flip ->
                    require(flip == "horizontal" || flip == "vertical") { "flip is horizontal or vertical" }
                    pasted = destination.flipped(pasted, flip == "horizontal", command["flip_center"]?.jsonPrimitive?.float)
                }
                val ratio = ratioOf(command)
                if (ratio < 1f) {
                    val current = destination.at(key)
                    pasted = FloatArray(pasted.size) { current[it] + (pasted[it] - current[it]) * ratio }
                }
                pasted
            }
        }
        val shape = if (vertices == null) written else destination.at(key).also { current ->
            for (v in vertices) { current[v * 2] = written[v * 2]; current[v * 2 + 1] = written[v * 2 + 1] }
        }
        return buildJsonObject {
            put("op", "set"); put("target", "${destination.kind}:${destination.id}")
            put("key", key.json())
            put("geometry", destination.geometry(shape))
        }
    }

    private fun ratioOf(command: JsonObject): Float = (command["ratio"]?.jsonPrimitive?.float ?: 1f).also {
        require(it in 0f..1f) { "ratio is 0..1" }
    }

    /** A paste's opacity, draw order and colours: the source's values at `from`, blended by `ratio` into the key's. */
    private fun pastedChannels(model: PuppetModel, command: JsonObject): List<JsonObject> {
        val names = channelsOf(command).filter { it != "geometry" }
        if (names.isEmpty()) return emptyList()
        val source = RigAuthoringJournal.target(model, command.text("target"))
        val destination = RigAuthoringJournal.target(model, command["destination"]?.jsonPrimitive?.content ?: command.text("target"))
        val from = command.coordinate("from")
        val key = command.coordinate("key")
        require(key.isNotEmpty()) { "Specify the exact destination key" }
        val ratio = ratioOf(command)
        return listOf(channelSet(destination, key, names.associateWith { name ->
            val channel = CHANNELS.getValue(name)
            blend(channelAt(model, destination.asKeyformOwner(), channel, key), channelAt(model, source.asKeyformOwner(), channel, from), ratio)
        }))
    }

    /** `multi_key`: each matching keyform of each target, its channels overwritten or combined with the given values. */
    private fun multiKey(model: PuppetModel, command: JsonObject): List<JsonObject> {
        val values = CHANNELS.keys.filter { it in command }.associateWith { name ->
            val element = command.getValue(name)
            when (CHANNELS.getValue(name)) {
                FormChannel.MULTIPLY_COLOR, FormChannel.SCREEN_COLOR -> element.jsonArray.map { it.jsonPrimitive.float }.let {
                    require(it.size == 3) { "$name is [r, g, b]" }
                    ChannelValue.Color(ColorRgb(it[0], it[1], it[2]))
                }
                else -> ChannelValue.Scalar(element.jsonPrimitive.float)
            }
        }
        require(values.isNotEmpty()) { "Give at least one of ${CHANNELS.keys.joinToString()}" }
        val method = command["method"]?.jsonPrimitive?.content ?: "overwrite"
        require(method in setOf("overwrite", "add", "multiply", "screen")) { "method is overwrite, add, multiply or screen" }
        val filters = command["keys"]?.jsonArray?.map { it.jsonObject.mapValues { v -> v.value.jsonPrimitive.float } }
        val ranges = command["ranges"]?.jsonObject?.mapValues { (_, values) -> values.jsonArray.map { it.jsonPrimitive.float } }
        val targets = command.getValue("targets").jsonArray.map { RigAuthoringJournal.target(model, it.jsonPrimitive.content) }
        require(targets.isNotEmpty()) { "Name at least one target" }
        return targets.flatMap { target ->
            val owner = target.asKeyformOwner()
            require(target.kind != RigTargetKind.GLUE) { "multi_key edits meshes, deformers and parts" }
            if (target.kind == RigTargetKind.WARP_DEFORMER || target.kind == RigTargetKind.ROTATION_DEFORMER)
                require(FormChannel.DRAW_ORDER.let { CHANNELS.entries.first { e -> e.value == it }.key } !in values) { "A deformer has no draw order" }
            val keys = keyformKeys(model, target).filter { key ->
                (filters == null || filters.any { filter -> filter.all { (id, v) -> key[id]?.let { abs(it - v) <= EPS_KEY } == true } }) &&
                    (ranges == null || ranges.all { (id, values) -> key[id]?.let { k -> values.any { abs(it - k) <= EPS_KEY } } ?: true })
            }
            keys.map { key ->
                channelSet(target, key, values.mapValues { (name, value) ->
                    combine(channelAt(model, owner, CHANNELS.getValue(name), key), value, method, CHANNELS.getValue(name))
                })
            }
        }.also { require(it.isNotEmpty()) { "No keyform matches; the targets have no keys in that range" } }
    }

    /** Every keyform key of [target]: its geometry axes and the axes its channels are keyed on, each at every key. */
    fun keyformKeys(model: PuppetModel, target: RigTargetRef): List<Map<String, Float>> {
        val grids = buildList<KeyformGrid<*>> {
            when (target.kind) {
                RigTargetKind.ART_MESH -> model.drawables.firstOrNull { it.id.raw == target.id }?.geometryGrid?.let(::add)
                RigTargetKind.WARP_DEFORMER -> (model.deformers.firstOrNull { it.id.raw == target.id } as? Deformer.Warp)?.geometryGrid?.let(::add)
                RigTargetKind.ROTATION_DEFORMER -> (model.deformers.firstOrNull { it.id.raw == target.id } as? Deformer.Rotation)?.geometryGrid?.let(::add)
                else -> Unit
            }
            model.channelGridsOf(target.asKeyformOwner())?.gridsByChannel?.values?.forEach(::add)
        }
        val axes = LinkedHashMap<String, MutableSet<Float>>()
        for (grid in grids) for (axis in grid.axes) axes.getOrPut(axis.parameterId.raw) { sortedSetOf() } += axis.keys.toList()
        if (axes.isEmpty()) return emptyList()
        return axes.entries.fold(listOf(emptyMap<String, Float>())) { keys, (id, values) ->
            keys.flatMap { key -> values.map { key + (id to it) } }
        }
    }

    /** What [owner]'s [channel] reads at [key]: its track interpolated there, or its static value. */
    fun channelAt(model: PuppetModel, owner: KeyformOwner, channel: FormChannel, key: Map<String, Float>): ChannelValue {
        val grid = model.channelGridsOf(owner)?.get(channel)
        if (grid != null) {
            val defaults = model.parameters.associate { it.id to it.default }
            val cells = cellsByLinearIndex(grid)
            val corners = requireNotNull(gridCorners(grid) { key[it.raw] ?: defaults[it] ?: 0f }) { "No $channel keyform at the key" }
            val parts = FloatArray(3)
            var color = false
            for (corner in corners) when (val value = requireNotNull(cells[corner.linearIndex]).form) {
                is ChannelValue.Scalar -> parts[0] += corner.weight * value.value
                is ChannelValue.Color -> { color = true; parts[0] += corner.weight * value.color.red; parts[1] += corner.weight * value.color.green; parts[2] += corner.weight * value.color.blue }
                is ChannelValue.Flag -> return value
            }
            return if (color) ChannelValue.Color(ColorRgb(parts[0], parts[1], parts[2])) else ChannelValue.Scalar(parts[0])
        }
        fun scalar(v: Float) = ChannelValue.Scalar(v)
        fun color(c: ColorRgb) = ChannelValue.Color(c)
        return when (owner) {
            is KeyformOwner.Drawable -> model.drawables.first { it.id == owner.id }.let {
                when (channel) { FormChannel.OPACITY -> scalar(it.opacity); FormChannel.DRAW_ORDER -> scalar(it.drawOrder)
                    FormChannel.MULTIPLY_COLOR -> color(it.multiplyColor); else -> color(it.screenColor) }
            }
            is KeyformOwner.Part -> model.parts.first { it.id == owner.id }.let {
                when (channel) { FormChannel.OPACITY -> scalar(it.composite.opacity); FormChannel.DRAW_ORDER -> scalar(it.drawOrder.toFloat())
                    FormChannel.MULTIPLY_COLOR -> color(it.composite.multiplyColor); else -> color(it.composite.screenColor) }
            }
            is KeyformOwner.Deformer -> when (val deformer = model.deformers.first { it.id == owner.id }) {
                is Deformer.Warp -> when (channel) { FormChannel.OPACITY -> scalar(deformer.opacity); FormChannel.MULTIPLY_COLOR -> color(deformer.multiplyColor)
                    FormChannel.SCREEN_COLOR -> color(deformer.screenColor); else -> throw IllegalArgumentException("A deformer has no draw order") }
                is Deformer.Rotation -> when (channel) { FormChannel.OPACITY -> scalar(deformer.opacity); FormChannel.MULTIPLY_COLOR -> color(deformer.multiplyColor)
                    FormChannel.SCREEN_COLOR -> color(deformer.screenColor); else -> throw IllegalArgumentException("A deformer has no draw order") }
            }
            else -> throw IllegalArgumentException("Channels of a glue are not edited here")
        }
    }

    private fun blend(current: ChannelValue, pasted: ChannelValue, ratio: Float): ChannelValue = when {
        current is ChannelValue.Scalar && pasted is ChannelValue.Scalar -> ChannelValue.Scalar(current.value + (pasted.value - current.value) * ratio)
        current is ChannelValue.Color && pasted is ChannelValue.Color -> ChannelValue.Color(ColorRgb(
            current.color.red + (pasted.color.red - current.color.red) * ratio,
            current.color.green + (pasted.color.green - current.color.green) * ratio,
            current.color.blue + (pasted.color.blue - current.color.blue) * ratio))
        else -> pasted
    }

    private fun combine(current: ChannelValue, value: ChannelValue, method: String, channel: FormChannel): ChannelValue {
        fun op(a: Float, b: Float) = when (method) {
            "add" -> a + b
            "multiply" -> a * b
            "screen" -> 1f - (1f - a) * (1f - b)
            else -> b
        }
        return when {
            current is ChannelValue.Scalar && value is ChannelValue.Scalar -> ChannelValue.Scalar(
                if (channel == FormChannel.DRAW_ORDER) op(current.value, value.value).coerceIn(0f, 1000f)
                else op(current.value, value.value).coerceIn(0f, 1f))
            current is ChannelValue.Color && value is ChannelValue.Color -> ChannelValue.Color(ColorRgb(
                op(current.color.red, value.color.red).coerceIn(0f, 1f), op(current.color.green, value.color.green).coerceIn(0f, 1f),
                op(current.color.blue, value.color.blue).coerceIn(0f, 1f)))
            else -> value
        }
    }

    private fun channelSet(target: RigTargetRef, key: Map<String, Float>, values: Map<String, ChannelValue>): JsonObject = buildJsonObject {
        val kind = when (target.kind) {
            RigTargetKind.ART_MESH -> "mesh"; RigTargetKind.WARP_DEFORMER -> "warp"; RigTargetKind.ROTATION_DEFORMER -> "rotation"
            RigTargetKind.PART -> "part"; else -> throw IllegalArgumentException("Channels of a glue are not edited here")
        }
        put("op", "set"); put("target", "$kind:${target.id}"); put("key", key.json())
        putJsonObject("channels") {
            for ((name, value) in values) when (value) {
                is ChannelValue.Scalar -> put(name, value.value)
                is ChannelValue.Color -> put(name, JsonArray(listOf(value.color.red, value.color.green, value.color.blue).map(::JsonPrimitive)))
                is ChannelValue.Flag -> Unit
            }
        }
    }

    /** Every cell key of [grid]. */
    private fun cellKeys(grid: KeyformGrid<*>?): List<Map<String, Float>> = grid?.cells.orEmpty().map { cell ->
        grid!!.axes.withIndex().associate { (i, axis) -> axis.parameterId.raw to axis.keys[cell.coordinate[i]] }
    }

    private fun Map<String, Float>.json() = JsonObject(mapValues { JsonPrimitive(it.value) })

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

        /**
         * [shape] flipped across a line through [center] (default its own middle): a mesh's or warp's points reflected
         * in x ([horizontal]) or y, a rotation's angle turned the other way.
         */
        fun flipped(shape: FloatArray, horizontal: Boolean, center: Float?): FloatArray {
            val result = shape.copyOf()
            if (kind == "rotation") {
                result[2] = -result[2]
                center?.let { c -> if (horizontal) result[0] = 2 * c - result[0] else result[1] = 2 * c - result[1] }
                return result
            }
            val axis = if (horizontal) 0 else 1
            val values = shape.indices.filter { it % 2 == axis }.map { shape[it] }
            val c = center ?: ((values.minOrNull() ?: 0f) + (values.maxOrNull() ?: 0f)) / 2f
            for (i in result.indices) if (i % 2 == axis) result[i] = 2 * c - result[i]
            return result
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
