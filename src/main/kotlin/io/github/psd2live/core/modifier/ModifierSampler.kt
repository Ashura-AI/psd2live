package io.github.psd2live.core.modifier

import io.github.psd2live.core.DocumentGenerators
import org.umamo.edit.withParameterCreated
import org.umamo.edit.withParameterKeys
import org.umamo.render.eval.DrawableSpaceMapping
import org.umamo.render.eval.drawableLocalPosed
import org.umamo.render.eval.drawableSpaceMapping
import org.umamo.runtime.eval.meshGridDefaultDeltas
import org.umamo.runtime.keyform.MeshDeltaInterpolator
import org.umamo.runtime.keyform.axisIndexOf
import org.umamo.runtime.keyform.isDense
import org.umamo.runtime.keyform.withKeyInserted
import org.umamo.runtime.model.*
import kotlin.math.hypot
import kotlin.math.max

/**
 * Samples the document's modifiers into ordinary mesh keyforms, after the journal and the other generators.
 *
 * Each mesh's stack is the enabled modifiers naming it, in document order; each modifier takes the shape the ones
 * before it made, starting - as Blender's modifiers start after the shape keys - from the mesh as its own keyforms
 * pose it, on the canvas through its parents at the default pose. The stack is sampled on the parameters its
 * settings are keyed on (their keys, plus keys added between them until linear interpolation is within the stack's
 * tolerance of the modifiers) together with the mesh's own keyform axes, over the product of all of them. Each
 * sample is taken back into the mesh's parent space at the default pose and added to the mesh's keyform at that
 * pose, so the modifier rides on its parents and on what the keyforms do. Settings that are not keyed anywhere act
 * on every keyform alike; on an unkeyed mesh they move its rest shape.
 *
 * A stack that cannot be sampled - a mesh or parameter gone, a vertex group missing, a parent that cannot be inverted,
 * too many keyforms - is left out for that mesh and reported ([Issue]), never failing the build.
 */
object ModifierSampler {
	/** The graph node of all modifiers ([DocumentGenerators]); it owns the keyforms the stacks add. */
	const val NODE = "modifiers"

	/** Past this many cells a mesh's keyform grid is left alone. */
	private const val MAX_CELLS = 200_000

	enum class IssueKind(val code: String) {
		TARGET_MISSING("target_missing"),
		PARAMETER_MISSING("parameter_missing"),
		VERTEX_GROUP_MISSING("vertex_group_missing"),
		UNMAPPABLE("unmappable"),
		TOO_MANY_KEYFORMS("too_many_keyforms"),
		TOLERANCE_EXCEEDED("tolerance_exceeded"),
	}

	/** What kept [modifier] from [target] as recorded, or how far the sampling stayed from it ([error] in canvas pixels). */
	data class Issue(val kind: IssueKind, val modifier: String, val target: String, val detail: String = "", val error: Float? = null)

	class Outcome(val model: PuppetModel, val issues: List<Issue>)

	/** Each mesh's stack: the enabled modifiers that name it on [model] (a part's meshes need it), in document order. */
	fun stacks(modifiers: List<RigModifier>, model: PuppetModel? = null): Map<String, List<RigModifier>> {
		val stacks = LinkedHashMap<String, MutableList<RigModifier>>()
		for (modifier in modifiers) if (modifier.enabled) for (mesh in modifier.target.resolve(model)) stacks.getOrPut(mesh) { ArrayList() } += modifier
		return stacks
	}

	/**
	 * What the modifiers own, as graph ids: the parameters they made and their keyforms on each mesh along each driving
	 * parameter. A part's meshes are known only with [model]; without it they are left out.
	 */
	fun claims(modifiers: List<RigModifier>, model: PuppetModel? = null): List<String> =
		modifiers.flatMap { m -> m.created.map { "parameter:${it.id.raw}" } } +
			stacks(modifiers, model).flatMap { (mesh, stack) -> stack.flatMap { it.drivers }.distinct().map { DocumentGenerators.keyform("mesh", mesh, it) } }

	fun apply(model: PuppetModel, modifiers: List<RigModifier>): PuppetModel = run(model, modifiers, write = true).model

