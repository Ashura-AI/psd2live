package org.umamo.interop.moc3

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.umamo.format.FileKind
import org.umamo.format.moc3.Moc3
import org.umamo.format.moc3.json.Cdi3Json
import org.umamo.format.moc3.json.DisplayDrawable
import org.umamo.format.moc3.json.DisplayParameter
import org.umamo.format.moc3.json.DisplayParameterGroup
import org.umamo.format.moc3.json.DisplayPart
import org.umamo.format.moc3.json.FileReferences
import org.umamo.format.moc3.json.Model3Json
import org.umamo.format.moc3.json.UserData3Json
import org.umamo.format.moc3.json.UserDataEntry
import org.umamo.format.moc3.json.UserDataMeta
import org.umamo.format.moc3.moc.MocVersion
import org.umamo.interop.ExportNotice
import org.umamo.interop.ExportReport
import org.umamo.interop.moc3.export.CanvasToParentSpace
import org.umamo.interop.moc3.export.Moc3Export
import org.umamo.interop.moc3.export.Moc3WrittenIds
import org.umamo.interop.mocVersion
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.PuppetModel

/**
 * Builds the whole `.moc3` FILE FAMILY - the moc, its `model3.json` manifest, a `cdi3.json` of
 * display names, a `userdata3.json` of art-mesh user data, the atlas pages, and whatever sidecars
 * the document was imported with.
 *
 * A baked model is a family, not a file: the runtime loads the manifest, and the manifest is what
 * names the atlas pages a moc's texture indices point into.  Writing the moc alone produces something
 * no runtime can open, so the export's unit is this bundle.
 *
 * Everything here is pure - the caller supplies the atlas bytes and the retained sidecar texts, and
 * receives named byte arrays to write wherever it likes.  That keeps the family layout testable
 * without a filesystem, and keeps the IO (and its per-platform limits) in the app layer.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md § Export</a>
 */
object Moc3Sidecars {
	/** The moc extension the family's base name is derived against, dotted for the name strip. */
	private val MOC3_EXTENSION: String = ".${FileKind.Moc3.extension}"

	/**
	 * The family base name for a moc file called [fileName] - every sibling is named off this.
	 *
	 * The strip ignores CASE.  A file spelled `X.MOC3` would otherwise keep its extension in the base
	 * name, and every generated sibling - the manifest included - would be named after an `X.MOC3.moc3`
	 * that no write ever produces.  Import and export both derive the name this way, so a re-export
	 * lands the family in the shape the model already used.
	 *
	 * @param String fileName The moc's own file name.
	 * @return String The base name the rest of the family hangs off.
	 */
	fun basenameFor(fileName: String): String =
		if (fileName.endsWith(MOC3_EXTENSION, ignoreCase = true)) {
			fileName.dropLast(MOC3_EXTENSION.length)
		} else {
			fileName
		}

	/** The cdi3 schema version the editor writes. */
	private const val CDI3_VERSION: Int = 3

	/** The userdata3 schema version the editor writes. */
	private const val USERDATA3_VERSION: Int = 3

	/** The model3 schema version the editor writes. */
	private const val MODEL3_VERSION: Int = 3

	/**
	 * One file of the exported family.
	 *
	 * @property String    name  The file name, relative to the moc's own directory.
	 * @property ByteArray bytes The contents.
	 */
	class BundleFile(val name: String, val bytes: ByteArray)

	/**
	 * A retained sidecar carried through untouched.
	 *
	 * Pass-through, not synthesis: physics, poses, expressions, and user data are authored elsewhere
	 * and reference parameters and parts by id, which the export preserves - so re-emitting the
	 * original text is both lossless and the only honest option, since Umamo does not model them.
	 *
	 * @property SidecarKind kind     Which sidecar this is, for the manifest wiring below.
	 * @property String      fileName The name to write it under.
	 * @property String      text     The original file's text.
	 */
	class PassThroughSidecar(val kind: SidecarKind, val fileName: String, val text: String)

	/** The sidecar families a MOC3 export carries through from the imported document. */
	enum class SidecarKind {
		Physics,
		Pose,
		UserData,
		Expression,
		Motion,
	}

	/**
	 * One atlas page's bytes and the name to write them under.
	 *
	 * @property String    fileName The page's file name, manifest-relative.
	 * @property ByteArray bytes    The PNG bytes, verbatim from the source when there is one.
	 */
	class AtlasPage(val fileName: String, val bytes: ByteArray)

	/**
	 * The complete export: every file to write, plus the lowering's advisory report.
	 *
	 * @property List         files  The family, moc first.
	 * @property ExportReport report The notices from the moc lowering.
	 */
	class Bundle(val files: List<BundleFile>, val mocFileName: String, val report: ExportReport)

