package io.github.psd2live.agent

import io.github.psd2live.application.WorkspaceOperationDefinition
import kotlinx.serialization.json.*

/**
 * What a reader needs to call one operation, as the help page shows it: how each tool profile reaches it and the
 * request fields with their shapes, read from the same definition the server publishes.
 */
data class AgentOperationDoc(
	val id: String,
	val description: String,
	/** query, document, session, project or output. */
	val kind: String,
	val jobBacked: Boolean,
	/** The compact profile's tool: the operation itself, its family, or workspace_call. */
	val coreTool: String,
	/** The `op` a family tool takes for it, or the `operation` workspace_call takes; null when [coreTool] is the operation. */
	val coreOperation: String?,
	val fields: List<AgentOperationField>,
)

/** A request field: its [type] in short form (`string`, `integer 0…64`, `"a" | "b"`, `array<object>` …). */
data class AgentOperationField(val name: String, val type: String, val required: Boolean, val description: String)

/** The request fields every operation shares are left out: the compact profile fills them in. */
private val SharedFields = setOf("request_id", "project_id")

internal fun agentOperationDocs(definitions: List<WorkspaceOperationDefinition>): List<AgentOperationDoc> = definitions.map { definition ->
	val family = AGENT_TOOL_FAMILIES.firstOrNull { family -> family.members(listOf(definition)).isNotEmpty() }
	val (tool, op) = when {
		definition.id in CORE_OPERATIONS -> definition.id to null
		family != null -> family.name to family.op(definition.id)
		else -> CALL_TOOL to definition.id
	}
	AgentOperationDoc(
		id = definition.id,
		description = definition.description,
		kind = definition.kind.name.lowercase(),
		jobBacked = definition.jobBacked,
		coreTool = tool,
		coreOperation = op,
		fields = requestFields(definition.requestSchema),
	)
}

/** The request's fields, a oneOf request's branches merged; a field is required only where the whole request requires it. */
private fun requestFields(schema: JsonObject): List<AgentOperationField> {
	val required = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty().toSet()
	val fields = linkedMapOf<String, JsonObject>()
	val branches = schema["oneOf"]?.jsonArray?.map { it.jsonObject }.orEmpty()
	(listOf(schema) + branches).forEach { part ->
		part["properties"]?.jsonObject?.forEach { (name, field) ->
			val shape = field as? JsonObject ?: return@forEach
			if (name !in fields || fields[name] == JsonObject(emptyMap())) fields[name] = shape
		}
	}
	return fields.filterKeys { it !in SharedFields }.map { (name, field) ->
		AgentOperationField(name, shortType(field, schema), name in required, field["description"]?.jsonPrimitive?.contentOrNull.orEmpty())
	}
}

private fun shortType(field: JsonObject, root: JsonObject, depth: Int = 0): String {
	field["const"]?.let { return it.toString() }
	field["enum"]?.jsonArray?.let { values ->
		val shown = values.take(6).joinToString(" | ") { it.toString() }
		return if (values.size > 6) "$shown | …" else shown
	}
	field["\$ref"]?.jsonPrimitive?.contentOrNull?.let { return it.substringAfterLast('/') }
	(field["oneOf"] ?: field["anyOf"])?.jsonArray?.let { branches ->
		if (depth > 1) return "…"
		return branches.joinToString(" | ") { shortType(it.jsonObject, root, depth + 1) }.ifEmpty { "any" }
	}
	val type = when (val raw = field["type"]) {
		is JsonArray -> raw.joinToString("|") { it.jsonPrimitive.content }
		is JsonPrimitive -> raw.content
		else -> if (field.isEmpty()) "any" else "object"
	}
	return when (type) {
		"array" -> "array<" + ((field["items"] as? JsonObject)?.let { if (depth > 1) "…" else shortType(it, root, depth + 1) } ?: "any") + ">"
		"integer", "number" -> {
			val min = field["minimum"]?.jsonPrimitive?.contentOrNull
			val max = field["maximum"]?.jsonPrimitive?.contentOrNull
			if (min != null || max != null) "$type ${min ?: ""}…${max ?: ""}" else type
		}
		else -> type
	}
}