	/** What sampling [modifiers] onto [model] (the rig before them) reports, without writing anything. */
	fun issues(model: PuppetModel, modifiers: List<RigModifier>): List<Issue> = run(model, modifiers, write = false).issues

	/** [model] with the parameters [modifiers] made, enabled or not: a disabled modifier keeps them for when it is turned on. */
	fun withCreated(model: PuppetModel, modifiers: List<RigModifier>): PuppetModel {
		var current = model
		for (p in modifiers.flatMap { it.created }) {
			if (current.parameters.any { it.id == p.id }) continue
			current = current.withParameterCreated(p.id, p.name, p.kind)
			current = current.copy(parameters = current.parameters.map { if (it.id == p.id) p.copy(keys = it.keys) else it })
		}
		return current
	}

	/** One mesh's stack evaluated exactly at a pose: what the canvas shows and the drag preview draws. */
	class Stack internal constructor(
		val mesh: String,
		val modifiers: List<RigModifier>,
		private val rest: FloatArray,
		/** Per modifier its vertex weights, null for all ones. */
		internal val weights: Map<String, FloatArray?>,
		private val defaults: Map<String, Float>,
	) {
		/** The rest shape on the canvas (interleaved x, y, y down). */
		val restCanvas: FloatArray get() = rest.copyOf()

		/** The stack's canvas positions at [pose] (parameter values; the rest at their defaults), from the rest shape. */
		fun canvasAt(pose: Map<String, Float>): FloatArray = canvasFrom(rest, pose)

		/** The stack's canvas positions at [pose] from [start], the canvas shape the first modifier takes. */
		fun canvasFrom(start: FloatArray, pose: Map<String, Float>): FloatArray {
			var positions = start
			for (modifier in modifiers) {
				val values = modifier.definition.settings.associate { s -> s.key to modifier.value(s.key).at { pose[it] ?: defaults[it] ?: 0f } }
				val out = modifier.definition.deform(positions, values)
				weights[modifier.id]?.let { w -> for (i in out.indices) out[i] = positions[i] + (out[i] - positions[i]) * w[i / 2] }
				positions = out
			}
			return positions
		}
	}

	/**
	 * [mesh]'s stack on [model], or an issue for each modifier that cannot act on it: the mesh gone, a vertex group or
	 * a parameter its settings are keyed on missing. Modifiers with an issue drop out of the stack.
	 */
	fun stack(model: PuppetModel, mesh: String, modifiers: List<RigModifier>, issues: MutableList<Issue> = ArrayList()): Pair<Stack, DrawableSpaceMapping>? {
		val drawable = model.drawables.firstOrNull { it.id.raw == mesh }
		val geometry = drawable?.mesh
		if (drawable == null || geometry == null) {
			modifiers.forEach { issues += Issue(IssueKind.TARGET_MISSING, it.id, mesh) }
			return null
		}
		val parameters = model.parameters.associateBy { it.id.raw }
		val weights = HashMap<String, FloatArray?>()
		val kept = modifiers.filter { modifier ->
			val missing = modifier.drivers.filter { it !in parameters }
			if (missing.isNotEmpty()) { issues += Issue(IssueKind.PARAMETER_MISSING, modifier.id, mesh, missing.joinToString()); return@filter false }
			val w = modifier.weights ?: return@filter true.also { weights[modifier.id] = null }
			val group = model.vertexGroups.firstOrNull { it.drawableId == drawable.id && it.name == w.group && it.weights.size == geometry.vertexCount }
			if (group == null) { issues += Issue(IssueKind.VERTEX_GROUP_MISSING, modifier.id, mesh, w.group); return@filter false }
			weights[modifier.id] = if (w.invert) FloatArray(group.weights.size) { 1 - group.weights[it] } else group.weights
			true
		}
		if (kept.isEmpty()) return null
		val mapping = drawableSpaceMapping(model, emptyMap(), drawable.id)
		if (mapping == null) {
			kept.forEach { issues += Issue(IssueKind.UNMAPPABLE, it.id, mesh) }
			return null
		}
		val defaults = model.parameters.associate { it.id.raw to it.default }
		val local = local(model, drawable)
		val world = mapping.localToWorld(local)
		val canvas = FloatArray(world.size) { if (it % 2 == 0) world[it] else -world[it] }
		return Stack(mesh, kept, canvas, weights, defaults) to mapping
	}