	/**
	 * Builds the family for [puppet].
	 *
	 * @param PuppetModel puppet   The rig to export.
	 * @param String      basename The family's base name (no extension).
	 * @param MocVersion  version  The moc version to bake; the document's runtime target by default.
	 * @param List        pages    The atlas pages, in the drawables' texture-page index order.
	 * @param List        sidecars The retained sidecars to carry through.
	 * @param Model3Json? source   The imported manifest, whose non-file sections carry through.
	 * @param CanvasToParentSpace? canvasToParentSpace The unkeyed-drawable space inverse, or null.
	 * @param Moc3ExportOptions options What the rigger chose to include; the default is the
	 *   options-less behavior.
	 * @return Bundle The files to write, which of them is the moc, and the report.
	 */
	fun bundle(
		puppet: PuppetModel,
		basename: String,
		version: MocVersion = puppet.runtimeTarget.mocVersion(),
		pages: List<AtlasPage>,
		sidecars: List<PassThroughSidecar> = emptyList(),
		source: Model3Json? = null,
		canvasToParentSpace: CanvasToParentSpace? = null,
		options: Moc3ExportOptions = Moc3ExportOptions.Default,
	): Bundle {
		// Lowered rather than written outright, because the cdi3 below has to name the objects by the ids
		// the MOC actually got: an id the record width forced short is not the id the model carries.
		val lowered = Moc3Export.toMocDocument(puppet, version, canvasToParentSpace, options)
		val sidecars = sidecars.mapNotNull { sidecar -> withWrittenIds(sidecar, puppet, lowered.writtenIds) }
		val mocBytes = Moc3.write(lowered.document)
		val report = lowered.report
		val mocFileName = "$basename$MOC3_EXTENSION"
		// Null when the rigger opted the cdi3 out: the file and the manifest's reference to it must
		// move together, or the manifest names a file the bundle does not contain.
		val displayInfoName = "$basename.cdi3.json".takeIf { options.includeDisplayInfo }
		val files = ArrayList<BundleFile>(pages.size + sidecars.size + 3)
		files.add(BundleFile(mocFileName, mocBytes))
		if (displayInfoName != null) {
			files.add(
				BundleFile(displayInfoName, Moc3.writeCdi3(displayInfo(puppet, lowered.writtenIds)).encodeToByteArray()),
			)
		}
		for (page in pages) {
			files.add(BundleFile(page.fileName, page.bytes))
		}
		for (sidecar in sidecars) {
			if (sidecar.kind == SidecarKind.UserData && !options.includeUserData) continue
			files.add(BundleFile(sidecar.fileName, sidecar.text.encodeToByteArray()))
		}
		// A retained userdata3 wins over the synthesized one; either way the option gates the file and its
		// manifest reference together.
		val userDataName =
			if (!options.includeUserData) {
				null
			} else {
				sidecars.firstOrNull { it.kind == SidecarKind.UserData }?.fileName
					?: userData(puppet, lowered.writtenIds)?.let { userData ->
						"$basename.userdata3.json".also { name ->
							files.add(BundleFile(name, Moc3.writeUserData3(userData).encodeToByteArray()))
						}
					}
			}
		// Each hit area names its ArtMesh as the moc wrote it; one the moc left out names nothing it contains and is
		// reported. A manifest left with none has no HitAreas, as one that never had any.
		val (written, dropped) = source?.hitAreas.orEmpty().partition { lowered.writtenIds.wroteDrawable(DrawableId(it.id)) }
		val hitAreas = source?.hitAreas?.let { all ->
			written.map { it.copy(id = lowered.writtenIds.drawableId(DrawableId(it.id))) }.takeIf { it.isNotEmpty() || all.isEmpty() }
		}
		val reported = if (dropped.isEmpty()) report else report.copy(notices = report.notices + dropped.map { ExportNotice.HitAreaDropped(it.name, it.id) })
		val manifest =
			Model3Json(
				version = MODEL3_VERSION,
				fileReferences =
					FileReferences(
						moc = mocFileName,
						textures = pages.map { page -> page.fileName },
						pose = sidecars.firstOrNull { it.kind == SidecarKind.Pose }?.fileName,
						physics = sidecars.firstOrNull { it.kind == SidecarKind.Physics }?.fileName,
						userData = userDataName,
						displayInfo = displayInfoName,
						// Expression and motion entries carry their own fade times and group names, so the
						// source's own entries are re-emitted rather than rebuilt from the file list - the
						// files themselves ride along in `sidecars` under those same relative names.
						expressions = source?.fileReferences?.expressions,
						motions = source?.fileReferences?.motions,
					),
				// Auto-wiring groups (EyeBlink / LipSync) and hit areas are USER DATA that lives only in
				// the manifest.  Nothing in PuppetModel holds them, so dropping them here would quietly
				// delete a rigger's eye-blink wiring on the first MOC3 round trip.
				groups = source?.groups,
				hitAreas = hitAreas,
			)
		files.add(BundleFile("$basename.model3.json", Moc3.writeModel3(manifest).encodeToByteArray()))
		return Bundle(files, mocFileName, reported)
	}

