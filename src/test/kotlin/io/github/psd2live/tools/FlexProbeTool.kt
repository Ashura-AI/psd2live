package io.github.psd2live.tools

import io.github.psd2live.core.*
import io.github.psd2live.project.ProjectRepository
import io.github.psd2live.application.WorkspacePreviewBuilder
import kotlinx.coroutines.runBlocking
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import java.nio.file.Path
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test

/**
 * A project's flexible chains: the bones, how each bound mesh is skinned, and how far each chain's meshes reach along
 * it as drawn, opened out by the joints' own parameters and once the chain is straightened (with the drawing posed back).
 * Writes each pose's vertices and triangles to build/tools/flex-probe/<pose>.txt.
 *
 * PSD2LIVE_TOOLS=1 PSD2LIVE_PROJECT=path/to/x.psd2live ./gradlew test --tests '*FlexProbeTool'; PSD2LIVE_REPAIR=0 skips
 * Update Generated Rig before straightening.
 */
class FlexProbeTool {
	@Test fun probe() {
		requireTools()
		val file = Path.of(setting("PSD2LIVE_PROJECT", "examples/ds/psd-input/ds.psd2live"))
		val project = runBlocking { ProjectRepository().open(file) }
		val document = project.history.head().snapshot
		val spec = document.rigEdits.skeleton ?: error("no skeleton")
		for (bone in spec.topological()) println("bone ${bone.id} role=${bone.role} flex=${bone.flex} bend=${bone.drawnBend} parent=${bone.parentId} " +
			"head=(${bone.headX},${bone.headY}) tail=(${bone.tailX},${bone.tailY}) len=${bone.length} meshes=${bone.drawableIds}")
		val placed = SkeletonCanvasSkin.placed(document.rigEdits)
		println("canvas-skinned: $placed")
		val roots = spec.bones.filter { it.flex && spec.bone(it.parentId ?: "")?.flex != true }
		if (roots.isEmpty()) { println("no flexible chain"); return }
		io.github.psd2live.scenario.Studio.run(java.nio.file.Files.createTempDirectory("flexprobe"), document, checks = emptySet()) {
			for (root in roots) {
				val chain = listOf(root.id) + spec.descendants(root.id).filter { spec.bone(it)!!.flex }
				val meshes = chain.flatMap { spec.bone(it)!!.drawableIds }.distinct()
				println("chain ${root.id}: $chain, meshes $meshes")
				for (id in meshes) report("as committed", puppet, id, root)
				if (setting("PSD2LIVE_REPAIR", "1") == "1") {
					println("  update generated rig: ${updateGenerated()}")
					for (id in meshes) report("repaired", puppet, id, root)
					println("  repaired tail parent ${puppet.drawables.first { it.id.raw == meshes.first() }.parentDeformerId?.raw}")
				}
				// The drawn chain opened out by its own parameters, as the pose tool or physics would.
				val unbend = chain.mapNotNull { spec.bone(it) }.filter { b -> spec.bone(b.parentId ?: "")?.flex == true && spec.children(b.parentId!!).count { it.flex } == 1 }
					.associate { b -> b.parameterId to (-SkeletonIk.wrap(SkeletonRig.restHeading(b) - SkeletonRig.restHeading(spec.bone(b.parentId!!)!!)) * b.direction).toFloat() }
				println("  unbend pose $unbend")
				for (id in meshes) puppet.drawables.first { it.id.raw == id }.let { d ->
					println("  axes ${d.geometryGrid?.axes?.joinToString { "${it.parameterId.raw}=${it.keys.toList()}" }} cells ${d.geometryGrid?.cells?.size} blend ${d.blendShapes.map { it.parameterId.raw }}")
				}
				println("  deformers " + puppet.deformers.filter { it.id.raw.startsWith("DeformSkel_") }.joinToString { "${it.id.raw}<-${it.parent?.raw}" })
				for (id in meshes) report("unbent by parameters", puppet, id, root, unbend)
				val probeOut = output("flex-probe")
				val poses = listOf("drawn-rest" to emptyMap(), "unbent" to unbend) + unbend.keys.map { k -> "half-$k" to mapOf(k to unbend.getValue(k)) }
				for ((label, pose) in poses) {
					val world = CpuDeformationEvaluator().evaluate(puppet, pose.mapKeys { ParameterId(it.key) }).worldPositions
					java.io.File(probeOut, "$label.txt").printWriter().use { w -> for (id in meshes) { val p = world[DrawableId(id)] ?: continue
						val tri = puppet.drawables.first { it.id.raw == id }.mesh!!.indices
						w.println("mesh $id"); for (k in 0 until p.size / 2) w.println("${p[k * 2]} ${-p[k * 2 + 1]}")
						w.println("tri " + tri.joinToString(" ")) } }
				}
				val straight = SkeletonFlex.straightenOnArt(spec, root.id, "probe drawn", puppet)
				for (b in straight.bones.filter { it.id in chain }) println("  straightened ${b.id}: bend=${b.drawnBend} heading=${SkeletonRig.restHeading(b)} drawn ${SkeletonFlex.bound(straight).bone(b.id)!!.let { d -> "(${d.headX},${d.headY})-(${d.tailX},${d.tailY})" }}")
				edit("straighten", "skeleton_put", io.github.psd2live.scenario.req("spec" to straight.toJson()))
				val rootAfter = straight.bone(root.id)!!
				println("  after straighten: skeleton bends ${now.document.rigEdits.skeleton!!.bones.map { it.drawnBend }}, flex warps ${puppet.deformers.filter { it.id.raw.endsWith("Flex") }.map { it.id.raw }}")
				forgetBuilds()
				val cold = runBlocking { WorkspacePreviewBuilder().build(now.document) }.rig.puppet
				println("  cold build: flex warps ${cold.deformers.filter { it.id.raw.endsWith("Flex") }.map { it.id.raw }}, tail parent ${cold.drawables.first { it.id.raw == meshes.first() }.parentDeformerId?.raw}")
				report("cold straightened", cold, meshes.first(), rootAfter)
				println("  regeneration: " + inspect().get("quality").toString().take(3000))
				println("  journal size ${now.document.rigEdits.authoringJournal.size}, ops ${now.document.rigEdits.authoringJournal.map { it["op"] }.groupingBy { it }.eachCount()}")
				for (id in meshes) {
					report("straightened", puppet, id, rootAfter)
					report("straightened, drawn pose", puppet, id, rootAfter, straight.savedPoses.getValue("probe drawn"))
					println("    parent ${puppet.drawables.firstOrNull { it.id.raw == id }?.parentDeformerId?.raw}")
				}
				val out = output("flex-probe")
				for ((label, pose) in listOf("rest" to emptyMap(), "drawn" to straight.savedPoses.getValue("probe drawn"))) {
					val world = CpuDeformationEvaluator().evaluate(puppet, pose.mapKeys { ParameterId(it.key) }).worldPositions
					java.io.File(out, "$label.txt").printWriter().use { w -> for (id in meshes) { val p = world[DrawableId(id)] ?: continue
						val tri = puppet.drawables.first { it.id.raw == id }.mesh!!.indices
						w.println("mesh $id"); for (k in 0 until p.size / 2) w.println("${p[k * 2]} ${-p[k * 2 + 1]}")
						w.println("tri " + tri.joinToString(" ")) } }
				}
			}
		}
	}

	private fun report(label: String, model: PuppetModel, id: String, root: SkeletonBone, pose: Map<String, Float> = emptyMap()) {
		val world = CpuDeformationEvaluator().evaluate(model, pose.mapKeys { ParameterId(it.key) }).worldPositions[DrawableId(id)] ?: return
		val h = Math.toRadians(SkeletonRig.restHeading(root))
		val along = (0 until world.size / 2).map { world[it * 2] * cos(h) - world[it * 2 + 1] * sin(h) }
		val across = (0 until world.size / 2).map { -world[it * 2] * sin(h) - world[it * 2 + 1] * cos(h) }
		val xs = (0 until world.size / 2).map { world[it * 2] }; val ys = (0 until world.size / 2).map { -world[it * 2 + 1] }
		println("  $label $id: ${world.size / 2} vertices, along root %.1f, across %.1f, bounds x %.1f..%.1f y %.1f..%.1f".format(
			along.max() - along.min(), across.max() - across.min(), xs.min(), xs.max(), ys.min(), ys.max()))
	}
}
