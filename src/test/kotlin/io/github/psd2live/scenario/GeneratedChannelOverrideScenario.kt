package io.github.psd2live.scenario

import io.github.psd2live.core.GeneratedOverrideIssueKind
import io.github.psd2live.core.GeneratedOverrideOrphanReason
import io.github.psd2live.core.GeneratedOverrides
import io.github.psd2live.core.RigCommandDelta
import io.github.psd2live.core.SkeletonCanvasSkin
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.runtime.model.*
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test

/**
 * A generator that keys a tint and a fade on its own axis - as a swing that darkens hair at its far end would - owns
 * those channel cells as it owns its lattice. The user's edit of them is recorded as an override per channel and merged
 * component by component when the generator changes; channels the generator does not key stay ordinary journal edits.
 * No generator keys channels yet, so the swing's tint is put on the rig the way such a generator would leave it.
 */
class GeneratedChannelOverrideScenario {
	@TempDir lateinit var temp: Path

	@Test fun anEditOfAGeneratedTintIsKeptAsAnOverride() = Studio.run(temp, Characters.head(physics = false)) {
		edit("add hair swing", "swing_put", req("id" to "sway", "kind" to "lateral", "targets" to listOf(WARP), "magnitude" to 0.3))
		val parameter = puppet.parameters.single { it.id.raw.startsWith("ParamSwing") }
		val authored = now.model.authored.rig.puppet
		val overlay = now.document.rigEdits
		val far = mapOf(parameter.id.raw to parameter.max)
		val white = ColorRgb(1f, 1f, 1f)
		fun <T> track(rest: T, end: T) = KeyformGrid(listOf(KeyformAxis(parameter.id, floatArrayOf(parameter.default, parameter.max))),
			listOf(KeyformCell(intArrayOf(0), rest), KeyformCell(intArrayOf(1), end)))
		fun tinted(model: PuppetModel, opacity: Float, multiply: ColorRgb) = model.copy(deformers = model.deformers.map { deformer ->
			if (deformer.id.raw != WARP) deformer else (deformer as Deformer.Warp).copy(channelGrids = ChannelGrids(deformer.channelGrids.gridsByChannel + mapOf(
				FormChannel.OPACITY to track<ChannelValue>(ChannelValue.Scalar(1f), ChannelValue.Scalar(opacity)),
				FormChannel.MULTIPLY_COLOR to track<ChannelValue>(ChannelValue.Color(white), ChannelValue.Color(multiply)))))
		})
		fun warp(model: PuppetModel) = model.deformers.single { it.id.raw == WARP } as Deformer.Warp
		fun farValue(model: PuppetModel, channel: FormChannel) = warp(model).channelGrids[channel]!!.cells.single { it.coordinate[0] == 1 }.form
		fun set(channels: JsonObject) = buildJsonObject {
			put("op", "set"); put("target", "warp:$WARP"); put("key", JsonObject(far.mapValues { JsonPrimitive(it.value) })); put("channels", channels)
		}
		val shown = tinted(puppet, 0.8f, ColorRgb(1f, 0.9f, 0.9f))
		val edit = set(buildJsonObject {
			put("opacity", 0.5f); putJsonArray("multiplyColor") { add(0.7f); add(0.9f); add(0.9f) }; putJsonArray("screenColor") { add(0.1f); add(0f); add(0f) }
		})

		// Recording: the generated channels become overrides, the screen color the swing does not key stays in the entry.
		val written = GeneratedOverrides.ownedWrites(shown, overlay, listOf(edit), authored = authored)
		val overrides = written.filter(GeneratedOverrides::isOverride)
		expect("recorded", overrides.map { it.getValue("channel").jsonPrimitive.content }.sorted() == listOf("multiply_color", "opacity")) { "recorded $written" }
		expect("recorded", overrides.all { it.getValue("generator").jsonPrimitive.content == "swing:sway" }) { "recorded $overrides" }
		val rest = written.single { !GeneratedOverrides.isOverride(it) }
		expect("recorded", rest.getValue("channels").jsonObject.keys == setOf("screenColor")) { "the user's own channel became $rest" }
		expect("recorded", GeneratedOverrides.ownedWrites(shown, overlay, listOf(edit)) == listOf(edit)) { "without the authored rig nothing is the generator's" }
		expect("recorded", GeneratedOverrides.ownedWrites(shown, overlay, listOf(edit), authored = tinted(authored, 1f, white)) == listOf(edit)) {
			"a track the journal keyed itself became an override"
		}
		expect("vertex counts", SkeletonCanvasSkin.addressedCounts(overrides).isEmpty()) { "a channel override was read as a vertex count" }

		// Change detection: writing the generated value back changes nothing; another value does.
		expect("no-op", RigCommandDelta.isNoOp(shown, set(buildJsonObject { put("opacity", 0.8f) }))) { "rewriting the generated opacity is a change" }
		expect("no-op", !RigCommandDelta.isNoOp(shown, set(buildJsonObject { put("opacity", 0.5f) }))) { "a new opacity is not a change" }

		// Refused: copying into the generated cell, deleting the generator's key of a channel.
		val copy = buildJsonObject {
			put("op", "copy"); put("target", "warp:$WARP"); put("from", buildJsonObject { put(parameter.id.raw, parameter.default) })
			put("key", JsonObject(far.mapValues { JsonPrimitive(it.value) })); putJsonArray("channels") { add("opacity") }
		}
		expect("refused", runCatching { GeneratedOverrides.ownedWrites(shown, overlay, listOf(copy), authored = authored) }.isFailure) { "a copy into a generated channel was recorded" }
		val delete = buildJsonObject {
			put("op", "delete"); put("target", "warp:$WARP"); put("parameter", parameter.id.raw); put("value", parameter.max); put("channel", "multiplyColor")
		}
		expect("refused", runCatching { GeneratedOverrides.ownedWrites(shown, overlay, listOf(delete), authored = authored) }.isFailure) { "deleting a generated channel key was recorded" }

		// Merge after the generator changes: opacity changed on both sides keeps the user's and reports it; green, which
		// only the generator changed, follows it; red keeps the user's.
		val merged = GeneratedOverrides.applyAll(tinted(puppet, 0.6f, ColorRgb(1f, 0.6f, 0.9f)), written)
		expect("merged", (farValue(merged.model, FormChannel.OPACITY) as ChannelValue.Scalar).value == 0.5f) { "opacity is ${farValue(merged.model, FormChannel.OPACITY)}" }
		val tint = (farValue(merged.model, FormChannel.MULTIPLY_COLOR) as ChannelValue.Color).color
		expect("merged", abs(tint.red - 0.7f) < 1e-6f && abs(tint.green - 0.6f) < 1e-6f && abs(tint.blue - 0.9f) < 1e-6f) { "multiply color is $tint" }
		val conflict = merged.issues.single()
		expect("merged", conflict.kind == GeneratedOverrideIssueKind.CONFLICT && conflict.channel == "opacity" && conflict.points == 1 && conflict.total == 1) {
			"reported ${merged.issues}"
		}
		val unchanged = GeneratedOverrides.applyAll(shown, written)
		expect("unchanged generator", unchanged.issues.isEmpty() && (farValue(unchanged.model, FormChannel.OPACITY) as ChannelValue.Scalar).value == 0.5f) {
			"against the generation it was made on the override gave ${unchanged.issues}"
		}

		// A generator that stops keying the channels leaves the overrides orphaned: reported, recorded and kept on reopen.
		val orphaned = GeneratedOverrides.applyAll(puppet, written)
		expect("orphaned", orphaned.model === puppet && orphaned.issues.size == 2 && orphaned.issues.all {
			it.kind == GeneratedOverrideIssueKind.ORPHANED && it.reason == GeneratedOverrideOrphanReason.MISSING_KEYFORM && it.channel != null
		}) { "orphaned overrides gave ${orphaned.issues}" }
		allow("GENERATED_OVERRIDE_ORPHANED")
		journal("record the overrides", JsonArray(overrides))
		fun reported() = inspect().getValue("quality").jsonObject.getValue("overrides").jsonObject.getValue("findings").jsonArray
			.mapNotNull { it.jsonObject.getValue("evidence").jsonObject["channel"]?.jsonPrimitive?.content }.sorted()
		expect("replayed", reported() == listOf("multiply_color", "opacity")) { "the replay reported ${reported()}" }
		reopen()
		expect("reopened", now.document.rigEdits.authoringJournal.count(GeneratedOverrides::isChannelOverride) == 2) { "the channel overrides were not saved" }
		expect("reopened", reported() == listOf("multiply_color", "opacity")) { "the reopened project reported ${reported()}" }
	}

	private companion object {
		const val WARP = "DeformHairBackPhysics"
	}
}
