package io.github.psd2live.scenario

import org.umamo.runtime.model.PuppetModel

/**
 * The exported moc3, read back, shows what the editor shows: the same meshes, deformed alike and with the same
 * opacity at rest and with each of the first parameters at its ends.
 */
internal fun exportMatchesEditor(studio: Studio, exported: PuppetModel = studio.moc3(), what: String = "moc3") = with(studio) {
	val editor = puppet
	val ids = editor.drawables.map { it.id }.toSet()
	expect("export $what", exported.drawables.map { it.id }.toSet() == ids) {
		"exported meshes differ: missing ${ids - exported.drawables.map { it.id }.toSet()}, extra ${exported.drawables.map { it.id }.toSet() - ids}"
	}
	val poses = listOf(emptyMap<String, Float>()) + editor.parameters.filter { it.max > it.min }.take(32)
		.flatMap { p -> listOf(mapOf(p.id.raw to p.min), mapOf(p.id.raw to p.max)) }
	for (pose in poses) difference(editor, exported, pose)?.let { problem -> expect("export $what", false) { "at $pose $problem" } }
}

/**
 * A blend shape on a mesh that has no keyforms of its own under a warp - what a cloth bake leaves on a mesh the generator
 * never keyed - exports as the editor shows it: [mesh] is made unkeyed at its default-pose lattice position and given a
 * blend shape, then goes through the moc3 target's own canvas-space pass and back.
 */
internal fun unkeyedBlendShapeMatchesEditor(studio: Studio, mesh: String) = with(studio) {
	val id = org.umamo.runtime.model.DrawableId(mesh)
	val source = puppet.drawables.single { it.id == id }
	expect("unkeyed blend shape", puppet.deformers.singleOrNull { it.id == source.parentDeformerId } is org.umamo.runtime.model.Deformer.Warp) {
		"mesh $mesh is not under a warp"
	}
	val defaults = puppet.parameters.associate { it.id to it.default }
	val reference = org.umamo.runtime.eval.meshGridDefaultDeltas(source) { defaults[it] ?: 0f }
	val base = source.mesh!!.positions.let { p -> FloatArray(p.size) { p[it] + (reference?.get(it) ?: 0f) } }
	val billow = org.umamo.runtime.model.ParameterId("ParamBillow")
	val form = org.umamo.runtime.model.MeshForm(FloatArray(base.size) { if (it % 2 == 0) 0.05f else 0.02f },
		source.drawOrder, source.opacity, source.multiplyColor, source.screenColor)
	val editor = puppet.copy(
		parameters = puppet.parameters + org.umamo.runtime.model.Parameter(billow, "Billow", 0f, 1f, 0f, org.umamo.runtime.model.ParameterKind.BLEND_SHAPE),
		drawables = puppet.drawables.map {
			if (it.id != id) it else it.copy(mesh = it.mesh!!.let { m -> org.umamo.runtime.model.DrawableMesh(base, m.uvs, m.indices) }, geometryGrid = null,
				blendShapes = listOf(org.umamo.runtime.model.BlendShapeBinding(billow, floatArrayOf(0f, 1f), 0, listOf(null, form))))
		})
	expect("unkeyed blend shape", Oracles.motion(editor, mesh, mapOf(billow.raw to 1f)) > 1.0) { "the blend shape does not move $mesh" }
	val exported = step("export the unkeyed blend shape") {
		val canvas = org.umamo.render.restMeshesToCanvasSpace(editor)
		val lowered = org.umamo.interop.moc3.export.Moc3Export.toMocDocument(canvas, canvasToParentSpace = org.umamo.render.canvasToParentSpaceFor(canvas))
		org.umamo.interop.moc3.import.Moc3Import.fromMocDocument(org.umamo.format.moc3.Moc3.read(org.umamo.format.moc3.Moc3.write(lowered.document)), null)
	}
	for (pose in listOf(emptyMap(), mapOf(billow.raw to 1f))) {
		difference(editor, exported, pose)?.let { problem -> expect("unkeyed blend shape", false) { "at $pose $problem" } }
	}
}

private fun difference(a: PuppetModel, b: PuppetModel, pose: Map<String, Float>, tolerance: Float = 0.5f): String? {
	val evaluator = org.umamo.render.eval.CpuDeformationEvaluator()
	val values = pose.mapKeys { org.umamo.runtime.model.ParameterId(it.key) }
	val x = evaluator.evaluate(a, values); val y = evaluator.evaluate(b, values)
	for ((id, p) in x.worldPositions) {
		val q = y.worldPositions[id] ?: return "mesh ${id.raw} is not shown by the export"
		if (q.size != p.size) return "mesh ${id.raw} has ${q.size / 2} vertices in the export, ${p.size / 2} in the editor"
		val worst = p.indices.maxOfOrNull { kotlin.math.abs(p[it] - q[it]) } ?: 0f
		if (worst > tolerance) return "mesh ${id.raw} is $worst px from the editor's"
		val o = x.opacity[id] ?: 1f; val r = y.opacity[id] ?: 1f
		if (kotlin.math.abs(o - r) > 0.01f) return "mesh ${id.raw} has opacity $r in the export, $o in the editor"
	}
	return null
}
