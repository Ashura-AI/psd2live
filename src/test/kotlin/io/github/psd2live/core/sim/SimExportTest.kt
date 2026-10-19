package io.github.psd2live.core.sim

import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SimExportTest {
	private val model = PuppetModel(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null)
	private val edit = RigSimEdit("cloth", "Cloth", SimKind.CLOTH, listOf("missing"),
		bake = SimBakeResult("test", mapOf("missing" to 3), emptyList(), emptyList()))

	@Test fun anEnabledBakeThatCannotBuildItsSceneReportsTheSimulationInsteadOfDroppingIt() {
		val failure = assertFailsWith<IllegalStateException> {
			SimExport.export(model, listOf(edit), emptySet(), emptySet())
		}
		assertTrue(failure.message.orEmpty().contains("cloth"))
		assertTrue(failure.cause is IllegalArgumentException)
	}

	@Test fun disabledAndUnbakedSimulationsDoNotRequireAScene() {
		val result = SimExport.export(model, listOf(edit.copy(enabled = false), edit.copy(bake = null)), emptySet(), emptySet())
		assertTrue(result.simulations.isEmpty())
	}
}
