@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package io.github.psd2live.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.selection.LocalSelectionRegistrar
import androidx.compose.foundation.text.selection.SelectionAdjustment
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.SelectionRegistrarImpl
import androidx.compose.material.Text
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.Color
import io.github.psd2live.ui.theme.CompactToolTheme
import io.github.psd2live.ui.views.ParameterTooltip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalFoundationApi::class)
class TooltipSelectionTest {
    @Test fun selectingTextWhileASharedTooltipIsVisibleNeverCrossesLayoutHierarchies() = runBlocking {
        withContext(Dispatchers.Main) {
            var registrar: SelectionRegistrarImpl? = null
            var messageCoordinates: LayoutCoordinates? = null
            var badge = Rect.Zero
            val scene = ImageComposeScene(600, 180, density = Density(1f), coroutineContext = currentCoroutineContext()) {
                CompactToolTheme {
                    SelectionContainer {
                        registrar = LocalSelectionRegistrar.current as SelectionRegistrarImpl
                        Row {
                            Text("AI client connected", Modifier.onGloballyPositioned { messageCoordinates = it }, color = Color.White)
                            TooltipArea(tooltip = { ParameterTooltip("Repeated twice") }, delayMillis = 50) {
                                Text("×2", Modifier.onGloballyPositioned { badge = it.boundsInRoot() }, color = Color.LightGray)
                            }
                        }
                    }
                }
            }
            try {
                scene.render(0).close()
                yield()
                assertEquals(2, assertNotNull(registrar).selectables.size)
                scene.sendPointerEvent(PointerEventType.Enter, badge.center, type = PointerType.Mouse)
                scene.sendPointerEvent(PointerEventType.Move, badge.center, type = PointerType.Mouse)
                delay(100)
                scene.render(150_000_000).use { image ->
                    if (System.getenv("PSD2LIVE_TOOLS") == "1") {
                        val path = File("build/tools/tooltip-selection/tooltip.png")
                        path.parentFile.mkdirs()
                        path.writeBytes(requireNotNull(image.encodeToData()).bytes)
                    }
                }
                yield()
                // Exercise the same selection callback used by a mouse word-selection gesture, without
                // relying on native-window focus or popup event routing in the offscreen renderer.
                val selection = assertNotNull(registrar)
                selection.notifySelectionUpdateStart(assertNotNull(messageCoordinates), Offset(5f, 8f),
                    SelectionAdjustment.Word, isInTouchMode = false)
                assertEquals(2, selection.selectables.size, "Popup text must not join the enclosing selection container")
                assertFalse(selection.subselections.isEmpty(), "The original message must remain selectable")
                scene.render(180_000_000).use { image ->
                    if (System.getenv("PSD2LIVE_TOOLS") == "1") {
                        File("build/tools/tooltip-selection/selection.png").writeBytes(requireNotNull(image.encodeToData()).bytes)
                    }
                }
                yield()
            } finally {
                scene.close()
            }
        }
    }
}
