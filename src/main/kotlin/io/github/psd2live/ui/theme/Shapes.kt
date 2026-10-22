package io.github.psd2live.ui.theme

import androidx.compose.ui.unit.dp

/**
 * The window's geometry: corner radii and the room between its surfaces, so the dock, the title bar and the status
 * bar line up and round the same way everywhere. Panels sit as cards on the window background, [Spacing.Gap] apart
 * and [Spacing.Margin] from the window's edges.
 */
internal object Corners {
	/** Buttons, chips, tabs, fields and menu rows. */
	val Control = 4.dp

	/** A docked panel's card, and the top of its header or tab strip. */
	val Panel = 6.dp
}

internal object Spacing {
	/** Between docked panels. */
	val Gap = 4.dp

	/** From the outer panels to the window's sides and bottom, and from the workspace tabs to the panels. */
	val Margin = 6.dp
}
