package io.github.psd2live.project

import io.github.psd2live.core.sim.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.random.Random
import kotlin.test.*

/** Simulation bakes are stored once each, packed, in the working store and in the archive, and read back exactly. */
class BakeStorageTest {
	@TempDir lateinit var temp: Path

	@Test fun packedJsonGivesBackItsValueElementForElement() {
		val floats = JsonArray(List(40) { JsonPrimitive(if (it % 5 == 0) -0f else it * 1.37e-7f) })
		val value = buildJsonObject {
			put("floats", floats)
			putJsonArray("nested") { add(floats); add(JsonArray(List(9) { JsonPrimitive(it) })) }
			// Integers, decimals not written as a float writes them, and short arrays keep their text.
			putJsonArray("decimals") { repeat(12) { add(JsonPrimitive(0.1)) } }
			putJsonArray("short") { add(1.5f); add(2.5f) }
			putJsonArray("mixed") { repeat(10) { add(JsonPrimitive(1.5f)) }; add("text") }
			put("name", "bake")
		}
		val packed = PackedJson.encode(value)
		assertEquals(value, PackedJson.decode(packed))
		assertEquals(value.toString(), PackedJson.decode(packed).toString())
		val many = JsonArray(List(1000) { JsonPrimitive(it * 1.37e-7f) })
		assertTrue(PackedJson.encode(many).size < many.toString().length / 2, "packed floats take half their text or more")
		// A value that already looks like a reference is kept as text.
		val reference = buildJsonObject { put("x", buildJsonObject { put(PackedJson.REF, 0) }); put("floats", floats) }
		assertEquals(reference, PackedJson.decode(PackedJson.encode(reference)))
	}

	@Test fun theWorkingStoreKeepsEachBakeOnceAndLoadsOneObjectPerBake() {
		val project = project(temp.resolve("a"))
		val root = project.store.projectRoot(SyntheticProject.PROJECT)
		val snapshots = Files.list(root.resolve("history/snapshots")).use { it.toList() }
		assertTrue(snapshots.none { Files.readString(it).contains("\"offsets\"") }, "a snapshot holds a bake")
		// Two different bakes over four revisions.
		assertEquals(2, Files.list(root.resolve("history/bakes")).use { it.count() })
		val loaded = WorkspaceStore(project.store.root).loadHistory(SyntheticProject.PROJECT)!!
		for (selection in loaded.selections()) assertEquals(selection.node.revisionId, WorkspaceRevisions.of(selection.snapshot))
		val bakes = loaded.selections().mapNotNull { it.snapshot.rigEdits.simEdits.singleOrNull()?.bake }
		assertEquals(3, bakes.size)
		assertSame(bakes[0], bakes[1], "revisions with one bake share it")
		assertEquals(project.tree.selections().mapNotNull { it.snapshot.rigEdits.simEdits.singleOrNull()?.bake?.toJson() }, bakes.map { it.toJson() })
	}

	@Test fun aSavedProjectKeepsItsBakesAsFilesAndOpensToTheSameRevisions() = runBlocking<Unit> {
		val project = project(temp.resolve("b"))
		val saved = temp.resolve("baked.psd2live")
		ProjectRepository(writeHeadCache = false).save(project.capture(), saved)
		ZipFile(saved.toFile()).use { zip ->
			val names = zip.entries().toList().map { it.name }
			assertEquals(2, names.count { it.startsWith("${ProjectFormatV2.BAKES}/") && it.endsWith(".bin") })
			val rigNodes = names.filter { it.startsWith("document/nodes/rig/") }
			assertTrue(rigNodes.none { zip.getInputStream(zip.getEntry(it)).readBytes().decodeToString().contains("\"offsets\"") })
			val schemas = names.filter { it.startsWith("history/revisions/") }.map { name ->
				Json.parseToJsonElement(zip.getInputStream(zip.getEntry(name)).readBytes().decodeToString()).jsonObject.getValue("schema").jsonPrimitive.int
			}
			assertEquals(listOf(ProjectFormatV2.REVISION_SCHEMA, ProjectFormatV2.REVISION_SCHEMA_BAKES), schemas.distinct().sorted())
		}
		ProjectRepository().open(saved).use { opened ->
			val before = project.tree.selections().associate { it.node.id to it.node.revisionId }
			assertEquals(before, opened.history.selections().associate { it.node.id to it.node.revisionId })
			for (selection in opened.history.selections()) assertEquals(selection.node.revisionId, WorkspaceRevisions.of(selection.snapshot))
		}
	}

	/** A small project whose history bakes a simulation, keeps the bake, bakes it again and clears it. */
	private fun project(directory: Path): SyntheticProject {
		val project = SyntheticProject(directory, layers = 2, size = 8, revisions = 1)
		val random = Random(3)
		fun bake(seed: Int): SimBakeResult {
			val keys = floatArrayOf(-30f, 0f, 30f)
			val offsets = mapOf("mesh" to List(keys.size) { FloatArray(64) { if (it % 4 == 0) 0f else random.nextFloat() * seed } })
			return SimBakeResult.fromJson(SimBakeResult("fp$seed", mapOf("mesh" to 32), emptyList(),
				listOf(SimBakedMode(SimBakedAxis("ParamSim", keys, offsets), 12f, 0.5f))).toJson())
		}
		val edit = RigSimEdit("sim", "Sim", SimKind.CLOTH, listOf("mesh"))
		val first = bake(1)
		for ((step, simulation) in listOf(edit.copy(bake = first), edit.copy(name = "Renamed", bake = first), edit.copy(bake = bake(2)), edit).withIndex()) {
			val head = project.tree.head()
			val document = head.snapshot.copy(rigEdits = head.snapshot.rigEdits.copy(simEdits = listOf(simulation)))
			val revision = WorkspaceRevisions.of(document)
			project.tree.commit(head.node.id, document, revision, revision, "Bake $step", "user")
		}
		project.store.persistHistory(SyntheticProject.PROJECT, project.tree.state())
		return project
	}
}
