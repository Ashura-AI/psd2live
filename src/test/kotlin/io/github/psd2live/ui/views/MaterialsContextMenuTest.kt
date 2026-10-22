package io.github.psd2live.ui.views

import androidx.compose.foundation.layout.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import org.umamo.format.art.LayerRaster
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class MaterialsContextMenuTest {

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
