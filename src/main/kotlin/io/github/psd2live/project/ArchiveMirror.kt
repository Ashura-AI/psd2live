package io.github.psd2live.project

import kotlinx.serialization.json.*
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * The v2 split ([ProjectFormatV2.split]) of the revisions a working store has saved or opened, kept in the project's
 * [FOLDER] folder so that a save links each known revision's files instead of encoding, splitting and hashing it
 * again: a save then costs what changed, not the length of the history.
 *
 * `revisions/<key>.json` is `{version, index, rasters}` for the revision whose snapshot file is `<key>.json`: its
 * revision index as the archive holds it and the raster files its documents name; `document/` holds the nodes the
 * indexes name. Everything is content-addressed and written once, like the rest of the store. A record of another
 * [VERSION], or one whose files are not all there, is not used, and that revision is split as before.
 */
internal class ArchiveMirror(private val directory: Path) {
	private val logger = System.getLogger("io.github.psd2live.project.ArchiveMirror")

	/**
	 * Links into the staging [root] the index and node files of each revision in [keys] the mirror holds, and the
	 * raster files those revisions name from the working store's [blobs] into [stagedBlobs]. Returns the revisions
	 * staged: key to index.
	 */
	fun stage(keys: Collection<String>, root: Path, blobs: Path, stagedBlobs: Path): Map<String, JsonObject> {
		val staged = LinkedHashMap<String, JsonObject>()
		// Revisions share most of their files: each is looked at and linked once.
		val present = HashSet<Path>()
		fun present(path: Path) = path in present || Files.isRegularFile(path).also { if (it) present.add(path) }
		val linked = HashSet<Path>()
		fun linkOnce(source: Path, target: Path) { if (linked.add(target)) link(source, target) }
		for (key in keys) {
			val record = read(key) ?: continue
			val index = record.getValue("index").jsonObject
			val files = ProjectFormatV2.indexFiles(index)
			val rasters = record.getValue("rasters").jsonArray.map { it.jsonPrimitive.content }
			if (!files.all { present(directory.resolve(it)) } || !rasters.all { present(blobs.resolve(it)) }) continue
			for (file in files) linkOnce(directory.resolve(file), root.resolve(file))
			for (raster in rasters) linkOnce(blobs.resolve(raster), stagedBlobs.resolve(raster))
			ProjectFormatV2.writeRevision(root, key, index)
			staged[key] = index
		}
		return staged
	}

	/** Records revision [key], split into [index] with its node files under [root], naming [rasters]. Never fails a save. */
	fun record(key: String, index: JsonObject, root: Path, rasters: Collection<String>) {
		try {
			if (read(key) != null) return
			for (file in ProjectFormatV2.indexFiles(index)) link(root.resolve(file), directory.resolve(file))
			write(key, index, rasters)
		} catch (failure: Exception) {
			logger.log(System.Logger.Level.WARNING, "Revision $key not mirrored", failure)
		}
	}

	/**
	 * Takes the node files of an unpacked archive under [root] into the mirror, with a record for each of
	 * [revisions] (key to index and rasters), instead of deleting them. The working store reads these revisions
	 * from the mirror alone, so a failure fails the open.
	 */
	fun adopt(root: Path, revisions: Map<String, Pair<JsonObject, Collection<String>>>) {
		val document = root.resolve("document")
		val target = directory.resolve("document")
		require(!Files.exists(directory)) { "The archive mirror exists already" }
		Files.createDirectories(directory)
		if (Files.isDirectory(document)) Files.move(document, target) else Files.createDirectories(target)
		// A new folder nothing else reads yet: the records need no atomic replace.
		Files.createDirectories(directory.resolve("revisions"))
		for ((key, revision) in revisions) {
			require(key.matches(KEY)) { "Invalid revision key" }
			Files.write(directory.resolve("revisions/$key.json"), record(revision.first, revision.second))
		}
	}

	/**
	 * The self-contained snapshot of revision [key], joined from its nodes; revisions read with one [shared] share
	 * their nodes, bakes and journal entries.
	 */
	fun snapshot(key: String, shared: WorkspaceStore.SharedContent): JsonObject {
		val record = requireNotNull(read(key)) { "History snapshot is missing: $key" }
		// Opening checked the rig objects when it joined the revision; a build reads them from wherever they are.
		val snapshot = ProjectFormatV2.join(directory, record.getValue("index").jsonObject, shared.nodes) { true }
		val rig = snapshot["rigEdits"] as? JsonObject ?: return snapshot
		val journal = rig["authoringJournal"] as? JsonArray ?: return snapshot
		val entries = JsonArray(journal.map { entry -> if (entry is JsonObject) shared.entries.getOrPut(entry) { entry } else entry })
		return JsonObject(snapshot + ("rigEdits" to JsonObject(rig + ("authoringJournal" to entries))))
	}

	private fun read(key: String): JsonObject? {
		require(key.matches(KEY)) { "Invalid revision key" }
		val file = directory.resolve("revisions/$key.json")
		if (!Files.isRegularFile(file)) return null
		return runCatching { Json.parseToJsonElement(Files.readString(file)).jsonObject }.getOrNull()
			?.takeIf { it["version"]?.jsonPrimitive?.intOrNull == VERSION && it["index"] is JsonObject && it["rasters"] is JsonArray }
	}

	private fun record(index: JsonObject, rasters: Collection<String>): ByteArray = buildJsonObject {
		put("version", VERSION); put("index", index)
		putJsonArray("rasters") { rasters.forEach { add(it) } }
	}.toString().encodeToByteArray()

	private fun write(key: String, index: JsonObject, rasters: Collection<String>) {
		require(key.matches(KEY)) { "Invalid revision key" }
		val bytes = record(index, rasters)
		val file = directory.resolve("revisions/$key.json")
		Files.createDirectories(file.parent)
		val temporary = file.resolveSibling(".${file.fileName}.${UUID.randomUUID()}.tmp")
		try {
			Files.write(temporary, bytes)
			try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE) }
			catch (_: FileAlreadyExistsException) {}
			catch (_: AtomicMoveNotSupportedException) { if (!Files.exists(file)) Files.move(temporary, file) }
		} finally { Files.deleteIfExists(temporary) }
	}

	companion object {
		const val FOLDER = "archive"
		/** Changes whenever [ProjectFormatV2.split] would split a revision differently, so older records are not used. */
		const val VERSION = 1
		private val KEY = Regex("[0-9a-f]{64}")

		/**
		 * Gives [target] the immutable file [source]: a hard link, or where links are not possible a copy that keeps
		 * its modification time, so the archive writer knows its digest ([ProjectArchive.Digests]).
		 */
		fun link(source: Path, target: Path) {
			if (Files.isRegularFile(target)) return
			Files.createDirectories(target.parent)
			try { Files.createLink(target, source) }
			catch (_: FileAlreadyExistsException) {}
			catch (_: Exception) {
				val temporary = target.resolveSibling(".${target.fileName}.${UUID.randomUUID()}.tmp")
				try {
					ProjectArchive.copyKeepingTime(source, temporary)
					try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE) }
					catch (_: FileAlreadyExistsException) {}
					catch (_: AtomicMoveNotSupportedException) { if (!Files.exists(target)) Files.move(temporary, target) }
				} finally { Files.deleteIfExists(temporary) }
			}
		}
	}
}
