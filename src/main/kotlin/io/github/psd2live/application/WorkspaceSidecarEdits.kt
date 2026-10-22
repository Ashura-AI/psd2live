package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.WorkspaceDocument
import kotlinx.serialization.json.*

/** A captured document's expressions and pose, as [ModelExpressions] and [ModelPoses] resolve them on its rig. */
data class WorkspaceSidecars(
    val expressions: List<ModelExpression>,
    /** Whether [expressions] are the standard ones, not taken into the document yet. */
    val standard: Boolean,
    val pose: ModelPose?,
    val parameterIds: Set<String>,
    val partIds: Set<String>,
)

/**
 * Pure candidate edits of the model's expressions (`expression_*`) and pose (`pose_*`), shared by the panel, the public
 * operations and atomic batches. They change only the document's sidecar data, never the rig.
 */
internal object WorkspaceSidecarEdits {
    val expressionOps = listOf("create", "put", "update", "set_entries", "capture", "delete", "duplicate", "move", "reset")
    val poseOps = listOf("put", "add_group", "set_group", "delete_group", "update", "clear")
    val supported: Set<String> = (expressionOps.map { "expression_$it" } + poseOps.map { "pose_$it" }).toSet()

    fun sidecars(document: WorkspaceDocument, puppet: org.umamo.runtime.model.PuppetModel?): WorkspaceSidecars {
        val parameterIds = puppet?.parameters?.mapTo(LinkedHashSet()) { it.id.raw }.orEmpty()
        return WorkspaceSidecars(ModelExpressions.effective(document.rigEdits, parameterIds), document.rigEdits.expressions == null,
            document.rigEdits.pose, parameterIds, puppet?.parts?.mapTo(LinkedHashSet()) { it.id.raw }.orEmpty())
    }

    /**
     * The handles [after] changed from [before]: each expression added, removed or changed, and `pose`. Expressions
     * only the rig generates are not document data, so they count once either side holds the document's list.
     */
    fun changed(before: WorkspaceDocument, beforePuppet: org.umamo.runtime.model.PuppetModel?,
                after: WorkspaceDocument, afterPuppet: org.umamo.runtime.model.PuppetModel?): List<String> {
        val expressions = if (before.rigEdits.expressions == null && after.rigEdits.expressions == null) emptyList() else {
            val old = sidecars(before, beforePuppet).expressions
            val new = sidecars(after, afterPuppet).expressions
            (old.filter { it !in new } + new.filter { it !in old }).map { it.id }.distinct()
        }
        return expressions + listOf("pose").filter { before.rigEdits.pose != after.rigEdits.pose }
    }

    fun apply(operation: WorkspaceDocumentOperation, document: WorkspaceDocument, model: RigPreviewModel): WorkspaceDocument {
        val puppet = model.rig.puppet
        val request = operation.request
        return when {
            operation.operation.startsWith("expression_") -> {
                val parameterIds = puppet.parameters.mapTo(HashSet()) { it.id.raw }
                val mode = operation.operation.removePrefix("expression_")
                if (mode == "reset") return document.copy(rigEdits = document.rigEdits.copy(expressions = null))
                val current = ModelExpressions.effective(document.rigEdits, parameterIds)
                val next = ModelExpressions.validated(expression(mode, current, request, puppet.parameters))
                // Only what this edit writes must name a parameter the rig has: an expression keeps the entries an
                // earlier deletion left it, which export skips and expression_get lists as missing, and a duplicate
                // those of its source; no other expression takes them on.
                val before = current.associate { e -> e.id to e.entries.mapTo(HashSet()) { it.parameterId } }
                val source = if (mode == "duplicate") request.text("id") else null
                for (expression in next) {
                    val kept = before[expression.id] ?: source?.let(before::get).orEmpty()
                    expression.entries.firstOrNull { it.parameterId !in parameterIds && it.parameterId !in kept }?.let {
                        throw IllegalArgumentException("Unknown expression parameter: ${it.parameterId}")
                    }
                }
                // Unchanged standard expressions stay generated, so the rig's own set keeps following it.
                if (document.rigEdits.expressions == null && next == current) document
                else document.copy(rigEdits = document.rigEdits.copy(expressions = next))
            }
            operation.operation.startsWith("pose_") -> {
                val partIds = puppet.parts.mapTo(HashSet()) { it.id.raw }
                val mode = operation.operation.removePrefix("pose_")
                val old = document.rigEdits.pose?.groups.orEmpty()
                val next = pose(mode, document.rigEdits.pose, request)?.takeIf { it.groups.isNotEmpty() }
                // As with expressions, only parts this edit writes must exist: a group left as it was keeps its missing
                // parts, which pose_get reports, and a replaced group those it had; no other group takes them on.
                val replaced = if (mode == "set_group") old.getOrNull(request.getValue("index").jsonPrimitive.int) else null
                for (group in next?.groups.orEmpty()) {
                    if (group in old) continue
                    val kept = replaced?.parts.orEmpty().flatMapTo(HashSet()) { it.ids }
                    group.parts.flatMap { it.ids }.firstOrNull { it !in partIds && it !in kept }?.let { throw IllegalArgumentException("Part not found: $it") }
                }
                document.copy(rigEdits = document.rigEdits.copy(pose = next))
            }
            else -> throw IllegalArgumentException("Not a sidecar operation: ${operation.operation}")
        }
    }

