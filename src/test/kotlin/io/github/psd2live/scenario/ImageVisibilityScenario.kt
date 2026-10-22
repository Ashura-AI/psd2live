package io.github.psd2live.scenario

import io.github.psd2live.application.ImageLayerInsertion
import io.github.psd2live.application.WorkspaceRasterEdits
import io.github.psd2live.core.CanvasViewport
import io.github.psd2live.core.RigCanvasSupport
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.reboundTo
import io.github.psd2live.project.config
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Path
import kotlin.test.*

class ImageVisibilityScenario {
    @TempDir lateinit var temp: Path

    @Test fun importedArtMeshShowsPixelsBeforeAndAfterRebuildAndReopen() = Studio.run(temp, Characters.head(false)) {
        val layer = importImages("drop an image", listOf(Characters.disc(temp, "badge.png", 12)),
            insertion = ImageLayerInsertion(centre = 8f to 32f)).single()
        fun visible() = assertVisible(now.model, layer)
        visible()
        // Cached authored rigs from an earlier build may have the right key but placeholder tile dimensions.
        val original = now.model
        val badInventory = original.rig.puppet.atlas.copy(tiles = original.rig.puppet.atlas.tiles.map {
            if (it.id == original.rig.puppet.drawables.single { it.id.raw == mesh(layer) }.atlasTileId) it.copy(width = 1, height = 1) else it
        })
        val stored = original.authored.reboundTo(badInventory, original.rig.puppet.sources)
        val pipeline = PSD2LivePipeline()
        val config = now.document.config()
        val restored = assertNotNull(pipeline.materializedPreview(now.document.source, config, stored,
            pipeline.bindingKey(now.document.source, config), rebind = true))
        assertVisible(restored, layer)
        val mesh = now.model.rig.puppet.drawables.single { it.id.raw == mesh(layer) }.mesh!!
        edit("rebuild from current pixels", WorkspaceRasterEdits.REBUILD_MESH, req("layer_id" to layer))
        val rebuilt = now.model.rig.puppet.drawables.single { it.id.raw == mesh(layer) }.mesh!!
        assertContentEquals(mesh.indices, rebuilt.indices, "Import and pixel rebuild must use the same triangulation")
        assertEquals(mesh.positions.size, rebuilt.positions.size)
        for (i in mesh.positions.indices) assertEquals(mesh.positions[i], rebuilt.positions[i], 1e-3f)
        visible()
        reopen()
        visible()
        val exported = moc3()
        assertNotNull(exported.drawables.singleOrNull { it.id.raw == mesh(layer) }?.mesh)
    }

    private fun assertVisible(model: RigPreviewModel, layer: String) {
        val drawable = model.rig.puppet.drawables.single { model.rig.layerIdByDrawableId[it.id.raw] == layer }
        val tile = model.rig.puppet.atlas.tiles.single { it.id == drawable.atlasTileId }
        assertTrue(tile.width > 1 && tile.height > 1, "The texture inventory must use the image raster, not the geometry placeholder")
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            RigCanvasSupport.paintTexturedRig(graphics, model, RigCanvasSupport.evaluateExact(model),
                CanvasViewport(1.0, 0.0, 0.0, 64f, 64f), visibleLayerIds = setOf(layer))
        } finally { graphics.dispose() }
        assertTrue(image.getRGB(8, 32) ushr 24 > 200, "Newly imported pixels must be visible before rebuilding")
    }
}
