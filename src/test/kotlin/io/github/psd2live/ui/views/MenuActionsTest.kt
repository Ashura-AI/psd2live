package io.github.psd2live.ui.views

import io.github.psd2live.ui.views.tooloptions.invokeMenuAction
import kotlin.test.*

class MenuActionsTest {
    @Test fun ordinaryActionsCloseBeforeExecutionAndReturnCanvasFocusAfterwards() {
        val events = mutableListOf<String>()
        invokeMenuAction({ events += "close" }, { events += "canvas" }) { events += "edit" }
        assertEquals(listOf("close", "edit", "canvas"), events)
    }

    @Test fun openingADialogNeverTakesItsFocusBack() {
        val events = mutableListOf<String>()
        invokeMenuAction({ events += "close" }, { events += "canvas" }, opensDialog = true) { events += "dialog" }
        assertEquals(listOf("close", "dialog"), events)
    }

    @Test fun continuousMenuActionsKeepTheMenuAndItsKeyboardFocus() {
        val events = mutableListOf<String>()
        invokeMenuAction({ events += "close" }, { events += "canvas" }, keepsMenu = true) { events += "edit" }
        assertEquals(listOf("edit"), events)
    }

    @Test fun failingActionsStillCloseAndDoNotStealFocus() {
        val events = mutableListOf<String>()
        assertFailsWith<IllegalStateException> {
            invokeMenuAction({ events += "close" }, { events += "canvas" }) { error("failed") }
        }
        assertEquals(listOf("close"), events)
    }
}
