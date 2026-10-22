package io.github.psd2live.core

import io.github.psd2live.i18n.tr
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelSidecarsTest {
	private fun parameter(id: String, min: Float, max: Float, default: Float) = Parameter(ParameterId(id), id, min, max, default)

	private val eye = parameter("ParamEyeLOpen", 0f, 1f, 1f)
	private val form = parameter("ParamMouthForm", -1f, 1f, 0f)

	@Test fun aPoseBecomesAddEntriesOfWhatLeftItsDefault() {
		val entries = ModelExpressions.entriesOfPose(mapOf("ParamEyeLOpen" to 0.4f, "ParamMouthForm" to 0f, "ParamUnknown" to 3f), listOf(eye, form))
		assertEquals(1, entries.size)
		assertEquals("ParamEyeLOpen", entries.single().parameterId)
		assertEquals(ExpressionBlend.ADD, entries.single().blend)
		assertEquals(-0.6f, entries.single().value, 1e-6f)
	}

	@Test fun reblendingKeepsTheFaceAtTheDefault() {
		val add = ExpressionEntry("ParamEyeLOpen", ExpressionBlend.ADD, -0.25f)
		for (blend in ExpressionBlend.entries) {
			val converted = ModelExpressions.withBlend(add, blend, eye.default)
			assertEquals(0.75f, ModelExpressions.result(converted, eye.default), 1e-6f, "as $blend")
		}
		// Multiplying a parameter at zero cannot keep an offset: it multiplies by one, no change.
		assertEquals(1f, ModelExpressions.withBlend(ExpressionEntry("ParamMouthForm", ExpressionBlend.ADD, 0.5f), ExpressionBlend.MULTIPLY, 0f).value)
	}

	@Test fun anExpressionAppliesOverThePoseWithinRange() {
		val expression = ModelExpression("e", "E", entries = listOf(
			ExpressionEntry("ParamEyeLOpen", ExpressionBlend.MULTIPLY, 0.5f), ExpressionEntry("ParamMouthForm", ExpressionBlend.ADD, 1.5f)))
		assertEquals(mapOf("ParamEyeLOpen" to 0.4f, "ParamMouthForm" to 1f),
			ModelExpressions.applied(expression, mapOf("ParamEyeLOpen" to 0.8f, "ParamMouthForm" to 0.2f), listOf(eye, form)))
	}

	@Test fun theStandardExpressionsFollowTheRigUntilEdited() {
		val overlay = RigEditOverlay()
		val only = ModelExpressions.exported(overlay, setOf("ParamMouthForm"))
		assertEquals(listOf("smile", "sad", "angry"), only.map { it.id })
		assertTrue(only.all { e -> e.entries.all { it.parameterId == "ParamMouthForm" } })
		val authored = overlay.copy(expressions = listOf(ModelExpression("neutral", "Neutral"), ModelExpression("wink", "Wink",
			entries = listOf(ExpressionEntry("ParamEyeLOpen", ExpressionBlend.OVERWRITE, 0f)))))
		assertEquals(listOf("neutral", "wink"), ModelExpressions.exported(authored, setOf("ParamMouthForm")).map { it.id })
		assertTrue(ModelExpressions.exported(authored, setOf("ParamMouthForm")).all { it.entries.isEmpty() })
	}

	@Test fun anImportedModelHasNoStandardExpressions() {
		val face = StandardParameters.MOUTH_FORM.raw
		assertEquals(emptyList(), ModelExpressions.exported(RigEditOverlay(importedCmo3 = "rig"), setOf(face)))
		val authored = RigEditOverlay(importedCmo3 = "rig", expressions = listOf(ModelExpression("joy", "Joy")))
		assertEquals(listOf("joy"), ModelExpressions.exported(authored, setOf(face)).map { it.id })
		// Named in the interface language, as the runtime export always named them.
		assertEquals(tr("model.expression.smile"), ModelExpressions.standardFor(setOf(face)).first().name)
	}

	@Test fun missingParametersAreThoseTheRigLacks() {
		val expression = ModelExpression("e", "E", entries = listOf(ExpressionEntry("ParamEyeLOpen", ExpressionBlend.ADD, 1f),
			ExpressionEntry("ParamGone", ExpressionBlend.ADD, 1f)))
		assertEquals(listOf("ParamGone"), ModelExpressions.missing(expression, setOf("ParamEyeLOpen")))
		assertEquals(listOf("A", "L"), ModelPoses.partIds(ModelPose(groups = listOf(PoseGroup(listOf(PosePart("A", listOf("L"))))))))
	}

	@Test fun jsonRoundTripsAndInvalidDataIsRefused() {
		val expression = ModelExpression("joy", "Joy", 0.2f, 0.8f, listOf(ExpressionEntry("ParamMouthForm", ExpressionBlend.OVERWRITE, 1f)))
		assertEquals(expression, ModelExpressions.fromJson(ModelExpressions.toJson(expression)))
		val pose = ModelPose(0.3f, listOf(PoseGroup(listOf(PosePart("PartArmA", listOf("PartHandA")), PosePart("PartArmB")))))
		assertEquals(pose, ModelPoses.fromJson(ModelPoses.toJson(pose)))
		assertFailsWith<IllegalArgumentException> { ModelExpression("bad id", "Bad") }
		assertFailsWith<IllegalArgumentException> { ModelExpressions.validated(listOf(expression, expression.copy(id = "joy2"))) }
		assertFailsWith<IllegalArgumentException> { ModelPose(groups = listOf(PoseGroup(listOf(PosePart("A"))), PoseGroup(listOf(PosePart("B", listOf("A")))))) }
		assertFailsWith<IllegalArgumentException> { ModelPoses.validated(pose, setOf("PartArmA", "PartArmB")) }
	}

	@Test fun exportDropsMissingPartsAndEmptyGroups() {
		val pose = ModelPose(groups = listOf(PoseGroup(listOf(PosePart("A", listOf("Gone")), PosePart("B"))), PoseGroup(listOf(PosePart("Gone2")))))
		assertEquals(ModelPose(groups = listOf(PoseGroup(listOf(PosePart("A"), PosePart("B"))))), ModelPoses.exported(pose, setOf("A", "B")))
		assertNull(ModelPoses.exported(pose, emptySet()))
	}

	@Test fun renamingAnIdUpdatesEveryReference() {
		val overlay = RigEditOverlay(
			motionClips = listOf(MotionClip("m", "M", curves = listOf(MotionCurve("ParamOld", listOf(MotionKey(0f, 1f)))))),
			expressions = listOf(ModelExpression("e", "E", entries = listOf(ExpressionEntry("ParamOld", ExpressionBlend.ADD, 1f)))),
			pose = ModelPose(groups = listOf(PoseGroup(listOf(PosePart("PartOld", listOf("PartLink")), PosePart("PartB"))))),
		)
		val parameter = ModelSidecars.withIdRenamed(overlay, "parameter", "ParamOld", "ParamNew")
		assertEquals("ParamNew", parameter.motionClips.single().curves.single().parameterId)
		assertEquals("ParamNew", parameter.expressions!!.single().entries.single().parameterId)
		val part = ModelSidecars.withIdRenamed(overlay, "part", "PartLink", "PartNew")
		assertEquals(listOf("PartNew"), part.pose!!.groups.single().parts.first().links)
		assertEquals(emptyList(), ModelSidecars.withoutParameter(overlay, "ParamOld").expressions!!.single().entries)
	}

	@Test fun eventsStayInOrderAndInsideAShortenedClip() {
		val clip = MotionClip("m", "M", duration = 2f, events = MotionClips.normalizedEvents(listOf(MotionEvent(1.5f, "b"), MotionEvent(0.5f, "a"))))
		assertEquals(listOf("a", "b"), clip.events.map { it.value })
		assertEquals(clip, MotionClips.fromJson(MotionClips.toJson(clip)))
		assertEquals(listOf("a"), MotionKeyEdits.withDuration(clip, 1f).events.map { it.value })
		assertFailsWith<IllegalArgumentException> { clip.copy(events = clip.events.reversed()) }
	}
}