    private fun expression(mode: String, current: List<ModelExpression>, request: JsonObject,
                           parameters: List<org.umamo.runtime.model.Parameter>): List<ModelExpression> {
        fun find(id: String) = requireNotNull(current.firstOrNull { it.id == id }) { "Expression not found: $id" }
        fun replaced(expression: ModelExpression) = current.map { if (it.id == expression.id) expression else it }
        fun pose(key: String) = request.getValue(key).jsonObject.mapValues { it.value.jsonPrimitive.float }
        fun fades(expression: ModelExpression) = expression.copy(
            fadeIn = request["fade_in"]?.jsonPrimitive?.float ?: expression.fadeIn,
            fadeOut = request["fade_out"]?.jsonPrimitive?.float ?: expression.fadeOut)
        return when (mode) {
            "create" -> {
                val id = request["id"]?.jsonPrimitive?.content ?: ModelExpressions.newId(current)
                require(current.none { it.id.equals(id, ignoreCase = true) }) { "Expression ID already exists: $id" }
                require(!("values" in request && "entries" in request)) { "Give values or entries, not both" }
                val entries = when {
                    "values" in request -> ModelExpressions.entriesOfPose(pose("values"), parameters)
                    "entries" in request -> entries(request.getValue("entries").jsonArray, parameters)
                    else -> emptyList()
                }
                val name = request["name"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() } ?: "Expression"
                val created = fades(ModelExpression(id, ModelExpressions.uniqueName(current, name), entries = entries))
                insert(current, created, request["index"]?.jsonPrimitive?.int)
            }
            "put" -> {
                val expression = ModelExpressions.fromJson(request.getValue("expression").jsonObject)
                if (current.any { it.id == expression.id }) replaced(expression) else current + expression
            }
            "update" -> {
                val source = find(request.text("id"))
                require(listOf("name", "fade_in", "fade_out").any { it in request }) { "Provide an expression property" }
                val name = request["name"]?.jsonPrimitive?.content?.trim()
                require(name == null || name.isNotEmpty()) { "Expression name must not be blank" }
                replaced(fades(source.copy(name = name?.let { ModelExpressions.uniqueName(current.filterNot { it.id == source.id }, it) } ?: source.name)))
            }
            "set_entries" -> {
                val source = find(request.text("id"))
                val defaults = parameters.associate { it.id.raw to it.default }
                var entries = source.entries
                for (element in request.getValue("entries").jsonArray) {
                    val change = element.jsonObject
                    val parameter = change.text("parameter")
                    val existing = entries.firstOrNull { it.parameterId == parameter }
                    if (change["remove"]?.jsonPrimitive?.boolean == true) {
                        require(existing != null) { "Expression ${source.id} does not set $parameter" }
                        entries = entries.filterNot { it.parameterId == parameter }
                        continue
                    }
                    val default = requireNotNull(defaults[parameter]) { "Unknown expression parameter: $parameter" }
                    val blend = change["blend"]?.jsonPrimitive?.content?.let { ExpressionBlend.valueOf(it.uppercase()) }
                    val base = existing ?: ExpressionEntry(parameter, ExpressionBlend.ADD, 0f)
                    // A changed blend keeps the face the entry made at the default; a given value is taken as it is.
                    val reblended = if (blend == null) base else ModelExpressions.withBlend(base, blend, default)
                    val entry = change["value"]?.jsonPrimitive?.float?.let { reblended.copy(value = it) } ?: reblended
                    entries = if (existing == null) entries + entry else entries.map { if (it.parameterId == parameter) entry else it }
                }
                replaced(source.copy(entries = entries))
            }
            "capture" -> replaced(find(request.text("id")).copy(entries = ModelExpressions.entriesOfPose(pose("values"), parameters)))
            "delete" -> current.filterNot { it.id == find(request.text("id")).id }
            "duplicate" -> {
                val source = find(request.text("id"))
                val id = request["new_id"]?.jsonPrimitive?.content ?: ModelExpressions.newId(current)
                require(current.none { it.id.equals(id, ignoreCase = true) }) { "Expression ID already exists: $id" }
                insert(current, source.copy(id = id, name = ModelExpressions.uniqueName(current, source.name)), current.indexOf(source) + 1)
            }
            "move" -> {
                val source = find(request.text("id"))
                insert(current - source, source, request.getValue("index").jsonPrimitive.int)
            }
            else -> throw IllegalArgumentException("Unknown expression edit: $mode")
        }
    }

