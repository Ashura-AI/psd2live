package io.github.psd2live.scenario

import io.github.psd2live.application.WorkspaceHitAreaEdits
import io.github.psd2live.format.model.Canvas
import io.github.psd2live.format.model.HitAreaIR
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.Moc3Target
import io.github.psd2live.targets.runtime.P2lrt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Hit areas set up on a generated figure: the Head and Body presets, an area of its own, renames and reordering - each
 * edit replayed cold and undone - kept through a save, hit-tested as the runtime does, and written to model3.json and
 * the .p2lrt file.
 */
class HitAreaScenario {
	@TempDir lateinit var temp: Path

	@Test fun hitAreasAreEditedKeptHitTestedAndExported() = Studio.run(temp, Characters.figure()) {
		val face = mesh("face"); val top = mesh("top"); val skirt = mesh("skirt")
		fun areas() = now.document.rigEdits.hitAreas
		edit("set up Head and Body", WorkspaceHitAreaEdits.PRESET, req())
		expect("presets", areas().map { Triple(it.id, it.name, it.meshes) } ==
			listOf(Triple("HitAreaHead", "Head", listOf(face)), Triple("HitAreaBody", "Body", listOf(top)))) { "presets ${areas()}" }
		edit("a skirt area before Body", WorkspaceHitAreaEdits.PUT, req("id" to "HitAreaSkirt", "name" to "Skirt",
			"meshes" to listOf("mesh:$skirt"), "before_id" to "HitAreaBody"))
		edit("Body covers the skirt too", WorkspaceHitAreaEdits.PUT, req("id" to "HitAreaBody", "name" to "Torso", "meshes" to listOf("mesh:$top", "mesh:$skirt")))
		expect("order", areas().map { it.id } == listOf("HitAreaHead", "HitAreaSkirt", "HitAreaBody") && areas()[2].name == "Torso") { "areas ${areas()}" }
		refused("an unknown mesh", WorkspaceHitAreaEdits.PUT, req("id" to "HitAreaHat", "meshes" to listOf("mesh:nothing")))
		refused("a taken ID", WorkspaceHitAreaEdits.PUT, req("id" to "HitAreaSkirt", "new_id" to "HitAreaHead"))
		refused("a new area without meshes", WorkspaceHitAreaEdits.PUT, req("id" to "HitAreaHat", "name" to "Hat"))
		refused("an ID outside Cubism's alphabet", WorkspaceHitAreaEdits.PUT, req("id" to "Hit-Hat", "meshes" to listOf("mesh:$face")))

		// The first area hit wins: the skirt is in Skirt before it is in Body.
		fun hit(x: Float, y: Float) = WorkspaceHitAreaEdits.test(areas(), now.model, emptyMap(), x, y)?.id
		expect("hit test", hit(210f, 70f) == "HitAreaHead" && hit(210f, 270f) == "HitAreaSkirt" && hit(210f, 180f) == "HitAreaBody" && hit(10f, 10f) == null) {
			"head ${hit(210f, 70f)}, skirt ${hit(210f, 270f)}, top ${hit(210f, 180f)}, outside ${hit(10f, 10f)}"
		}

		val kept = areas()
		reopen()
		expect("reopened", areas() == kept) { "reopened as ${areas()}" }

		val model3 = export("moc3").single { it.toString().endsWith(".model3.json") }
		val entries = Json.parseToJsonElement(Files.readString(model3)).jsonObject.getValue("HitAreas").jsonArray.map {
			it.jsonObject.getValue("Name").jsonPrimitive.content to it.jsonObject.getValue("Id").jsonPrimitive.content
		}
		expect("model3", entries == listOf("Head" to face, "Skirt" to skirt, "Torso" to top, "Torso" to skirt)) { "model3 HitAreas $entries" }
		val p2lrt = String(Files.readAllBytes(export("p2lrt").single { it.toString().endsWith(".p2lrt") }), Charsets.ISO_8859_1)
		expect("p2lrt", "HITA" in p2lrt && "HitAreaSkirt" in p2lrt) { "the runtime file has no HITA chunk with the document's areas" }

		edit("delete Skirt", WorkspaceHitAreaEdits.DELETE, req("id" to "HitAreaSkirt"))
		expect("deleted", areas().map { it.id } == listOf("HitAreaHead", "HitAreaBody")) { "areas ${areas()}" }
	}

	@Test fun aModelWithoutHitAreasWritesNoneToModel3() = Studio.run(temp, Characters.figure(), checks = emptySet()) {
		val model3 = export("moc3").single { it.toString().endsWith(".model3.json") }
		expect("model3", "HitAreas" !in Json.parseToJsonElement(Files.readString(model3)).jsonObject) { Files.readString(model3) }
	}

	@Test fun targetsReportWhatTheyCannotSayAboutHitAreas() {
		val ir = RigIR(Canvas(1f, 1f), emptyList(), hitAreas = listOf(HitAreaIR("HitAreaBody", "Body", listOf("top", "skirt")), HitAreaIR("HitAreaHead", "Head", listOf("face"))))
		assertEquals(listOf("HitAreaBody"), Moc3Target.hitAreaLosses(ir).map { it.objectId })
		assertEquals(listOf("HitAreaBody", "HitAreaHead"), P2lrt.version1Losses(ir).map { it.objectId })
	}
}
