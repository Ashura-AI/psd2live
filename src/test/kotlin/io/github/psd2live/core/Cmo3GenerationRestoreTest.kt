package io.github.psd2live.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** A new source started while an imported model is open generates its rig: the import's switched-off generation stays with the import. */
class Cmo3GenerationRestoreTest {
	@Test fun anImportedModelsSettingsGenerateAgain() {
		val imported = PipelineConfig(rigEdits = RigEditOverlay(importedCmo3 = "model.cmo3"), generateDeformers = false,
			mouthOutlineEnabled = false, physicsFrontHair = false, motionIdle = false, meshSpacing = 52)
		val restored = Cmo3ModelImport.generationRestored(imported)
		val defaults = PipelineConfig()
		assertEquals(defaults.generateDeformers, restored.generateDeformers)
		assertEquals(defaults.mouthOutlineEnabled, restored.mouthOutlineEnabled)
		assertEquals(defaults.physicsFrontHair, restored.physicsFrontHair)
		assertEquals(defaults.motionIdle, restored.motionIdle)
		assertEquals(52, restored.meshSpacing)
	}

}
