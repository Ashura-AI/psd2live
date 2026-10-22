package io.github.psd2live.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LayerTagsTest {
	@Test fun everyClassificationRoundTripsThroughItsTags() {
		for (tag in SemanticTag.entries) for (side in Side.entries) {
			val resolution = LayerTags.resolve(LayerTags.of(tag, side))
			assertEquals(tag to side, resolution.tag to resolution.side, "tags of $tag $side")
			assertTrue(!resolution.ambiguous, "tags of $tag $side are ambiguous: ${resolution.candidates}")
		}
	}

	@Test fun theMostSpecificPartWinsAndUserWordsStayOut() {
		assertEquals(SemanticTag.FACE_DETAIL, LayerTags.resolve(setOf("face", "detail")).tag)
		assertEquals(SemanticTag.NECKWEAR, LayerTags.resolve(setOf("Neck", "accessory", "外套")).tag)
		val resolution = LayerTags.resolve(setOf("eye", "iris", "left", "外套"))
		assertEquals(SemanticTag.IRIDES to Side.LEFT, resolution.tag to resolution.side)
		assertEquals(setOf("外套"), resolution.user)
		assertEquals(SemanticTag.UNKNOWN, LayerTags.resolve(setOf("eye")).tag)
		assertEquals(Side.NONE, LayerTags.resolve(setOf("left", "right")).side)
		assertTrue(LayerTags.resolve(setOf("eye", "iris", "white")).ambiguous)
	}

	@Test fun namesTypedAsTagsBecomeTheirWords() {
		assertEquals(setOf("hair", "front"), LayerTags.fromName("前发"))
		assertEquals(setOf("eye", "iris", "left"), LayerTags.fromName("eye-l"))
		assertEquals("hair", LayerTags.normalize(" HAIR "))
		assertEquals("Coat  lining".replace("  ", " "), LayerTags.normalize("Coat   lining"))
	}

}
