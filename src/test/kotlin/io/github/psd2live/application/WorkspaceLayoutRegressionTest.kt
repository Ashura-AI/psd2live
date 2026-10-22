package io.github.psd2live.application

import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.Side
import io.github.psd2live.project.*
import io.github.psd2live.scenario.Characters
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.umamo.format.art.LayerRaster
import kotlin.test.*

class WorkspaceLayoutRegressionTest {
    private fun operation(name: String, vararg fields: Pair<String, String>) =
        WorkspaceDocumentOperation(name, buildJsonObject { fields.forEach { (key, value) -> put(key, value) } })

    @Test fun classificationPatchesKeepPreviouslyPendingFields() = runBlocking {
        val document = Characters.head(false)
        val model = WorkspacePreviewBuilder().build(document)
        val first = WorkspaceDocumentEdits.apply(operation("layer_classify", "layer_id" to "hair", "role" to "objects"), document, model)
        val second = WorkspaceDocumentEdits.apply(operation("layer_classify", "layer_id" to "hair", "side" to "right"), first, model)
        val pending = assertNotNull(second.layout.generation.pendingClassifications["hair"])
        assertEquals(SemanticTag.OBJECTS, pending.tag)
        assertEquals(Side.RIGHT, pending.side)
        val generated = WorkspaceGenerationEdits.generate(second, model, null)
        assertEquals(pending, generated.layerOverrides["hair"])
    }

    @Test fun typedImageReplacementTakesTheMaterialAlong() = runBlocking {
        val document = WorkspaceMaterialEdits.addLinked(Characters.head(false), "head.psd")
        val builder = WorkspacePreviewBuilder()
        val runtime = WorkspaceRuntime({ document: WorkspaceDocument -> builder.build(document) })
        val before = runtime.install(runtime.state.value.state, "test", document, builder.build(document))
        val original = document.source.layers.single { it.id.raw == "face" }.raster
        val replacement = LayerRaster(original.width, original.height, original.rgba.copyOf().also { bytes ->
            for (o in bytes.indices step 4) if (bytes[o + 3].toInt() != 0) {
                bytes[o] = -1; bytes[o + 1] = 0; bytes[o + 2] = 0
            }
        })
        val replaced = WorkspaceTextureCommands(runtime).execute(before.projectId, before.state,
            WorkspaceTextureEdit.ReplaceImage("face", WorkspaceTextureImage.Raster(replacement)), MutationAuthor.USER).commit.capture
        // The face alone showed its material: the material is its one source and follows the new image.
        val face = document.layout.layerMaterials.getValue("face")
        assertEquals(face, replaced.document.layout.layerMaterials["face"])
        assertContentEquals(replacement.rgba, replaced.document.layout.materials.item(face)!!.raster.rgba)
        // Editing the material edits the face.
        val adjusted = WorkspaceDocumentCommands(runtime).execute(replaced.projectId, replaced.state, "Adjust the face's material",
            listOf(WorkspaceDocumentOperation("material_adjust", buildJsonObject { put("id", face); put("hue", 90) })), MutationAuthor.USER).capture.document
        assertContentEquals(adjusted.layout.materials.item(face)!!.raster.rgba, adjusted.source.layers.single { it.id.raw == "face" }.raster.rgba)
        assertFalse(replacement.rgba.contentEquals(adjusted.source.layers.single { it.id.raw == "face" }.raster.rgba))
        assertEquals(document.layout.layerMaterials["hair"], adjusted.layout.layerMaterials["hair"])
        val current = runtime.capture()
        val linked = WorkspaceTextureCommands(runtime).execute(current.projectId, current.state,
            WorkspaceTextureEdit.ReplaceImage("face", WorkspaceTextureImage.Material(document.layout.layerMaterials.getValue("hair"))),
            MutationAuthor.USER).commit.capture.document
        assertEquals(document.layout.layerMaterials["hair"], linked.layout.layerMaterials["face"])
    }

    private fun WorkspaceDocument.withLayers(layers: List<org.umamo.format.art.SourceLayer>) =
        copy(source = WorkspaceSourceArt(source.widthPx, source.heightPx, layers, source.groups))

    private fun WorkspaceDocument.withRaster(layerId: String, raster: LayerRaster) = withLayers(source.layers.map {
        if (it.id.raw == layerId) (WorkspaceSourceLayer.copyOf(it, it.order) as WorkspaceSourceLayer).copy(raster = raster) else it
    })

    @Test fun aLayerSharingItsMaterialGetsACopyAndAddedLayersGetMaterials() {
        val document = WorkspaceMaterialEdits.addLinked(Characters.head(false), "head.psd")
        val hair = document.layout.layerMaterials.getValue("hair")
        val hairRaster = document.layout.materials.item(hair)!!.raster
        val faceRaster = document.source.layers.single { it.id.raw == "face" }.raster
        // The face shows the hair's material too; then only the face's pixels change.
        val sharing = document.withRaster("face", hairRaster).let { it.copy(layout = it.layout.copy(layerMaterials = it.layout.layerMaterials + ("face" to hair))) }
        val reconciled = WorkspaceMaterialLinks.reconcile(sharing, sharing.withRaster("face", faceRaster))
        val copy = reconciled.layout.layerMaterials.getValue("face")
        assertNotEquals(hair, copy)
        assertSame(faceRaster, reconciled.layout.materials.item(copy)!!.raster)
        assertEquals(hair, reconciled.layout.layerMaterials["hair"])
        assertSame(hairRaster, reconciled.layout.materials.item(hair)!!.raster)
        // A layer an edit adds is given a material of its own.
        val face = document.source.layers.single { it.id.raw == "face" }
        val extra = (WorkspaceSourceLayer.copyOf(face, 0) as WorkspaceSourceLayer).copy(id = org.umamo.format.art.LayerId("extra"))
        val adopted = WorkspaceMaterialLinks.reconcile(document, document.withLayers(document.source.layers + extra))
        val item = adopted.layout.materials.item(adopted.layout.layerMaterials.getValue("extra"))!!
        assertEquals(WorkspaceMaterialLinks.LAYERS_FOLDER, adopted.layout.materials.folder(item.folderId!!)!!.name)
    }

}
