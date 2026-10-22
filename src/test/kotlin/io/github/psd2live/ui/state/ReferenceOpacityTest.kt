package io.github.psd2live.ui.state

import io.github.psd2live.application.WorkspaceDocumentOperation
import io.github.psd2live.application.WorkspaceMaterialEdits
import io.github.psd2live.project.MutationAuthor
import io.github.psd2live.scenario.Characters
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class ReferenceOpacityTest {
	@TempDir lateinit var temp: Path

	@Test fun aScrubPreviewsWithoutHistoryAndCommitsOnlyItsFinalValue() = runBlocking {
		PSD2LiveViewModel().use { vm ->
			DesktopWorkspace(vm, temp.resolve("workspace")).use { workspace ->
				vm.attachWorkspace(workspace)
				workspace.importPsd(Characters.figurePsd(temp).toAbsolutePath().toString(), true)
				val material = vm.state.value.layout.materials.items.first().id
				workspace.applyDocumentEdits(workspace.snapshot().state, "Reference", listOf(
					WorkspaceDocumentOperation(WorkspaceMaterialEdits.ADD_REFERENCE, buildJsonObject { put("material_id", material) })), MutationAuthor.USER)
				val before = assertNotNull(vm.state.value.historySnapshot)
				val reference = vm.state.value.layout.references.single()
				for (value in listOf(0f, 0.12f, 0.37f, 0.63f)) {
					vm.previewReferenceOpacity(reference.id, value)
					assertEquals(ReferenceOpacityDraft(ReferenceTarget.Image(reference.id), value), vm.referenceOpacityDraft.value)
					assertEquals(reference.opacity, vm.state.value.layout.references.single().opacity)
					assertEquals(before.nodes.size, vm.state.value.historySnapshot?.nodes?.size)
				}
				vm.commitReferenceOpacity(reference.id, 0.63f)
				withTimeout(10_000) { while (vm.referenceOpacityDraft.value != null || vm.state.value.workspaceEditBusy) delay(10) }
				assertNull(vm.state.value.errorMessage)
				assertEquals(0.63f, vm.state.value.layout.references.single().opacity)
				val after = assertNotNull(vm.state.value.historySnapshot)
				assertEquals(before.nodes.size + 1, after.nodes.size)
				workspace.checkoutHistory(before.headNodeId, MutationAuthor.USER)
				assertEquals(reference.opacity, vm.state.value.layout.references.single().opacity)
				workspace.checkoutHistory(after.headNodeId, MutationAuthor.USER)
				val file = temp.resolve("reference.psd2live")
				workspace.save(file)
				workspace.open(file)
				assertEquals(0.63f, vm.state.value.layout.references.single().opacity)
				// Failure clears transient feedback too, without publishing a history prefix.
				vm.previewReferenceOpacity("missing", 0.4f)
				vm.commitReferenceOpacity("missing", 0.4f)
				withTimeout(10_000) { while (vm.referenceOpacityDraft.value != null || vm.state.value.workspaceEditBusy) delay(10) }
				assertNotNull(vm.state.value.errorMessage)
				assertEquals(after.nodes.size, vm.state.value.historySnapshot?.nodes?.size)
			}
		}
	}

}
