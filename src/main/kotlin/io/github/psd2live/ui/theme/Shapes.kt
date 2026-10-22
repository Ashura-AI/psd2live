package io.github.psd2live.ui.theme

import androidx.compose.ui.unit.dp

/**
 * The window's geometry: corner radii and the room between its surfaces, so the dock, the title bar and the status
 * bar line up and round the same way everywhere. Panels sit as cards on the window background, [Spacing.Gap] apart
 * and the same distance from the window's edges.
 */
internal object Corners {
	/** Buttons, chips, tabs and fields inside a panel. */
	val Control = 4.dp

	/** A docked panel's card, and the top of its header or tab strip. */
	val Panel = 6.dp
}

internal object Spacing {
	/** Between docked panels, and from the outer panels to the window's edges. */
	val Gap = 6.dp
}
