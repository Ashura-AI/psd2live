package io.github.psd2live.tools

import io.github.psd2live.core.RigCheckpoint
import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.scenario.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.interop.cmo3.Cmo3Import
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * A model finished elsewhere comes in as a cmo3: it is imported, edited past a checkpoint interval, one of its layers
 * split and another's material recoloured, the project reopened, and exported back to cmo3. The import is never regenerated, edits replay on it, and the
 * cmo3 written reads back to what the editor shows.
 *
 * A development tool: it runs the whole pipeline end to end, too long for the suite (PSD2LIVE_TOOLS=1).
 */
class Cmo3ImportScenario {
	@TempDir lateinit var temp: Path

	@BeforeEach fun tools() = requireTools()

	@Test fun importEditSplitReopenAndExportBack() {
		val source = temp.resolve("made-elsewhere")
		Files.createDirectories(source)
		val cmo3 = Studio(source, emptySet()).use { studio -> studio.open(Characters.figure()); studio.export("cmo3").single { it.toString().endsWith(".cmo3") } }
		Studio.run(temp, checks = setOf(Studio.Check.REPLAY)) {
			importCmo3(cmo3)
			expect("in the library", now.document.source.layers.filter { it.raster.width > 0 && it.raster.height > 0 }.all { layer -> now.document.layout.layerMaterials[layer.id.raw]
				?.let(now.document.layout.materials::item)?.raster?.rgba?.contentEquals(layer.raster.rgba) == true }) { "an imported ArtMesh's image is not a material" }
			val layer = now.document.rigEdits.importedLayerIds.getValue("ArtMeshBackHair")
			repeat(RigEditOverlay.CHECKPOINT_INTERVAL + 1) { edit("axis $it", "parameter_create", req("parameter_id" to "P$it", "name" to "P$it")) }
			val journal = now.document.rigEdits.authoringJournal
			expect("imported", journal.filter(RigCheckpoint::isRecord).none { "generated" in it }) { "an imported model was regenerated" }
			edit("split an imported layer", "source_split_polygon", req("layer_id" to layer, "names" to listOf("Left", "Right"),
				"polygon" to listOf(listOf(0, 0), listOf(210, 0), listOf(210, 420), listOf(0, 420))))
			// Not written back as it came: an imported layer's material can change its pixels, and the atlas follows.
			val face = now.document.rigEdits.importedLayerIds.values.first { id -> id != layer && id !in now.document.deletedLayerIds &&
				id in now.document.layout.layerMaterials && now.document.source.layers.any { it.id.raw == id && it.raster.width > 0 } }
			val before = now.document.source.layers.single { it.id.raw == face }.raster
			edit("tint an imported layer's material", "material_adjust", req("id" to now.document.layout.layerMaterials.getValue(face), "hue" to 120))
			expect("repainted", !now.document.source.layers.single { it.id.raw == face }.raster.rgba.contentEquals(before.rgba)) {
				"the imported layer did not take its material's new pixels" }
			reopen()
			val written = export("cmo3").single { it.toString().endsWith(".cmo3") }
			val readBack = step("read the cmo3 back") { Cmo3Import.fromModelSource(Cmo3.read(Files.readAllBytes(written)).root as CModelSource) }
			exportMatchesEditor(this, readBack, "cmo3")
		}
	}
}
