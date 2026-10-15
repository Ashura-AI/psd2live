package io.github.psd2live.ui.theme

/**
 * The editor's motion: every transition takes its duration from here, so the same kind of change moves the same way
 * everywhere. Entrances ease out (LinearOutSlowIn for fades, FastOutSlowIn for movement), exits are [Instant] and
 * FastOutLinearIn, and nothing springs. The canvas itself (zoom, pan, playback, edit feedback) never eases.
 */
internal object Motion {
	/** Hover and selection tints, the gliding highlight, every exit. */
	const val Instant = 80

	/** Small changes of state: a chevron turning, a menu growing from its anchor, a highlight appearing. */
	const val Quick = 100

	/** Fading in: menus, second levels, labels, switches. */
	const val Fade = 120

	/** Rows, labels and bars folding open or closed. */
	const val Fold = 160

	/** Sizes following the pointer: a rail widening, a floating bar lifting, a bar sliding into view. */
	const val Expand = 200

	/** One menu row arriving, and how long after the row above it it starts. */
	const val RowArrival = 90
	const val RowStagger = 5

	/** How long a widening rail waits before its labels follow. */
	const val LabelDelay = 40

	/** One cycle of a busy indicator. */
	const val Pulse = 900

	/** How long the pointer rests on a control before its tooltip shows. */
	const val TooltipDelay = 400
}
