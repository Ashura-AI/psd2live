package io.github.psd2live.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import java.awt.Cursor

/** The pointer over anything that can be clicked. */
internal val HandPointer = PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))

/** Shows [HandPointer] over this element while [enabled], the default pointer otherwise. */
internal fun Modifier.handCursor(enabled: Boolean = true): Modifier =
	pointerHoverIcon(if (enabled) HandPointer else PointerIcon.Default)

/**
 * A primary click selects at the press, without waiting to tell it from a double click, and a second press in time
 * and in place opens: for items whose double click goes further than their click. Other buttons are left to the
 * item (a right click for its menu), so two right clicks never open it. The latest callbacks are read at each press,
 * so the selection recomposing the item does not lose the first click. Hover needs its own `hoverable`.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun Modifier.selectOrOpen(enabled: Boolean, onSelect: () -> Unit, onOpen: () -> Unit): Modifier {
	val select by rememberUpdatedState(onSelect)
	val open by rememberUpdatedState(onOpen)
	val last = remember { LastPress() }
	if (!enabled) return this
	return onPointerEvent(PointerEventType.Press) { event ->
		if (event.button != PointerButton.Primary) return@onPointerEvent
		val change = event.changes.firstOrNull() ?: return@onPointerEvent
		val second = last.time?.let { time ->
			change.uptimeMillis - time <= viewConfiguration.doubleTapTimeoutMillis &&
				(change.position - last.at).getDistance() <= viewConfiguration.touchSlop
		} == true
		select()
		if (second) {
			last.time = null
			open()
		} else {
			last.time = change.uptimeMillis
			last.at = change.position
		}
	}
}

/** When and where the last primary press of a [selectOrOpen] item landed; no time once it made a double click. */
private class LastPress {
	var time: Long? = null
	var at: Offset = Offset.Zero
}
