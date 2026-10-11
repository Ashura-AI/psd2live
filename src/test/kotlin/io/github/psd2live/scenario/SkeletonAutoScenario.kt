package io.github.psd2live.scenario

import io.github.psd2live.core.BoneRole
import io.github.psd2live.core.Side
import io.github.psd2live.core.SkeletonSpec
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test

/** Where the proposed skeleton puts its joints on figures whose art says where they belong. */
class SkeletonAutoScenario {
	@TempDir lateinit var temp: Path

	private fun Studio.skeleton(): SkeletonSpec = now.document.rigEdits.skeleton!!

	/**
	 * The figure's sleeves are bars 28 px across, 82 long, out to each side at shoulder height: shorter than a limb is
	 * wide enough to read two ends off. The joints go along the bar's middle, from where the shoulder is to its far end.
	 */
	@Test fun armsRunAlongTheirSleeves() = Studio.run(temp, Characters.figure()) {
		edit("apply the proposed skeleton", "skeleton_auto", req())
		val bones = skeleton().bones
		for (side in listOf(Side.LEFT, Side.RIGHT)) {
			val chain = listOf(BoneRole.UPPER_ARM, BoneRole.FOREARM, BoneRole.HAND).map { role -> bones.single { it.role == role && it.side == side } }
			val outward = if (side == Side.LEFT) 1f else -1f
			val joints = chain.map { it.headX to it.headY } + (chain.last().tailX to chain.last().tailY)
			for ((x, y) in joints) expect("on the bar", abs(y - 134f) < 9f) { "$side joint at ($x, $y) is not along the sleeve's middle (y = 134)" }
			expect("outward", joints.zipWithNext().all { (a, b) -> (b.first - a.first) * outward > 5f }) { "$side joints do not run out along the sleeve: $joints" }
			val reach = (joints.last().first - 210f) * outward
			expect("reach", reach > 118f && reach < 134f) { "$side hand ends ${reach} px from the centre line; the sleeve ends at 130" }
		}
	}

	@Test fun emptyTemplateLayersGrowNoTailOrWings() = Studio.run(temp, Characters.withPlaceholders()) {
		edit("apply the proposed skeleton", "skeleton_auto", req())
		val roles = skeleton().bones.map { it.role }.toSet()
		expect("no tail", BoneRole.TAIL !in roles) { "a layer of a few pixels grew a tail: ${skeleton().bones.filter { it.role == BoneRole.TAIL }}" }
		expect("no wings", BoneRole.WING !in roles) { "a layer of a few pixels grew wings: ${skeleton().bones.filter { it.role == BoneRole.WING }}" }
		expect("limbs", listOf(BoneRole.UPPER_ARM, BoneRole.THIGH).all { it in roles }) { "the figure lost its limbs: $roles" }
	}
}