	/**
	 * [sidecar] naming the model's parameters and parts by the ids the moc was written with, as [displayInfo] does.
	 *
	 * Expressions, poses and motions name objects by id; an id the record width forced short, or a part the
	 * lowering left out (a hidden or guide part the options exclude), would otherwise leave the file naming
	 * something the moc does not contain.  A pose drops such parts and the groups they empty, and goes when no
	 * group is left; expression entries and motion curves are renamed only.  Ids the model does not hold are kept
	 * as they are, and a sidecar nothing in it changed keeps its text byte for byte.
	 *
	 * @param PassThroughSidecar sidecar    The sidecar to rewrite.
	 * @param PuppetModel        puppet     The rig.
	 * @param Moc3WrittenIds     writtenIds What the lowering wrote each object's id as.
	 * @return PassThroughSidecar? The sidecar as written, or null for a pose left without groups.
	 */
	fun withWrittenIds(sidecar: PassThroughSidecar, puppet: PuppetModel, writtenIds: Moc3WrittenIds): PassThroughSidecar? {
		val parameters = puppet.parameters.associate { it.id.raw to writtenIds.parameterId(it.id) }
		val parts = puppet.parts.associate { it.id.raw to (if (writtenIds.wrotePart(it.id)) writtenIds.partId(it.id) else null) }
		fun parameter(id: String) = parameters[id] ?: id
		fun part(id: String): String? = if (id in parts) parts[id] else id
		val root = when (sidecar.kind) {
			SidecarKind.Expression, SidecarKind.Pose, SidecarKind.Motion -> runCatching { Json.parseToJsonElement(sidecar.text) as? JsonObject }.getOrNull()
			else -> null
		} ?: return sidecar
		fun JsonObject.withId(id: String): JsonObject = JsonObject(this + ("Id" to JsonPrimitive(id)))
		fun JsonElement.id(): String? = ((this as? JsonObject)?.get("Id") as? JsonPrimitive)?.takeIf { it.isString }?.content
		val rewritten: JsonObject? = when (sidecar.kind) {
			SidecarKind.Expression -> if ("Parameters" !in root) root else JsonObject(root + ("Parameters" to JsonArray((root["Parameters"] as? JsonArray).orEmpty().map { entry ->
				entry.id()?.let { (entry as JsonObject).withId(parameter(it)) } ?: entry
			})))
			SidecarKind.Motion -> if ("Curves" !in root) root else JsonObject(root + ("Curves" to JsonArray((root["Curves"] as? JsonArray).orEmpty().map { curve ->
				val id = curve.id() ?: return@map curve
				when (((curve as JsonObject)["Target"] as? JsonPrimitive)?.content) {
					"Parameter" -> curve.withId(parameter(id))
					"PartOpacity" -> part(id)?.let(curve::withId) ?: curve
					else -> curve
				}
			})))
			else -> {
				val groups = (root["Groups"] as? JsonArray).orEmpty().mapNotNull { group ->
					(group as? JsonArray)?.mapNotNull { entry ->
						val id = entry.id() ?: return@mapNotNull entry
						val written = part(id) ?: return@mapNotNull null
						val links = ((entry as JsonObject)["Link"] as? JsonArray)?.mapNotNull { link ->
							val id = (link as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull link
							part(id)?.let(::JsonPrimitive)
						}
						JsonObject(entry.withId(written) + (links?.let { mapOf("Link" to JsonArray(it)) } ?: emptyMap()))
					}?.takeIf { it.isNotEmpty() }?.let(::JsonArray)
				}
				if (groups.isEmpty()) null else JsonObject(root + ("Groups" to JsonArray(groups)))
			}
		}
		if (rewritten == null) return null
		if (rewritten == root) return sidecar
		return PassThroughSidecar(sidecar.kind, sidecar.fileName, REWRITE_JSON.encodeToString(JsonObject.serializer(), rewritten))
	}

	/** How a rewritten sidecar is printed; number literals keep the text they were read with. */
	private val REWRITE_JSON: Json = Json { prettyPrint = true }

	/**
	 * The `userdata3.json` for [puppet]: one `ArtMesh` entry per written drawable with user data.
	 *
	 * Unlike the cdi3 this is written only when some drawable carries user data - an empty user-data
	 * file says nothing a runtime could use.  Ids are the ones the MOC was written with, for the same
	 * join reason as [displayInfo], and drawables the lowering dropped are left out.
	 *
	 * @param PuppetModel    puppet     The rig.
	 * @param Moc3WrittenIds writtenIds What the lowering wrote each object's id as.
	 * @return UserData3Json? The user data, or null when no written drawable has any.
	 */
	fun userData(
		puppet: PuppetModel,
		writtenIds: Moc3WrittenIds,
	): UserData3Json? {
		val entries =
			puppet.drawables
				.filter { drawable -> drawable.userData.isNotEmpty() && writtenIds.wroteDrawable(drawable.id) }
				.map { drawable -> UserDataEntry(target = "ArtMesh", id = writtenIds.drawableId(drawable.id), value = drawable.userData) }
		if (entries.isEmpty()) return null
		return UserData3Json(
			version = USERDATA3_VERSION,
			meta = UserDataMeta(userDataCount = entries.size, totalUserDataSize = entries.sumOf { it.value.encodeToByteArray().size }),
			userData = entries,
		)
	}

	/**
	 * The `cdi3.json` for [puppet]: parameter, group, part, and art-mesh display names.
	 *
	 * Written whenever [Moc3ExportOptions.includeDisplayInfo] allows it, even when every name equals
	 * its id.  A cdi3 is what carries the ONE thing the MOC3 cannot - what the rigger called each
	 * object - and deciding it is "not worth writing" because this model happens to use default names
	 * would make the family's shape depend on the data in it.  The rigger's explicit opt-out in the
	 * export options is the one sanctioned exception: a CHOICE may shape the family, the data may not.
	 *
	 * Every id here is the id the MOC was written with, taken from [writtenIds] rather than from the
	 * model: the runtime joins the two files on that string, so an id the record width forced short
	 * would leave its cdi3 entry naming an object the MOC3 does not contain - the display name, the
	 * parameter's group placement, and its combined-parameter pairing all quietly stranded.  Parameter
	 * GROUP ids are the exception, and stay verbatim: they exist only in this file, so no MOC3 record
	 * bounds them.
	 *
	 * Parts and art meshes are also filtered to the ones the lowering actually WROTE - the export drops
	 * sketch subtrees, mesh-less drawables, and unkeyed drawables it cannot invert into parent space.
	 * Naming a dropped object is dead weight on its own, and worse than that alongside the shortening
	 * above: a dropped object claims no id, so an over-long id can shorten onto exactly its string and
	 * leave the file with two entries under one id for a reader's join to choose between.
	 *
	 * @param PuppetModel    puppet     The rig.
	 * @param Moc3WrittenIds writtenIds What the lowering wrote each object's id as.
	 * @return Cdi3Json The display info.
	 */
	fun displayInfo(
		puppet: PuppetModel,
		writtenIds: Moc3WrittenIds,
	): Cdi3Json {
		val groupIdByParameter = HashMap<String, String>()
		val groups = ArrayList<DisplayParameterGroup>()

		/**
		 * Walks the parameter tree, recording each group and the group every parameter belongs to.
		 *
		 * @param List   nodes         The nodes at this level.
		 * @param String parentGroupId The enclosing group's id, empty at the root.
		 */
		fun walk(nodes: List<ParameterNode>, parentGroupId: String) {
			for (node in nodes) {
				when (node) {
					is ParameterNode.Param -> groupIdByParameter[node.id.raw] = parentGroupId
					is ParameterNode.Group -> {
						groups.add(DisplayParameterGroup(id = node.id.raw, groupId = parentGroupId, name = node.name))
						walk(node.children, node.id.raw)
					}
				}
			}
		}
		walk(puppet.parameterTree, "")

		return Cdi3Json(
			version = CDI3_VERSION,
			parameters =
				puppet.parameters.map { parameter ->
					DisplayParameter(
						id = writtenIds.parameterId(parameter.id),
						// Keyed by the MODEL id: the group walk above indexed the parameter tree, which
						// holds model ids like everything else in the rig.
						groupId = groupIdByParameter[parameter.id.raw] ?: "",
						name = parameter.name,
					)
				},
			parameterGroups = groups,
			parts =
				puppet.parts
					.filter { part -> writtenIds.wrotePart(part.id) }
					.map { part -> DisplayPart(id = writtenIds.partId(part.id), name = part.name) },
			// The pair order is the same one the import reads back: horizontal first, then vertical.
			combinedParameters =
				puppet.parameterLinks
					.map { link ->
						listOf(writtenIds.parameterId(link.horizontal), writtenIds.parameterId(link.vertical))
					}
					.takeIf { links -> links.isNotEmpty() },
			drawables =
				puppet.drawables
					.filter { drawable -> writtenIds.wroteDrawable(drawable.id) }
					.map { drawable ->
						DisplayDrawable(id = writtenIds.drawableId(drawable.id), name = drawable.name)
					}
					.takeIf { meshes -> meshes.isNotEmpty() },
		)
	}
}