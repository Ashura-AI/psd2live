package io.github.psd2live.ui.views.tooloptions

/** Close ordinary menus before running their action; dialogs and continuous menus retain their own focus. */
internal fun invokeMenuAction(
    dismiss: () -> Unit,
    focusCanvas: () -> Unit = {},
    keepsMenu: Boolean = false,
    opensDialog: Boolean = false,
    action: () -> Unit,
) {
    if (!keepsMenu) dismiss()
    action()
    if (!keepsMenu && !opensDialog) focusCanvas()
}