	/** The drawable's local shape at the default pose: the rest mesh plus its keyforms there. */
	internal fun local(model: PuppetModel, drawable: Drawable): FloatArray {
		val positions = drawable.mesh!!.positions
		val defaults = model.parameters.associate { it.id to it.default }
		val deltas = meshGridDefaultDeltas(drawable) { defaults[it] ?: 0f }
		return if (deltas == null) positions.copyOf() else FloatArray(positions.size) { positions[it] + deltas[it] }
	}

	/**
	 * What [modifiers] (one mesh's stack, as [stacks] gives it) add to [mesh] on [model]: the parameters they are
	 * sampled on with their keys, and the offsets in the mesh's parent space at each combination of those keys
	 * (a constant when nothing is keyed). Null when the stack cannot act on the mesh; [issues] says why.
	 */
	fun offsets(model: PuppetModel, mesh: String, modifiers: List<RigModifier>, issues: MutableList<Issue> = ArrayList()):
		Pair<List<ModifierValue.Keyed.Axis>, ModifierValue>? {
		val (stack, mapping) = stack(model, mesh, modifiers, issues) ?: return null
		val drawable = model.drawables.first { it.id.raw == mesh }
		val local = local(model, drawable)
		val own = drawable.geometryGrid?.axes.orEmpty()
		// The mesh as its keyforms pose it, in its parent space and on the canvas at the default pose.
		fun localAt(pose: Map<String, Float>): FloatArray =
			if (own.isEmpty()) local else drawableLocalPosed(model, pose.mapKeys { ParameterId(it.key) }, drawable.id) ?: local
		fun canvasOf(l: FloatArray) = mapping.localToWorld(l).let { w -> FloatArray(w.size) { if (it % 2 == 0) w[it] else -w[it] } }
		val shape: (Map<String, Float>) -> FloatArray = { pose -> stack.canvasFrom(canvasOf(localAt(pose)), pose) }
		val drivers = stack.modifiers.flatMap { it.drivers }.distinct()
		val parameters = drivers.map { id -> model.parameters.first { it.id.raw == id } }
		val tolerance = stack.modifiers.minOf { it.sampling.tolerance }
		val maxKeys = stack.modifiers.minOf { it.sampling.maxKeys }
		val keys = parameters.map { p -> keys(p, stack.modifiers, tolerance, maxKeys, shape) }
		val worst = keys.maxOfOrNull { it.second } ?: 0f
		if (worst > tolerance) stack.modifiers.forEach { issues += Issue(IssueKind.TOLERANCE_EXCEEDED, it.id, mesh, error = worst) }
		// The driving parameters with their sampled keys, then the mesh's own axes; a parameter that is both takes both's keys.
		val axes = parameters.mapIndexed { i, p ->
			val keyed = own.firstOrNull { it.parameterId == p.id }?.keys?.toList().orEmpty()
			ModifierValue.Keyed.Axis(p.id.raw, merged(keys[i].first.toList() + keyed))
		} + own.filter { a -> a.parameterId.raw !in drivers }.map { ModifierValue.Keyed.Axis(it.parameterId.raw, it.keys.copyOf()) }
		val combinations = axes.fold(1L) { n, a -> n * a.keys.size }
		if (combinations > MAX_CELLS) {
			stack.modifiers.forEach { issues += Issue(IssueKind.TOO_MANY_KEYFORMS, it.id, mesh, "$combinations") }
			return null
		}
		val offsets = ArrayList<FloatArray>(combinations.toInt())
		val all = (0 until local.size / 2).toSet()
		for (index in 0 until combinations.toInt()) {
			var rest = index
			val pose = axes.associate { a -> a.parameter to a.keys[rest % a.keys.size].also { rest /= a.keys.size } }
			val base = localAt(pose)
			val canvas = stack.canvasFrom(canvasOf(base), pose)
			val world = FloatArray(canvas.size) { if (it % 2 == 0) canvas[it] else -canvas[it] }
			val solved = mapping.worldToLocal(world, base, all)
			offsets += FloatArray(local.size) { solved[it] - base[it] }
		}
		return axes to if (axes.isEmpty()) ModifierValue.Constant(offsets.single()) else ModifierValue.Keyed(axes, offsets)
	}

