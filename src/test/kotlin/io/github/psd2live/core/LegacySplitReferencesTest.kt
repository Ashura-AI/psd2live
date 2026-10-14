package io.github.psd2live.core

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals

/** Reading the journal's quoted component IDs once finds exactly what searching each command's text for them found. */
class LegacySplitReferencesTest {
	@Test fun quotedIdsMatchASearchOfEachCommandsText() {
		val journal = listOf(
			buildJsonObject { put("op", "canvas_geometry"); put("id", "hair:r") },
			buildJsonObject { putJsonObject("meshes") { put("face:l", 1) } },
			buildJsonObject { putJsonArray("ids") { add("x"); add("arm:l"); add("tail:rr") } },
			buildJsonObject { put("text", "say \"ear:r\" here"); put("name", "leg:r and more") },
			buildJsonObject { put("op", "other"); put("id", "eye:r:l") },
		)
		val config = PipelineConfig(rigEdits = RigEditOverlay.Empty.copy(authoringJournal = journal))
		val references = LegacySplitReferences(config)
		for (source in listOf("hair", "face", "arm", "tail", "ear", "leg", "eye", "eye:r", "x", "", "nose")) {
			val ids = setOf("$source:r", "$source:l")
			val searched = journal.any { command -> ids.any { id -> command.toString().contains("\"$id\"") } }
			assertEquals(searched, references.preserves(source), "source $source")
		}
	}
}
