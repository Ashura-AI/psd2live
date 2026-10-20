package io.github.psd2live.ui.views

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.psd2live.project.*
import io.github.psd2live.ui.theme.CompactToolTheme
import io.github.psd2live.ui.theme.LocalToolColors
import kotlinx.coroutines.*
import org.umamo.format.art.LayerRaster
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class MaterialsContextMenuTest {
    @Test fun blankSpaceOpensItsMenuAndEntryPressesKeepTheirOwnTarget() = runBlocking {
        withContext(Dispatchers.Main) {
            for (grid in listOf(false, true)) {
                val events = mutableListOf<String>()
                val scene = ImageComposeScene(320, 240, density = Density(1f), coroutineContext = currentCoroutineContext()) {
                    CompactToolTheme {
                        val colors = LocalToolColors.current
                        Box(Modifier.fillMaxSize().materialBackgroundMenu { events += "background" }) {
                            val entry = Modifier.size(80.dp).background(colors.inputBackground)
                                .materialEntryMenu { events += "entry" }.clickable { events += "select" }
                            if (grid) LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize()) {
                                item { Box(entry) }
                            } else LazyColumn(Modifier.fillMaxSize()) {
                                item { Box(entry) }
                            }
                        }
                    }
                }
                var time = 0L
                suspend fun frames() { repeat(3) { time += 16_000_000; scene.render(time).close(); yield() } }
                suspend fun click(at: Offset, button: PointerButton) {
                    scene.sendPointerEvent(PointerEventType.Move, at)
                    scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(
                        isPrimaryPressed = button == PointerButton.Primary, isSecondaryPressed = button == PointerButton.Secondary), button = button)
                    scene.sendPointerEvent(PointerEventType.Release, at, button = button)
                    frames()
                }
                try {
                    frames()
                    click(Offset(260f, 190f), PointerButton.Secondary)
                    assertEquals(listOf("background"), events, "blank ${if (grid) "grid" else "list"}")
                    events.clear()
                    click(Offset(30f, 30f), PointerButton.Secondary)
                    assertEquals(listOf("entry"), events, "entry must not be replaced by the background target")
                    events.clear()
                    click(Offset(30f, 30f), PointerButton.Primary)
                    assertEquals(listOf("select"), events)
                } finally { scene.close() }
            }
        }
    }

    @Test fun folderActionsIncludeNestedMaterialsAndExcludeSiblingFolders() {
        fun item(id: String, folder: String?) = MaterialItem(id, id, folder, MaterialKind.BITMAP, LayerRaster(1, 1, byteArrayOf(0, 0, 0, -1)))
        val direct = item("direct", "folder")
        val nested = item("nested", "child")
        val sibling = item("sibling", "other")
        val loose = item("loose", null)
        val library = MaterialLibrary(listOf(MaterialFolder("folder", "Folder", null), MaterialFolder("child", "Child", "folder"),
            MaterialFolder("other", "Other", null), MaterialFolder("empty", "Empty", null)), listOf(direct, nested, sibling, loose))
        assertEquals(listOf(direct, nested), materialMenuItems(library, "folder"))
        assertEquals(listOf(nested), materialMenuItems(library, "child"))
        assertEquals(listOf(loose), materialMenuItems(library, "loose"))
        assertTrue(materialMenuItems(library, "empty").isEmpty())
        assertTrue(materialMenuItems(library, "missing").isEmpty())
    }
}