	/** [keys] sorted, with keys closer than a keyform key's snap merged. */
	private fun merged(keys: List<Float>): FloatArray = keys.sorted().fold(ArrayList<Float>()) { out, k ->
		if (out.isEmpty() || k - out.last() > ModifierValue.EPS) out += k; out
	}.toFloatArray()

	/**
	 * [axes] and [table] with every axis not in [keep] taken at its parameter's default from [defaults]: the offsets an
	 * apply writes on the authored mesh, which lacks the axes the other generators add.
	 */
	internal fun reduced(axes: List<ModifierValue.Keyed.Axis>, table: ModifierValue, keep: Set<String>, defaults: Map<String, Float>):
		Pair<List<ModifierValue.Keyed.Axis>, ModifierValue> {
		val kept = axes.filter { it.parameter in keep }
		if (kept.size == axes.size) return axes to table
		val count = kept.fold(1) { n, a -> n * a.keys.size }
		val values = List(count) { index ->
			var rest = index
			val pose = kept.associate { a -> a.parameter to a.keys[rest % a.keys.size].also { rest /= a.keys.size } }
			table.at { pose[it] ?: defaults[it] ?: 0f }
		}
		return kept to if (kept.isEmpty()) ModifierValue.Constant(values.single()) else ModifierValue.Keyed(kept, values)
	}

	private fun run(model: PuppetModel, modifiers: List<RigModifier>, write: Boolean): Outcome {
		if (modifiers.isEmpty()) return Outcome(model, emptyList())
		var current = withCreated(model, modifiers)
		val issues = ArrayList<Issue>()
		for ((mesh, stack) in stacks(modifiers, current)) {
			val (axes, table) = offsets(current, mesh, stack, issues) ?: continue
			if (!write) continue
			val drawable = current.drawables.first { it.id.raw == mesh }
			val grid = withOffsets(drawable.geometryGrid, drawable.mesh!!.positions.size, axes, table)
			if (grid == null) { stack.forEach { issues += Issue(IssueKind.TOO_MANY_KEYFORMS, it.id, mesh, "sparse") }; continue }
			current = current.copy(drawables = current.drawables.map { if (it.id == drawable.id) it.copy(geometryGrid = grid) else it })
			for (axis in axes) {
				val id = ParameterId(axis.parameter)
				val authored = current.parameters.first { it.id == id }.keys ?: continue
				current = current.withParameterKeys(id, (authored + axis.keys.toList()).distinct().sorted())
			}
		}
		return Outcome(current, issues)
	}

	/**
	 * The keys [parameter] is sampled at, with the largest distance (canvas pixels) left between linear
	 * interpolation and the stack midway between two of them. It starts from the keys the settings have on it and its
	 * default, within its range; the other parameters stay at their defaults while it is refined, and a key is added
	 * in the middle of the worst span until every span is within [tolerance] or [maxKeys] is reached.
	 */
	private fun keys(parameter: Parameter, modifiers: List<RigModifier>, tolerance: Float, maxKeys: Int,
					 shape: (Map<String, Float>) -> FloatArray): Pair<FloatArray, Float> {
		val raw = parameter.id.raw
		val authored = modifiers.flatMap { m -> m.settings.values.filterIsInstance<ModifierValue.Keyed>() }
			.flatMap { v -> v.axes.filter { it.parameter == raw }.flatMap { it.keys.toList() } }
		val keys = merged((authored + parameter.default).map { it.coerceIn(parameter.min, parameter.max) }).toMutableList()
		if (keys.size < 2) return keys.toFloatArray() to 0f
		fun at(v: Float) = shape(mapOf(raw to v))
		val shapes = keys.map(::at).toMutableList()
		fun error(i: Int): Float {
			val mid = (keys[i] + keys[i + 1]) / 2
			val exact = at(mid)
			val a = shapes[i]; val b = shapes[i + 1]
			var worst = 0f
			for (v in 0 until exact.size / 2) worst = max(worst, hypot((a[v * 2] + b[v * 2]) / 2 - exact[v * 2], (a[v * 2 + 1] + b[v * 2 + 1]) / 2 - exact[v * 2 + 1]))
			return worst
		}
		val errors = keys.indices.toList().dropLast(1).map(::error).toMutableList()
		while (keys.size < maxKeys) {
			val i = errors.indices.maxByOrNull { errors[it] } ?: break
			if (errors[i] <= tolerance || keys[i + 1] - keys[i] <= 4 * ModifierValue.EPS) break
			val mid = (keys[i] + keys[i + 1]) / 2
			keys.add(i + 1, mid); shapes.add(i + 1, at(mid))
			errors[i] = error(i); errors.add(i + 1, error(i + 1))
		}
		return keys.toFloatArray() to (errors.maxOrNull() ?: 0f)
	}

