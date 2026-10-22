package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.WorkspaceDocument
import kotlinx.serialization.json.*
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.PuppetModel

/**
 * Object-level edits Cubism keeps in its Edit menu: paste copies of objects ([RigObjectPaste]), duplicate one mesh's
 * topology onto others ([RigMeshDuplication]), find and replace object names or IDs, and convert IDs
 * ([RigIdConversion]). Each is a pure candidate on the document: journal entries, plus for an ID conversion the
 * document's own references outside the rig (motions, user physics, swing targets).
 */
internal object WorkspaceObjectEdits {
	const val PASTE = "object_paste"
	const val MESH_DUPLICATE = "mesh_duplicate"
	const val RENAME = "object_rename"
	const val CONVERT_ID = "object_convert_id"
	const val FIND = "object_find"
	val supported = setOf(PASTE, MESH_DUPLICATE, RENAME, CONVERT_ID)

	/** Object kinds find and replace covers, as targets name them. */
	val FIND_KINDS = listOf("mesh", "warp", "rotation", "part", "parameter", "param_group")

	fun apply(operation: WorkspaceDocumentOperation, document: WorkspaceDocument, model: RigPreviewModel): WorkspaceDocument {
		val request = JsonObject(operation.request - setOf("state", "dry_run"))
		return when (operation.operation) {
			PASTE -> WorkspaceDocumentEdits.journal(document, model, JsonArray(listOf(JsonObject(request + ("op" to JsonPrimitive(RigObjectPaste.OP))))))
			MESH_DUPLICATE -> WorkspaceDocumentEdits.journal(document, model, JsonArray(listOf(JsonObject(request + ("op" to JsonPrimitive(RigMeshDuplication.OP))))))
			RENAME -> rename(document, model, request)
			CONVERT_ID -> convert(document, model, request.getValue("renames").jsonArray.map { element ->
				val o = element.jsonObject
				val target = o.getValue("target").jsonPrimitive.content
				RigIdConversion.Rename(target.substringBefore(':', ""), target.substringAfter(':', ""), o.getValue("id").jsonPrimitive.content)
			})
			else -> throw IllegalArgumentException("Not an object edit: ${operation.operation}")
		}
	}

	// --- Find and replace ---------------------------------------------------------------------------------------------

	/** One object a find matches: its `kind:id`, the [field] searched and what replace makes of it. */
	data class Match(val kind: String, val id: String, val name: String, val field: String, val before: String, val after: String) {
		val target: String get() = "$kind:$id"
	}

	/** Left and right in the spellings model IDs and names use, longest first so `Left` is not read as `L` + `eft`. */
	private val SIDES = listOf("Left" to "Right", "left" to "right", "LEFT" to "RIGHT", "左" to "右", "L" to "R", "l" to "r")

	/** [text] with each left spelled as the matching right and back, each only where it is a word of its own ([token]). */
	fun swapSides(text: String): String {
		val words = SIDES.flatMap { (l, r) -> listOf(l to r, r to l) }
		val out = StringBuilder()
		var i = 0
		while (i < text.length) {
			val hit = words.firstOrNull { (from, _) -> text.startsWith(from, i) && token(text, i, from.length) }
			if (hit == null) { out.append(text[i]); i++ } else { out.append(hit.second); i += hit.first.length }
		}
		return out.toString()
	}

	/**
	 * Whether the [length] letters at [at] are a word of their own: bounded by the ends, a non-letter or a change to an
	 * upper-case letter (`ArmL`, `Hair_L_01`, `EyeLOpen`, `LeftArm`, not `Lip` or `Cleft`). A CJK side is a word anywhere.
	 */
	private fun token(text: String, at: Int, length: Int): Boolean {
		val first = text[at]; val last = text[at + length - 1]
		if (first.code > 0x7F) return true
		val before = text.getOrNull(at - 1); val after = text.getOrNull(at + length)
		val starts = before == null || !before.isLetter() || (first.isUpperCase() && before.isLowerCase())
		val ends = after == null || !after.isLetter() || (after.isUpperCase() && (last.isLowerCase() || length == 1))
		return starts && ends
	}

