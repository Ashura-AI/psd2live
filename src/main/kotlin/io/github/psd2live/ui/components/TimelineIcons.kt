package io.github.psd2live.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.psd2live.ui.theme.LocalToolColors

/** A bookmark ribbon, filled while the parameter is bookmarked. */
@Composable
internal fun IconBookmark(
	marked: Boolean,
	modifier: Modifier = Modifier.size(11.dp),
	tint: Color = LocalToolColors.current.textMuted,
) = GridIcon(modifier, tint) {
	val ribbon = path { m(4.6f, 2.2f); l(13.4f, 2.2f); l(13.4f, 15.8f); l(9f, 12.2f); l(4.6f, 15.8f); z() }
	if (marked) fill(ribbon)
	outline(ribbon)
}

/** Shy: an eye looking down under its lid, filled while the track is shy. */
@Composable
internal fun IconShy(
	shy: Boolean,
	modifier: Modifier = Modifier.size(11.dp),
	tint: Color = LocalToolColors.current.textMuted,
) = GridIcon(modifier, tint) {
	val lid = path { m(1.8f, 8f); c(4.2f, 12.4f, 13.8f, 12.4f, 16.2f, 8f) }
	outline(lid)
	if (shy) {
		line(4.4f, 11.4f, 3.4f, 13.6f)
		line(9f, 12.4f, 9f, 14.8f)
		line(13.6f, 11.4f, 14.6f, 13.6f)
	} else {
		outline(path { m(1.8f, 8f); c(4.2f, 3.6f, 13.8f, 3.6f, 16.2f, 8f) })
		dot(9f, 8.6f, 1.8f)
	}
}

/** Freeze: a snowflake, solid while the track is frozen. */
@Composable
internal fun IconFreeze(
	frozen: Boolean,
	modifier: Modifier = Modifier.size(11.dp),
	tint: Color = LocalToolColors.current.textMuted,
) = GridIcon(modifier, tint) {
	val width = if (frozen) 1.8f else ICON_LINE
	for (angle in listOf(0f, 60f, 120f)) turned(angle) {
		line(9f, 2f, 9f, 16f, width)
		chevron(9f, 4.4f, 0f, -1f, 2.2f, width)
		chevron(9f, 13.6f, 0f, 1f, 2.2f, width)
	}
}

/** A marker flag on its pole. */
@Composable
internal fun IconMarker(
	modifier: Modifier = Modifier.size(11.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) = GridIcon(modifier, tint) {
	line(4.6f, 2.2f, 4.6f, 16f)
	val flag = path { m(4.6f, 2.6f); l(14.4f, 5.6f); l(4.6f, 8.8f); z() }
	shape(flag)
}

/** Close loop: an arrow running round to where it started, its ends joined by a dot. */
@Composable
internal fun IconCloseLoop(
	modifier: Modifier = Modifier.size(11.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) = GridIcon(modifier, tint) {
	arcArrow(9f, 9f, 6f, 200f, 500f, head = 3f)
	dot(9f - 6f * 0.94f, 9f - 6f * 0.34f, 1.4f)
}

/** Motion trajectory: a dotted arc of frames ending in the current one. */
@Composable
internal fun IconTrajectory(
	modifier: Modifier = Modifier.size(11.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) = GridIcon(modifier, tint) {
	outline(path { m(2.4f, 11.4f); q(9f, 1f, 15.4f, 11.2f) }, ICON_FINE, dash = floatArrayOf(1.6f, 1.6f))
	dot(2.4f, 11.4f, 1.2f, tone(color, 0.5f))
	dot(9f, 6f, 1.3f, tone(color, 0.75f))
	dot(15.4f, 11.2f, 2.2f)
}
