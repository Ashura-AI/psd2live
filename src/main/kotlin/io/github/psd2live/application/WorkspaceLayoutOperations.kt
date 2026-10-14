package io.github.psd2live.application

import kotlinx.serialization.json.*

/**
 * Layout operations: semantic tags, variants, materials, placement and generation. Each document operation runs as a
 * one-member document batch ([startDocumentBatch]), so the GUI and MCP commit it exactly as `workspace_apply_edits`
 * does, and it is a batch member itself.
 */
internal object WorkspaceLayoutOperations {
    /** Operations whose job result is the batch result. */
    val batches: Set<String> get() = WorkspaceLayoutEdits.supported
}

internal fun registerLayoutOperations(registry: WorkspaceOperationRegistry, port: WorkspaceDocumentPort,
                                      reads: WorkspaceStatePort, jobs: WorkspaceJobs) {
    for (id in WorkspaceLayoutEdits.supported) {
        registry.register(WorkspaceOperationDefinition(id,
            WorkspaceLayoutEdits.descriptions.getValue(id) + " Returns a process-owned job handle; use job_wait/job_get for the result.",
            WorkspaceLayoutEdits.schemas.getValue(id), WorkspaceOperationKind.DOCUMENT, jobBacked = true,
            resultSchema = requireNotNull(WorkspaceJobResultSchemas.operationOutput(id)),
            jobResultSchema = WorkspaceJobResultSchemas.result(id))) { request, context ->
            val state = request.getValue("state").jsonPrimitive.content
            val summary = when (id) {
                WorkspaceLayoutEdits.TAG -> "Tagged ${request.getValue("layer_ids").jsonArray.size} layer(s)"
                WorkspaceGenerationEdits.GENERATE -> "Generated the rig"
                else -> "Set variant of ${request.getValue("layer_id").jsonPrimitive.content}"
            }
            startDocumentBatch(port, reads, jobs, id, state, summary,
                listOf(WorkspaceDocumentOperation(id, JsonObject(request - setOf("state", "request_id", "project_id")))), context.author)
        }
    }
}