	/**
	 * The objects of [model] the find [request] matches and what the replace makes of each: `field` (`name`, default,
	 * or `id`), `kinds` (default all of [FIND_KINDS]), `targets` to search only those `kind:id`s, `find` (text, or a
	 * regular expression with `regex`), `match_case` (default true), `replace`, `prefix`, `suffix` and `swap_sides`
	 * (left and right exchanged: L/R, Left/Right, 左/右). With nothing to replace, it lists what `find` matches.
	 */
	fun find(model: PuppetModel, request: JsonObject): List<Match> {
		val allowed = setOf("field", "kinds", "targets", "find", "regex", "match_case", "replace", "prefix", "suffix", "swap_sides", "standard_ids", "offset", "limit")
		require(request.keys.all { it in allowed }) { "Unknown find field: ${(request.keys - allowed).sorted().joinToString()}" }
		val field = request["field"]?.jsonPrimitive?.content ?: "name"
		require(field == "name" || field == "id") { "field must be name or id" }
		val kinds = request["kinds"]?.jsonArray?.map { it.jsonPrimitive.content } ?: FIND_KINDS
		require(kinds.all { it in FIND_KINDS }) { "kinds must be among ${FIND_KINDS.joinToString()}" }
		val targets = request["targets"]?.jsonArray?.mapTo(HashSet()) { it.jsonPrimitive.content }
		val find = request["find"]?.jsonPrimitive?.content.orEmpty()
		val regex = request["regex"]?.jsonPrimitive?.boolean ?: false
		val matchCase = request["match_case"]?.jsonPrimitive?.boolean ?: true
		val replacement = request["replace"]?.jsonPrimitive?.contentOrNull
		val prefix = request["prefix"]?.jsonPrimitive?.content.orEmpty()
		val suffix = request["suffix"]?.jsonPrimitive?.content.orEmpty()
		val swap = request["swap_sides"]?.jsonPrimitive?.boolean ?: false
		val standard = request["standard_ids"]?.jsonPrimitive?.boolean ?: false
		require(!standard || field == "id") { "standard_ids converts IDs: use field id" }
		val options = if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)
		val pattern = if (find.isEmpty()) null else if (regex) runCatching { Regex(find, options) }.getOrElse {
			throw IllegalArgumentException("Invalid regular expression: ${it.message}")
		} else Regex(Regex.escape(find), options)
		val replacing = replacement != null || prefix.isNotEmpty() || suffix.isNotEmpty() || swap || standard
		val out = ArrayList<Match>()
		for ((kind, id, name) in objects(model)) {
			if (kind !in kinds || (targets != null && "$kind:$id" !in targets)) continue
			val text = if (field == "id") id else name
			if (pattern != null && !pattern.containsMatchIn(text)) continue
			var next = text
			if (standard) next = standardId(kind, next) ?: continue
			if (swap) next = swapSides(next)
			if (pattern != null && replacement != null) next = pattern.replace(next, if (regex) replacement else Regex.escapeReplacement(replacement))
			next = prefix + next + suffix
			if (replacing && next == text) continue
			out += Match(kind, id, name, field, text, next)
		}
		return out
	}

	/**
	 * [id] in Cubism 3's style when it is in Cubism 2's: `PARAM_EYE_L_OPEN` becomes `ParamEyeLOpen` and `PARTS_01_FACE`
	 * `PartFace`, the standard IDs Cubism 3 introduced; null for any other ID.
	 */
	fun standardId(kind: String, id: String): String? {
		fun camel(words: List<String>) = words.filter { it.isNotEmpty() }.joinToString("") { word -> word.lowercase().replaceFirstChar(Char::uppercaseChar) }
		return when (kind) {
			"parameter" -> Regex("PARAM_([A-Z0-9_]+)").matchEntire(id)?.let { "Param" + camel(it.groupValues[1].split('_')) }
			"part" -> Regex("PARTS_[0-9]+_([A-Z0-9_]+)").matchEntire(id)?.let { "Part" + camel(it.groupValues[1].split('_')) }
			else -> null
		}
	}

	/** Every object find and replace covers, as `(kind, id, name)`, in panel order. */
	fun objects(model: PuppetModel): List<Triple<String, String, String>> = buildList {
		for (d in model.drawables) add(Triple("mesh", d.id.raw, d.name))
		for (d in model.deformers) add(Triple(if (d is Deformer.Warp) "warp" else "rotation", d.id.raw, d.name))
		for (p in model.parts) add(Triple("part", p.id.raw, p.name))
		for (p in model.parameters) add(Triple("parameter", p.id.raw, p.name))
		fun walk(nodes: List<ParameterNode>) { for (n in nodes) if (n is ParameterNode.Group) { add(Triple("param_group", n.id.raw, n.name)); walk(n.children) } }
		walk(model.parameterTree)
	}

	/** The find [request] applied: names as one structure entry, IDs as one ID conversion. */
	fun rename(document: WorkspaceDocument, model: RigPreviewModel, request: JsonObject): WorkspaceDocument {
		val matches = find(model.rig.puppet, JsonObject(request - setOf("offset", "limit")))
		require(matches.isNotEmpty()) { "Nothing matches" }
		require(matches.size <= 4096) { "Replace at most 4096 objects at once" }
		if (matches.first().field == "id") return convert(document, model, matches.map { RigIdConversion.Rename(it.kind, it.id, it.after) })
		matches.firstOrNull { it.after.isBlank() || it.after.any(Char::isISOControl) }?.let { throw IllegalArgumentException("${it.target} would have no name") }
		val edits = matches.map { m -> buildJsonObject {
			put("action", "rename"); put("kind", m.kind); put("id", m.id); put("name", m.after.trim())
		} }
		return WorkspaceDocumentEdits.journal(document, model, buildJsonArray { add(buildJsonObject { put("op", "structure"); put("edits", JsonArray(edits)) }) })
	}

	// --- ID conversion ----------------------------------------------------------------------------------------------

	/** Why [rename] cannot convert in [document]: what outside the rig holds the ID in a way only its owner can change. */
	private fun refusal(document: WorkspaceDocument, model: RigPreviewModel, rename: RigIdConversion.Rename): String? {
		val shown = model.rig.puppet
		val authored = model.authored.rig.puppet
		val id = rename.from
		if (RigIdConversion.ids(shown, rename.namespace).contains(id) && !RigIdConversion.ids(authored, rename.namespace).contains(id))
			return "${rename.kind} $id belongs to a swing, simulation or interior effect; its ID follows that generator"
		val overlay = document.rigEdits
		when (rename.kind) {
			"mesh" -> {
				overlay.simEdits.firstOrNull { sim -> id in sim.targets || sim.colliders.any { it.mesh == id } }
					?.let { return "Mesh $id is simulated by ${it.id}; remove the simulation before converting its ID" }
				overlay.interiorEffects.firstOrNull { it.target == id }
					?.let { return "Mesh $id has the interior effect ${it.id}; remove it before converting its ID" }
			}
			"parameter" -> {
				overlay.simEdits.firstOrNull { sim -> sim.inputs.any { it.parameter == id } || id in sim.inputRanges || id in sim.staticInputs.orEmpty() ||
					id in sim.forcedInputs || id in sim.outputs || id in sim.outputNames }
					?.let { return "Parameter $id drives the simulation ${it.id}; remove it from the simulation before converting its ID" }
				overlay.interiorEffects.firstOrNull { effect -> effect.sources.any { it.id.raw == id } || id in effect.parameterIds }
					?.let { return "Parameter $id drives the interior effect ${it.id}; remove it before converting its ID" }
				overlay.swingEdits.firstOrNull { id in it.parameterIds }?.let { return "Parameter $id belongs to the swing ${it.id}" }
			}
		}
		return null
	}

	/**
	 * [document] with [renames] converted: one `convert_id` entry for the rig, and the document's own references -
	 * motion curves, the user's physics groups, swing targets - renamed with it in the same edit.
	 */
	fun convert(document: WorkspaceDocument, model: RigPreviewModel, renames: List<RigIdConversion.Rename>): WorkspaceDocument {
		RigIdConversion.problem(model.rig.puppet, renames)?.let { throw IllegalArgumentException(it) }
		RigIdConversion.problem(model.authored.rig.puppet, renames)?.let { throw IllegalArgumentException(it) }
		for (rename in renames) refusal(document, model, rename)?.let { throw IllegalArgumentException(it) }
		val journaled = WorkspaceDocumentEdits.journal(document, model, JsonArray(listOf(RigIdConversion.encode(renames))))
		val parameters = renames.filter { it.kind == "parameter" }.associate { it.from to it.to }
		val deformers = renames.filter { it.namespace == "deformer" }.associate { it.from to it.to }
		val overlay = journaled.rigEdits
		return journaled.copy(rigEdits = overlay.copy(
			motionClips = if (parameters.isEmpty()) overlay.motionClips else overlay.motionClips.map { clip ->
				clip.copy(curves = clip.curves.map { curve -> parameters[curve.parameterId]?.let { curve.copy(parameterId = it) } ?: curve })
			},
			physicsEdits = overlay.physicsEdits.map { RigIdConversion.forward(it, parameters) },
			swingEdits = if (deformers.isEmpty()) overlay.swingEdits else overlay.swingEdits.map { swing ->
				if (swing.targets.none { it in deformers }) swing else swing.copy(targets = swing.targets.map { deformers[it] ?: it })
			},
		))
	}

	// --- Results ----------------------------------------------------------------------------------------------------

	fun findResult(matches: List<Match>, offset: Int, limit: Int): JsonObject = buildJsonObject {
		put("total", matches.size)
		putJsonArray("matches") {
			for (m in matches.drop(offset).take(limit)) add(buildJsonObject {
				put("target", m.target); put("kind", m.kind); put("id", m.id); put("name", m.name)
				put("field", m.field); put("before", m.before); put("after", m.after)
			})
		}
	}
}
