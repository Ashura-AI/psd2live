package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.runtime.eval.EPS_KEY
import org.umamo.runtime.eval.cellsByLinearIndex
import org.umamo.runtime.eval.gridCorners
import org.umamo.runtime.model.*
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * The keyform tools a deform session uses beside the brushes, as Live2D editors offer them on a keyform: `reset` puts
 * the shape at a key back to its default-key shape (or a mesh to its undeformed rest) - with `all_keys` every key of
 * the object, restoring a deformer to the shape it had when it was first keyed; `interpolate` remakes the shape at a
 * key from the keys on either side of it along one parameter, as if the key were not there; `paste` writes the move
 * another key (of the same object, or of one of its kind with the same points and parent) makes, optionally blended in
 * by a ratio, flipped, or with its opacity, draw order and colours; and `multi_key` sets the opacity, draw order or
 * colours of many keyforms of many objects at once. Shape tools take `vertices` to touch only those points (a mesh's
 * vertices, a warp's control points), the rest keeping the shape at the key.
 *
 * Like `mirror`, they are materialized when compiled ([RigAuthoringJournal.compile]): each becomes ordinary `set`s of
 * what it computes, so replay never reads another key again. A geometry `set` of a keyform a generator owns is captured
 * as a `generated_override` like any other; the channel `set`s of paste and `multi_key` are ordinary channel keyforms.
 *
 * A paste carries moves, not places: a mesh's offsets from its rest, a warp's or rotation's offset from its default-key
 * form, so a shape copied from one object lands on another in the same proportions. The copied side is a [Snapshot],
 * taken once - the editor's clipboard keeps it, so what a paste writes is what was copied.
 *
 * Fields: `target`, `key` (the key written; it names every directly bound geometry axis), `vertices`; `reset` takes
 * `to` (`default`, the default; `rest`, meshes only), `along` (the parameters taken to their default, default all of
 * the key's) and `all_keys` (in place of `key`); `interpolate` takes `parameter` (default the one ordinary axis the key
 * sits between two keys of); `paste` takes `from` (the source key, missing axes at their defaults), `destination`
 * (default the target), `ratio` (0..1, default 1: how far the key moves toward the pasted values), `flip`
 * (`horizontal` or `vertical`: a warp's columns or rows reversed, a mesh's vertices paired by their mirrored rest
 * places, each move turned around; a rotation's angle reversed) and `channels` (`geometry`, `opacity`, `drawOrder`,
 * `multiplyColor`, `screenColor`; default geometry). `multi_key` takes `targets`, `keys` (partial keys a keyform must
 * match one of; default every keyform), `ranges` (per parameter, the values a keyform on it may have), the values
 * `opacity`, `drawOrder`, `multiplyColor`, `screenColor`, and `method` (`overwrite`, the default; `add`, `multiply`,
 * `screen` - draw order takes only overwrite and add). Each channel is written at its own keys: those of its track, or
 * the object's geometry keys while it has none.
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
        PASTE to setOf("op", "target", "from", "destination", "key", "vertices", "ratio", "flip", "channels"),
        MULTI_KEY to setOf("op", "targets", "keys", "ranges", "method") + CHANNELS.keys,
    )

    /**
     * A shape and its look copied at one key: what is needed to paste it later without reading the source again - its
     * place in the rig ([parent], [rows] and [columns], [indices]), the [shape] at the key, the [reference] its moves are
     * measured from (a mesh's rest, a warp's or rotation's default-key form) and its channel values.
     */
    internal class Snapshot(
        val kind: String,
        val id: String,
        val parent: String?,
        val rows: Int?,
        val columns: Int?,
        val indices: IntArray?,
        val shape: FloatArray,
        val reference: FloatArray,
        val channels: Map<String, ChannelValue>,
    ) {
        val pointCount: Int get() = if (kind == "rotation") 0 else shape.size / 2
    }

    /** [text]'s shape and channels at [from] (missing axes at their defaults). */
    fun snapshot(model: PuppetModel, text: String, from: Map<String, Float>): Snapshot {
        from.keys.forEach { id -> require(model.parameters.any { it.id.raw == id }) { "Unknown parameter: $id" } }
        val shape = Shape.of(model, text)
        val owner = RigAuthoringJournal.target(model, text).asKeyformOwner()
        val channels = CHANNELS.filter { (_, channel) -> shape.kind == "mesh" || channel != FormChannel.DRAW_ORDER }
            .mapValues { (_, channel) -> channelAt(model, owner, channel, from) }
        return Snapshot(shape.kind, shape.id, shape.parent, shape.rows, shape.columns, shape.indices, shape.at(from), shape.reference(), channels)
    }

    /**
     * Why [snapshot]'s shape cannot be pasted onto [destination], or null when it can: the destination must be of its
     * kind with the same points - a mesh the same triangles, a warp the same rows and columns - and, when it is another
     * object, under the same parent, since a move means the same only in the same space. The same object is held to
     * its points too: its mesh or lattice may have been rebuilt since the copy.
     */
    fun misfit(model: PuppetModel, snapshot: Snapshot, destination: String): String? {
        val other = runCatching { Shape.of(model, destination) }.getOrElse { return it.message }
        if (other.kind != snapshot.kind) return "Paste source and destination must be the same kind (got ${snapshot.kind} and ${other.kind})"
        val same = other.id == snapshot.id
        if (!same && other.parent != snapshot.parent) return "${other.id} is under ${other.parent ?: "the root"}, not under ${snapshot.parent ?: "the root"} as ${snapshot.id}; a move pastes only within one parent"
        return when (other.kind) {
            "mesh" -> if (other.pointCount != snapshot.pointCount || !other.indices.contentEquals(snapshot.indices))
                (if (same) "${other.id} has other points or triangles than when it was copied; copy it again" else "${other.id} has other points or triangles than ${snapshot.id}") else null
            "warp" -> if (other.rows != snapshot.rows || other.columns != snapshot.columns)
                (if (same) "${other.id} is a ${other.rows}×${other.columns} warp now, ${snapshot.rows}×${snapshot.columns} when it was copied; copy it again"
                else "${other.id} is a ${other.rows}×${other.columns} warp, ${snapshot.id} ${snapshot.rows}×${snapshot.columns}") else null
            else -> null
        }
    }

    fun compile(model: PuppetModel, command: JsonObject): List<JsonObject> {
        val op = command.text("op")
        val unexpected = command.keys - FIELDS.getValue(op)
        require(unexpected.isEmpty()) { "Unexpected $op field: ${unexpected.first()}" }
        if (op == MULTI_KEY) return multiKey(model, command)
        if (op == PASTE) {
            val channels = command["channels"]?.jsonArray?.map { it.jsonPrimitive.content } ?: listOf("geometry")
            return paste(model, snapshot(model, command.text("target"), command.coordinate("from")),
                command["destination"]?.jsonPrimitive?.content ?: command.text("target"), command.coordinate("key"),
                command["vertices"]?.jsonArray?.map { it.jsonPrimitive.int }?.toSet(), ratioOf(command),
                command["flip"]?.jsonPrimitive?.content, channels)
        }
        if (op == RESET && command["all_keys"]?.jsonPrimitive?.boolean == true) {
            require("key" !in command && "vertices" !in command) { "all_keys resets every key; leave out key and vertices" }
            val shape = Shape.of(model, command.text("target"))
            val keys = cellKeys(shape.grid)
            require(keys.isNotEmpty()) { "${shape.id} has no keyforms to restore" }
            return keys.map { key -> shapeTool(model, JsonObject(command - "all_keys" + ("key" to key.json()))) }
        }
        return listOf(shapeTool(model, command))
    }

    /**
     * The `set`s pasting [snapshot] onto [destination] at [key] writes: its geometry when [channels] names it (the
     * copied moves, flipped by [flip], blended by [ratio], kept to [vertices]) and its channel values, blended alike.
     */
    fun paste(model: PuppetModel, snapshot: Snapshot, destination: String, key: Map<String, Float>, vertices: Set<Int>?,
              ratio: Float, flip: String?, channels: List<String>): List<JsonObject> {
        require(channels.isNotEmpty() && channels.all { it == "geometry" || it in CHANNELS }) { "channels are geometry, ${CHANNELS.keys.joinToString()}" }
        require(ratio in 0f..1f) { "ratio is 0..1" }
        require(flip == null || flip == "horizontal" || flip == "vertical") { "flip is horizontal or vertical" }
        val target = Shape.of(model, destination)
        val result = mutableListOf<JsonObject>()
        if ("geometry" in channels) {
            misfit(model, snapshot, destination)?.let { throw IllegalArgumentException(it) }
            checkKey(model, target, key)
            val picked = checkVertices(target, vertices)
            var moves = FloatArray(snapshot.shape.size) { snapshot.shape[it] - snapshot.reference[it] }
            if (flip != null) moves = flipped(snapshot, moves, flip == "horizontal")
            val reference = target.reference()
            var pasted = FloatArray(moves.size) { reference[it] + moves[it] }
            if (ratio < 1f) {
                val current = target.at(key)
                pasted = FloatArray(pasted.size) { current[it] + (pasted[it] - current[it]) * ratio }
            }
            result += shapeSet(target, key, keepTo(target, key, pasted, picked))
        }
        val names = channels.filter { it != "geometry" }
        if (names.isNotEmpty()) {
            require(key.isNotEmpty()) { "Specify the exact destination key" }
            val ref = RigAuthoringJournal.target(model, destination)
            result += channelSet(ref, key, names.associateWith { name ->
                val copied = requireNotNull(snapshot.channels[name]) { "${snapshot.id} has no $name to paste" }
                blend(channelAt(model, ref.asKeyformOwner(), CHANNELS.getValue(name), key), copied, ratio)
            })
        }
        return result
    }

    /**
     * [moves] of [snapshot] flipped: a rotation's angle change reversed and its origin's move turned; a warp's columns
     * ([horizontal]) or rows reversed; a mesh's vertices paired with the one whose rest mirrors theirs across the rest's
     * middle - refused when the rest is not symmetric. Each point takes its partner's move, turned around.
     */
    private fun flipped(snapshot: Snapshot, moves: FloatArray, horizontal: Boolean): FloatArray {
        val sx = if (horizontal) -1f else 1f
        val sy = if (horizontal) 1f else -1f
        if (snapshot.kind == "rotation") return floatArrayOf(moves[0] * sx, moves[1] * sy, -moves[2], moves[3])
        val partner: IntArray = if (snapshot.kind == "warp") {
            val rows = requireNotNull(snapshot.rows); val columns = requireNotNull(snapshot.columns)
            IntArray(snapshot.pointCount) { i ->
                val r = i / (columns + 1); val c = i % (columns + 1)
                if (horizontal) r * (columns + 1) + (columns - c) else (rows - r) * (columns + 1) + c
            }
        } else {
            val rest = snapshot.reference
            val axis = if (horizontal) 0 else 1
            val along = rest.indices.filter { it % 2 == axis }.map { rest[it] }
            val middle = (along.min() + along.max()) / 2f
            val xs = rest.indices.filter { it % 2 == 0 }.map { rest[it] }; val ys = rest.indices.filter { it % 2 == 1 }.map { rest[it] }
            val tolerance = max(hypot(xs.max() - xs.min(), ys.max() - ys.min()) * 0.02f, 1e-6f)
            // Points bucketed by cells of the tolerance, so each mirrored place looks only at the nine cells around it.
            fun cell(v: Float) = kotlin.math.floor(v / tolerance).toLong()
            val buckets = HashMap<Pair<Long, Long>, MutableList<Int>>()
            for (i in 0 until snapshot.pointCount) buckets.getOrPut(cell(rest[i * 2]) to cell(rest[i * 2 + 1])) { mutableListOf() } += i
            val asymmetric = "${snapshot.id} is not symmetric at rest, so its points have no mirrored partners; use mirror instead"
            val partners = IntArray(snapshot.pointCount) { i ->
                val mx = if (horizontal) 2 * middle - rest[i * 2] else rest[i * 2]
                val my = if (horizontal) rest[i * 2 + 1] else 2 * middle - rest[i * 2 + 1]
                val cx = cell(mx); val cy = cell(my)
                var best = -1; var distance = Float.MAX_VALUE
                for (dx in -1L..1L) for (dy in -1L..1L) for (j in buckets[(cx + dx) to (cy + dy)].orEmpty()) {
                    val d = hypot(rest[j * 2] - mx, rest[j * 2 + 1] - my)
                    if (d < distance) { distance = d; best = j }
                }
                require(best >= 0 && distance <= tolerance) { asymmetric }
                best
            }
            // One to one: two points sharing a partner would both take its move.
            require(partners.toSet().size == partners.size) { asymmetric }
            partners
        }
        return FloatArray(moves.size) { k -> val j = partner[k / 2]; moves[j * 2 + k % 2] * (if (k % 2 == 0) sx else sy) }
    }

    private fun checkKey(model: PuppetModel, target: Shape, key: Map<String, Float>) {
        require(key.isNotEmpty()) { "Specify the exact destination key" }
        key.keys.forEach { id -> require(model.parameters.any { it.id.raw == id }) { "Unknown parameter: $id" } }
        require(target.axes.all { it in key }) { "KEY_INCOMPLETE: include all directly bound geometry axes of ${target.id}" }
    }

    private fun checkVertices(target: Shape, vertices: Set<Int>?): Set<Int>? = vertices?.toSortedSet()?.also { picked ->
        require(target.kind != "rotation") { "A rotation has no points to pick; leave out vertices" }
        require(picked.isNotEmpty() && picked.all { it in 0 until target.pointCount }) { "vertices index the ${target.pointCount} points of ${target.id}" }
    }

    /** [written] at [picked] only, the rest of [target] keeping its shape at [key]. */
    private fun keepTo(target: Shape, key: Map<String, Float>, written: FloatArray, picked: Set<Int>?): FloatArray =
        if (picked == null) written else target.at(key).also { current ->
            for (v in picked) { current[v * 2] = written[v * 2]; current[v * 2 + 1] = written[v * 2 + 1] }
        }

    private fun shapeSet(target: Shape, key: Map<String, Float>, shape: FloatArray) = buildJsonObject {
        put("op", "set"); put("target", "${target.kind}:${target.id}")
        put("key", key.json())
        put("geometry", target.geometry(shape))
    }

    /** The `set` of the shape [command] (a reset or interpolate at one key) computes. */
    private fun shapeTool(model: PuppetModel, command: JsonObject): JsonObject {
        val op = command.text("op")
        val target = Shape.of(model, command.text("target"))
        val key = command.coordinate("key")
        checkKey(model, target, key)
        val picked = checkVertices(target, command["vertices"]?.jsonArray?.map { it.jsonPrimitive.int }?.toSet())
        val parameters = model.parameters.associateBy { it.id.raw }
        val defaults = parameters.mapValues { it.value.default }
        val written: FloatArray = when (op) {
            RESET -> when (command["to"]?.jsonPrimitive?.content ?: "default") {
                "rest" -> {
                    require(target.kind == "mesh") { "Only a mesh has a rest shape apart from its default key; use to: default" }
                    require("along" !in command) { "along applies to to: default" }
                    target.reference()
                }
                "default" -> {
                    val along = command["along"]?.jsonArray?.map { it.jsonPrimitive.content } ?: key.keys.toList()
                    along.forEach { require(it in key) { "along names a parameter of the key; $it is not one" } }
                    target.at(key + along.associateWith { defaults.getValue(it) })
                }
                else -> throw IllegalArgumentException("to is default or rest")
            }
            else -> {
                val grid = target.grid
                val interior = target.axes.filter { id -> parameters[id]?.kind == ParameterKind.NORMAL && neighbours(grid, id, key.getValue(id)) != null }
                val parameter = command["parameter"]?.jsonPrimitive?.content ?: interior.singleOrNull()
                    ?: throw IllegalArgumentException(if (interior.isEmpty()) "${target.id} has no keys on both sides of this key"
                        else "The key sits between keys of ${interior.joinToString()}; name the parameter")
                require(parameters[parameter]?.kind == ParameterKind.NORMAL) { "Interpolate reads ordinary keys; $parameter is not one" }
                require(parameter in key) { "parameter must be one of the key's axes" }
                val value = key.getValue(parameter)
                val (below, above) = requireNotNull(neighbours(grid, parameter, value)) { "${target.id} has no keys of $parameter on both sides of $value" }
                val t = (value - below) / (above - below)
                val low = target.at(key + (parameter to below))
                val high = target.at(key + (parameter to above))
                FloatArray(low.size) { low[it] + (high[it] - low[it]) * t }
            }
        }
        return shapeSet(target, key, keepTo(target, key, written, picked))
    }

    private fun ratioOf(command: JsonObject): Float = (command["ratio"]?.jsonPrimitive?.float ?: 1f).also {
        require(it in 0f..1f) { "ratio is 0..1" }
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
        require("drawOrder" !in values || method in setOf("overwrite", "add")) { "Draw order is overwritten or added to, not multiplied or screened" }
        val filters = command["keys"]?.jsonArray?.map { it.jsonObject.mapValues { v -> v.value.jsonPrimitive.float } }
        val ranges = command["ranges"]?.jsonObject?.mapValues { (_, values) -> values.jsonArray.map { it.jsonPrimitive.float } }
        val targets = command.getValue("targets").jsonArray.map { RigAuthoringJournal.target(model, it.jsonPrimitive.content) }
        require(targets.isNotEmpty()) { "Name at least one target" }
        return targets.flatMap { target ->
            require(target.kind != RigTargetKind.GLUE) { "multi_key edits meshes, deformers and parts" }
            if (target.kind == RigTargetKind.WARP_DEFORMER || target.kind == RigTargetKind.ROTATION_DEFORMER)
                require("drawOrder" !in values) { "A deformer has no draw order" }
            val owner = target.asKeyformOwner()
            values.flatMap { (name, value) ->
                val channel = CHANNELS.getValue(name)
                channelKeys(model, target, channel).filter { key ->
                    (filters == null || filters.any { filter -> filter.all { (id, v) -> key[id]?.let { abs(it - v) <= EPS_KEY } == true } }) &&
                        (ranges == null || ranges.all { (id, allowed) -> key[id]?.let { k -> allowed.any { abs(it - k) <= EPS_KEY } } ?: true })
                }.map { key -> channelSet(target, key, mapOf(name to combine(channelAt(model, owner, channel, key), value, method, channel))) }
            }
        }.also { require(it.isNotEmpty()) { "No keyform matches; the targets have no keys in that range" } }
    }

    /**
     * The keys [channel] of [target] is written at: those of its own track, or while it has none the object's
     * geometry keys (a part's: those of its other channel tracks), so writing it adds no axis it does not need.
     */
    private fun channelKeys(model: PuppetModel, target: RigTargetRef, channel: FormChannel): List<Map<String, Float>> {
        val grids = model.channelGridsOf(target.asKeyformOwner())
        grids?.get(channel)?.let { return cellKeys(it) }
        val geometry: KeyformGrid<*>? = when (target.kind) {
            RigTargetKind.ART_MESH -> model.drawables.firstOrNull { it.id.raw == target.id }?.geometryGrid
            RigTargetKind.WARP_DEFORMER -> (model.deformers.firstOrNull { it.id.raw == target.id } as? Deformer.Warp)?.geometryGrid
            RigTargetKind.ROTATION_DEFORMER -> (model.deformers.firstOrNull { it.id.raw == target.id } as? Deformer.Rotation)?.geometryGrid
            else -> null
        }
        if (geometry != null && geometry.axes.isNotEmpty()) return cellKeys(geometry)
        return union(grids?.gridsByChannel?.values.orEmpty().toList())
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
        return union(grids)
    }

    /** The keys of every axis of [grids], in every combination. */
    private fun union(grids: List<KeyformGrid<*>>): List<Map<String, Float>> {
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
        val columns: Int? = rest?.columns
        val pointCount: Int = rest?.let { it.base.size / 2 } ?: 0
        val parent: String? = when (kind) {
            "mesh" -> model.drawables.first { it.id.raw == id }.parentDeformerId?.raw
            else -> model.deformers.first { it.id.raw == id }.parent?.raw
        }
        val indices: IntArray? = if (kind == "mesh") model.drawables.first { it.id.raw == id }.mesh?.indices else null

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

        /** What a move is measured from: a mesh's undeformed positions, a warp's or rotation's default-key form. */
        fun reference(): FloatArray = if (kind == "mesh") requireNotNull(rest).base.copyOf() else at(emptyMap())

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
