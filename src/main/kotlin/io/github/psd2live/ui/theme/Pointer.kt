package io.github.psd2live.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import java.awt.Cursor

/** The pointer over anything that can be clicked. */
internal val HandPointer = PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))

/** Shows [HandPointer] over this element while [enabled], the default pointer otherwise. */
internal fun Modifier.handCursor(enabled: Boolean = true): Modifier =
	pointerHoverIcon(if (enabled) HandPointer else PointerIcon.Default)