    private fun insert(list: List<ModelExpression>, expression: ModelExpression, index: Int?): List<ModelExpression> {
        val at = (index ?: list.size).coerceIn(0, list.size)
        return list.subList(0, at) + expression + list.subList(at, list.size)
    }

    private fun entries(array: JsonArray, parameters: List<org.umamo.runtime.model.Parameter>): List<ExpressionEntry> {
        val known = parameters.associateBy { it.id.raw }
        return array.map { element ->
            val entry = element.jsonObject
            val parameter = entry.text("parameter")
            require(parameter in known) { "Unknown expression parameter: $parameter" }
            ExpressionEntry(parameter, ExpressionBlend.valueOf(entry.text("blend").uppercase()), entry.getValue("value").jsonPrimitive.float)
        }
    }

    private fun pose(mode: String, current: ModelPose?, request: JsonObject): ModelPose? {
        fun parts(array: JsonArray) = array.map { element ->
            when (element) {
                is JsonPrimitive -> PosePart(element.content)
                else -> PosePart(element.jsonObject.text("part"), element.jsonObject["links"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content })
            }
        }
        fun index(pose: ModelPose): Int = request.getValue("index").jsonPrimitive.int.also {
            require(it in pose.groups.indices) { "Pose group not found: $it" }
        }
        val pose = current ?: ModelPose()
        return when (mode) {
            "put" -> ModelPoses.fromJson(request.getValue("pose").jsonObject)
            "add_group" -> {
                val at = (request["index"]?.jsonPrimitive?.int ?: pose.groups.size).coerceIn(0, pose.groups.size)
                pose.copy(groups = pose.groups.subList(0, at) + PoseGroup(parts(request.getValue("parts").jsonArray)) + pose.groups.subList(at, pose.groups.size))
            }
            "set_group" -> {
                val at = index(pose)
                pose.copy(groups = pose.groups.mapIndexed { i, group -> if (i == at) PoseGroup(parts(request.getValue("parts").jsonArray)) else group })
            }
            "delete_group" -> {
                val at = index(pose)
                pose.copy(groups = pose.groups.filterIndexed { i, _ -> i != at })
            }
            "update" -> pose.copy(fadeIn = request.getValue("fade_in").jsonPrimitive.float)
            "clear" -> null
            else -> throw IllegalArgumentException("Unknown pose edit: $mode")
        }
    }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
}
