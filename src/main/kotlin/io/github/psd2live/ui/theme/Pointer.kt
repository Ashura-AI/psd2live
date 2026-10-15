package io.github.psd2live.ui.theme

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import java.awt.Cursor

/** The pointer over anything that can be clicked. */
internal val HandPointer = PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))

/** Shows [HandPointer] over this element while [enabled], the default pointer otherwise. */
internal fun Modifier.handCursor(enabled: Boolean = true): Modifier =
	pointerHoverIcon(if (enabled) HandPointer else PointerIcon.Default)

/**
 * A click selects at the press, without waiting to tell it from a double click, and a double click opens: for items
 * whose double click goes further than their click. The latest callbacks are read at each tap, so the selection
 * recomposing the item does not restart the gesture between clicks. Hover needs its own `hoverable`.
 */
@Composable
internal fun Modifier.selectOrOpen(enabled: Boolean, onSelect: () -> Unit, onOpen: () -> Unit): Modifier {
	val select by rememberUpdatedState(onSelect)
	val open by rememberUpdatedState(onOpen)
	return pointerInput(enabled) {
		if (enabled) detectTapGestures(onPress = { select() }, onDoubleTap = { open() })
	}
}
