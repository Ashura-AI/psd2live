package io.github.psd2live.ui.views

import androidx.compose.foundation.layout.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.*
import kotlinx.coroutines.*
import org.umamo.runtime.model.*
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class MaterialUsageBadgeTest {
    @Test fun previewIncludesEveryMeshOfSharedLayersAndNamesPendingLayers() {
        fun mesh(id: String, geometry: Boolean = true) = Drawable(DrawableId(id), "Mesh $id", null, BlendMode.Normal, emptyList(),
            if (geometry) DrawableMesh(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), intArrayOf(0, 1, 2)) else null, null)
        val drawables = listOf(mesh("one"), mesh("two"), mesh("other"), mesh("no_geometry", false), mesh("unbound"))
        val rows = materialUsageEntries(listOf("shared", "pending", "shared", "unknown"), drawables,
            mapOf("one" to "shared", "two" to "shared", "other" to "unrelated", "no_geometry" to "pending"), mapOf("pending" to "Pending layer"))
        assertEquals(listOf("one", "two", null, null), rows.map { it.drawable?.id?.raw })
        assertEquals(listOf("shared", "shared", "pending", "unknown"), rows.map { it.layerId })
        assertEquals(listOf("Mesh one", "Mesh two", "Pending layer", "unknown"), rows.map { it.name })
    }

}
