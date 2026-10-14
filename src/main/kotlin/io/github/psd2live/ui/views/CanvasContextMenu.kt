package io.github.psd2live.ui.views

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.psd2live.ui.CanvasEditor
import io.github.psd2live.ui.components.TreeContextMenu
import io.github.psd2live.ui.tooloptions.menuOptions
import io.github.psd2live.ui.views.tooloptions.ToolOptionMenu

/** Whether a right press on [editor]'s canvas has a menu to open: the tool in hand has options or actions for it. */
internal fun canvasContextMenuHasContent(editor: CanvasEditor): Boolean = menuOptions(editor).isNotEmpty()

/**
 * The canvas context menu, opened where the right press landed: the tool in hand's actions and its most used
 * values - the same options the tool options bar shows, from the same definitions (see [menuOptions]), in the
 * shared [PressMenu], its rows arriving one after another.
 */
@Composable
internal fun CanvasContextMenu(
    editor: CanvasEditor,
    expanded: Boolean,
    clickOffset: Offset,
    onDismissRequest: () -> Unit,
    onAction: () -> Unit = {},
) {
    if (!expanded && !canvasContextMenuHasContent(editor)) return
    PressMenu(expanded, clickOffset, onDismissRequest) {
        ToolOptionMenu(editor, onDismiss = onDismissRequest, onAction = onAction)
    }
}

/**
 * A context menu opened where a right press landed ([press], in the coordinates of the layout that holds it): the
 * panels' [TreeContextMenu] shell, so the canvas's menu and every other right-click menu look and move alike. It grows
 * to its content's width ([width] fixes it) and scrolls past 560dp.
 */
@Composable
internal fun PressMenu(
    expanded: Boolean,
    press: Offset,
    onDismissRequest: () -> Unit,
    width: Dp? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    TreeContextMenu(expanded, onDismissRequest, clickOffset = press, modifier = Modifier.heightIn(max = 560.dp),
        minWidth = width ?: 0.dp, maxWidth = width ?: Dp.Unspecified) {
        Column(Modifier.verticalScroll(rememberScrollState()), content = content)
    }
}
