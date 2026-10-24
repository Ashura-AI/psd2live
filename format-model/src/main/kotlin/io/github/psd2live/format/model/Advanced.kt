package io.github.psd2live.format.model

/**
 * What a host adds on top of the rig every target exports. The bones, simulations and colliders are data the Cubism
 * rig has already baked into keyforms and pendulums, kept so the PSD2Live runtime's advanced mode can play it live;
 * the expressions, hit areas and pose are what a host builds interaction on, which the Cubism family writes beside
 * the moc (exp3, pose3) as well. Targets without a place for them ignore them.
 */
public data class AdvancedIR(
	/**
	 * Skeleton bones the rig folded into its meshes' keyforms (no mesh hangs from them), as rotations the runtime
	 * evaluates only to skin those meshes. A parent is a deformer of the rig or an earlier virtual bone.
	 */
	val virtualBones: List<Deformer.Rotation> = emptyList(),
	val simulations: List<SimulationIR> = emptyList(),
	val colliders: List<ColliderIR> = emptyList(),
	/** Facial expressions a host switches between, each a few parameters set over the motion. */
	val expressions: List<ExpressionIR> = emptyList(),
	/** Named regions a host hit-tests, such as Head and Body, derived from the rig where the document set up none ([RigIR.hitAreas]). */
	val hitAreas: List<HitAreaIR> = emptyList(),
	/** Part poses: groups of parts of which one shows at a time. */
	val pose: PoseIR? = null,
	/** Meshes' modifier stacks, which the rig has sampled into keyforms, for the runtime to evaluate exactly between the keys. */
	val modifiers: List<ModifierStackIR> = emptyList(),
) {
	val isEmpty: Boolean get() = virtualBones.isEmpty() && simulations.isEmpty() && colliders.isEmpty() && expressions.isEmpty() &&
		hitAreas.isEmpty() && pose == null && modifiers.isEmpty()
}

/**
 * One mesh's modifier stack as the rig sampled it: the modifiers chained in [modifiers] order from [start], over
 * [axes] at exactly the keys its keyforms were sampled at (the driving parameters', then the mesh's own keyform
 * axes). The runtime adds the stack's departure from its own interpolation over those keys, zero at every key.
 */
public data class ModifierStackIR(
	val mesh: String,
	val axes: List<KeyAxis>,
	/**
	 * What the first modifier takes: the mesh as its own keyforms pose it, on the canvas through its parents at the
	 * default pose (x, y interleaved, y down, pixels), keyed on those of [axes] that are its own with the same keys.
	 */
	val start: ModifierValueIR,
	/** Per vertex a, b, c, d: a canvas displacement (dx, dy) in the mesh's parent space at the default pose is (a dx + b dy, c dx + d dy). */
	val toParent: Floats,
	val modifiers: List<ModifierIR>,
)

/**
 * A modifier of a kind the format names ([kind]: transform, bend, twist, wave, lattice) with every setting of
 * that kind; [weights] per vertex, empty for all ones. [phaseSpeed] (degrees per second) advances a wave's phase
 * with time in the runtime's advanced mode only.
 */
public data class ModifierIR(val kind: String, val settings: Map<String, ModifierValueIR>, val weights: Floats = Floats.Empty, val phaseSpeed: Float = 0f)

/**
 * A setting's value: a constant without [axes], else keyed on them as a dense product (axis 0 fastest), each
 * combination holding the setting's components in turn; multilinear between keys and held past the ends.
 */
public data class ModifierValueIR(val axes: List<KeyAxis>, val values: Floats)

/** Cubism's pose3: per group the parts (by id) of which one shows, each with parts that follow it; [fadeIn] seconds to switch. */
public data class PoseIR(val fadeIn: Float, val groups: List<List<PoseEntry>>)

public data class PoseEntry(val part: String, val links: List<String> = emptyList())

/** An expression as Cubism's exp3 has one: parameters added to, multiplied into or overwriting the motion's values. */
public data class ExpressionIR(val id: String, val name: String, val fadeIn: Float, val fadeOut: Float, val parameters: List<ExpressionParameter>)

public enum class ExpressionBlend { ADD, MULTIPLY, OVERWRITE }

public data class ExpressionParameter(val parameter: String, val blend: ExpressionBlend, val value: Float)

/** A hit area: the region the [meshes] cover as they are drawn. */
public data class HitAreaIR(val id: String, val name: String, val meshes: List<String>)

/**
 * A cloth or hair simulation as the editor's XPBD solver runs it, particle for particle: one particle per
 * vertex of each target mesh in [targets] order. Positions are world space (canvas x, negated canvas y).
 * Compliances may be [Float.POSITIVE_INFINITY] (no constraint).
 */
public data class SimulationIR(
	val id: String,
	/** Solver steps per second; each step runs [substeps] substeps. */
	val fps: Float,
	val substeps: Int,
	val gravityX: Float, val gravityY: Float,
	val windX: Float, val windY: Float,
	val pinCompliance: Float,
	val targets: List<SimTarget>,
	val particles: SimParticles,
	val stretch: SimStretch,
	val triangles: SimTriangles,
	val bends: SimBends,
	val welds: SimWelds,
	val longRange: SimLongRange,
	/** The baked mode parameters the simulation replaces: held at their defaults while it runs. */
	val parameters: List<String>,
	/** The baked pendulums driving them: skipped while it runs. */
	val physicsGroups: List<String>,
	/** Corrections the bake added to the targets' keyforms on other parameters, taken back out while it runs. */
	val statics: List<SimStatic>,
	/** Colliders (by id) the particles keep out of. */
	val colliders: List<String> = emptyList(),
)

public data class SimTarget(val mesh: String, val vertexCount: Int)

public data class SimParticles(
	val invMass: Floats, val damping: Floats, val windFactor: Floats, val pinWeight: Floats, val goalCompliance: Floats,
	val goalOffsetX: Floats, val goalOffsetY: Floats,
	/** Per particle the mesh its pin follows ("" for its own position) and that mesh's vertex. */
	val anchorMesh: List<String>, val anchorVertex: Ints,
)

public data class SimStretch(val a: Ints, val b: Ints, val rest: Floats, val compliance: Floats, val compressionCompliance: Floats)
public data class SimTriangles(val a: Ints, val b: Ints, val c: Ints, val areaCompliance: Floats)
public data class SimBends(val t1: Ints, val t2: Ints, val compliance: Floats)
public data class SimWelds(val a: Ints, val b: Ints, val weightA: Floats, val weightB: Floats, val compliance: Floats)
public data class SimLongRange(val particle: Ints, val root: Ints, val maxDistance: Floats)

/** A static correction: at each of [keys] of [parameter], what each target mesh (by id) adds, in its parent's space. */
public data class SimStatic(val parameter: String, val keys: Floats, val offsets: Map<String, List<Floats>>)

/**
 * A circle ([ax], [ay] with [radiusA]) or a capsule from it to ([bx], [by]) with [radiusB], that simulated
 * particles stay out of. It rides [deformer] (a rig deformer or virtual bone; its local space), or with
 * [mesh] set runs between that mesh's vertices [vertexA] and [vertexB] as they are evaluated; with neither,
 * it stays put in canvas space. [friction] 0..1 slows particles sliding along it.
 */
public data class ColliderIR(
	val id: String,
	val capsule: Boolean,
	val deformer: String? = null,
	val mesh: String? = null,
	val vertexA: Int = 0, val vertexB: Int = 0,
	val ax: Float = 0f, val ay: Float = 0f, val bx: Float = 0f, val by: Float = 0f,
	val radiusA: Float, val radiusB: Float = radiusA,
	val friction: Float = 0f,
)
