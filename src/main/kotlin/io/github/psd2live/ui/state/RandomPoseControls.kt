package io.github.psd2live.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.psd2live.ui.RandomPoseScope
import org.umamo.runtime.model.ParameterId

/** The values a random pose replaced, for the project and workspace they were shown in. */
internal class RandomPoseRestore(val generation: Long, val workspaceId: String, val values: Map<ParameterId, Float>)

/**
 * The canvas's random pose: which parameters it moves, how far from rest, and what it replaced. Session state
 * like the motion editor's: neither the project nor its history keeps it.
 */
internal class RandomPoseControls {
	var scope: RandomPoseScope by mutableStateOf(RandomPoseScope.ALL)
	/** How far from its default each parameter may go, as a share of the way to its limits. */
	var amount: Float by mutableStateOf(1f)
	var restore: RandomPoseRestore? by mutableStateOf(null)
}