	/**
	 * [grid] (null for an unkeyed mesh of [count] coordinates) with [table]'s offsets added: each driving parameter
	 * the grid has no axis for becomes one with the sampled keys, each cell a copy of the grid; on an axis it has,
	 * the sampled keys are inserted. Every cell then adds the offsets at its pose, so what was keyed is kept.
	 * Null for a sparse grid, which keys cannot be inserted into, or one past [MAX_CELLS].
	 */
	internal fun withOffsets(grid: KeyformGrid<MeshDeltaForm>?, count: Int, axes: List<ModifierValue.Keyed.Axis>, table: ModifierValue): KeyformGrid<MeshDeltaForm>? {
		var next = grid ?: KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), MeshDeltaForm(FloatArray(count)))))
		if (!next.isDense) return null
		for (axis in axes) {
			val id = ParameterId(axis.parameter)
			if (next.axisIndexOf(id) < 0) {
				val cells = ArrayList<KeyformCell<MeshDeltaForm>>(next.cells.size * axis.keys.size)
				for (k in axis.keys.indices) for (cell in next.cells) cells += KeyformCell(cell.coordinate + k, cell.form)
				next = KeyformGrid(next.axes + KeyformAxis(id, axis.keys.copyOf()), cells)
			} else for (key in axis.keys) next = next.withKeyInserted(id, key, MeshDeltaInterpolator)
			if (next.cells.size > MAX_CELLS) return null
		}
		val indices = axes.map { next.axisIndexOf(ParameterId(it.parameter)) }
		return KeyformGrid(next.axes, next.cells.map { cell ->
			val pose = axes.withIndex().associate { (i, a) -> a.parameter to next.axes[indices[i]].keys[cell.coordinate[indices[i]]] }
			val add = table.at { pose.getValue(it) }
			val base = cell.form.positionDeltas
			KeyformCell(cell.coordinate, MeshDeltaForm(FloatArray(base.size) { base[it] + add[it] }))
		})
	}

	/**
	 * What an export keeps of [modifiers]: only their keyforms, so each enabled modifier on each mesh is one baked entry;
	 * the kind and settings stay in the project.
	 */
	fun losses(modifiers: List<RigModifier>, model: PuppetModel): List<io.github.psd2live.format.compile.LossEntry> = modifiers.filter { it.enabled }.flatMap { m ->
		m.target.resolve(model).map { mesh ->
			io.github.psd2live.format.compile.LossEntry(mesh, io.github.psd2live.format.compile.Feature.PARAMETER_GRID,
				io.github.psd2live.format.compile.Handling.BAKED, note = "Modifier ${m.id} (${m.kind}) is written as keyforms")
		}
	}

	/** The largest vertex distance between two interleaved shapes. */
	internal fun distance(a: FloatArray, b: FloatArray): Float {
		var worst = 0f
		for (v in 0 until a.size / 2) worst = max(worst, hypot(a[v * 2] - b[v * 2], a[v * 2 + 1] - b[v * 2 + 1]))
		return worst
	}
}
