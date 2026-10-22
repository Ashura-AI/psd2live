package io.github.psd2live.core.quality

import io.github.psd2live.core.GeneratedOverrideIssue
import io.github.psd2live.core.GeneratedOverrideIssueKind
import kotlinx.serialization.json.*
import io.github.psd2live.core.legacy.SupersededEntryNote

/**
 * Stable codes for generated-override findings. The rule alone decides severity and category; callers and the
 * UI read the code, never a localized message.
 */
enum class GeneratedOverrideRule(val severity: String, val category: String) {
	/** The generator and the user's override both moved some points; the user's values were kept. */
	GENERATED_OVERRIDE_CONFLICT("warning", "quality"),
	/** The generated keyform the override edits is gone or reshaped; the override has no effect. */
	GENERATED_OVERRIDE_ORPHANED("warning", "quality"),
	/**
	 * A journal entry before a version 2 split addressed only what that split supersedes and failed there, so replay
	 * skipped it; what it did is gone with the superseded mesh, as the split itself already decided.
	 */
	SUPERSEDED_ENTRY_SKIPPED("info", "coverage"),
	/**
	 * The settings ask for a copy of a brow or eye line over the bangs the generated base found, but none was made: its
	 * original or every bang over it is gone (split, replaced or deleted since), or another mesh has the copy's id.
	 */
	SEE_THROUGH_DROPPED("info", "coverage"),
	;

	companion object {
		fun of(issue: GeneratedOverrideIssue): GeneratedOverrideRule = when (issue.kind) {
			GeneratedOverrideIssueKind.CONFLICT -> GENERATED_OVERRIDE_CONFLICT
			GeneratedOverrideIssueKind.ORPHANED -> GENERATED_OVERRIDE_ORPHANED
		}
	}
}

/**
 * Observation report for generated overrides (report version 4): three checks - one finding per override record that
 * did not apply as recorded, then one info finding per journal entry the replay skipped because it addresses only
 * what a later version 2 split supersedes, each in journal order, then one info finding per copy over the bangs the
 * settings ask for that could not be made. None blocks, so `can_proceed` is always true; a project without any yields
 * a complete report with no findings.
 */
object GeneratedOverrideQuality {
	const val VERSION = 4
	const val DOMAIN = "overrides"
	const val CHECK_ID = "generated_overrides"
	const val SCOPE = "Edits of swing and baked-simulation keyforms, merged per control point or vertex with the current generation. " +
		"Only whether each override applied is checked, not how the merged shape looks."
	const val SUPERSEDED_CHECK_ID = "superseded_entries"
	const val SUPERSEDED_SCOPE = "Journal entries before a version 2 split that failed only on the meshes it supersedes and replayed as no-ops."
	/** Evidence `kind` of a [GeneratedOverrideRule.SUPERSEDED_ENTRY_SKIPPED] finding. */
	const val SUPERSEDED_KIND = "superseded_entry"
	const val SEE_THROUGH_CHECK_ID = "see_through"
	const val SEE_THROUGH_SCOPE = "Copies of brows and eye lines over the bangs the settings ask for, of the features the generated base found the bangs cover."
	/** Evidence `kind` of a [GeneratedOverrideRule.SEE_THROUGH_DROPPED] finding. */
	const val SEE_THROUGH_KIND = "see_through"
	/** Evidence `reason`s of a [GeneratedOverrideRule.SEE_THROUGH_DROPPED] finding. */
	val SEE_THROUGH_REASONS = io.github.psd2live.core.SeeThroughLayers.DropReason.entries.map { it.wire }

	/** A copy of mesh [original] over the bangs not made, for [reason] (one of [SEE_THROUGH_REASONS]). */
	fun finding(original: String, reason: String): JsonObject {
		val rule = GeneratedOverrideRule.SEE_THROUGH_DROPPED
		return buildJsonObject {
			put("code", rule.name)
			put("severity", rule.severity)
			put("category", rule.category)
			put("domain", DOMAIN)
			put("target", "mesh:$original")
			putJsonObject("evidence") { put("kind", SEE_THROUGH_KIND); put("reason", reason) }
		}
	}

	fun finding(note: SupersededEntryNote): JsonObject {
		val rule = GeneratedOverrideRule.SUPERSEDED_ENTRY_SKIPPED
		return buildJsonObject {
			put("code", rule.name)
			put("severity", rule.severity)
			put("category", rule.category)
			put("domain", DOMAIN)
			put("target", "journal:${note.index}")
			putJsonObject("evidence") {
				put("kind", SUPERSEDED_KIND)
				put("index", note.index)
				put("op", note.op)
				putJsonArray("targets") { note.targets.forEach { add(it) } }
				put("detail", note.detail)
			}
		}
	}

	fun finding(issue: GeneratedOverrideIssue): JsonObject {
		val rule = GeneratedOverrideRule.of(issue)
		return buildJsonObject {
			put("code", rule.name)
			put("severity", rule.severity)
			put("category", rule.category)
			put("domain", DOMAIN)
			put("target", issue.target)
			putJsonObject("evidence") {
				put("kind", issue.kind.wire)
				put("generator", issue.generator)
				putJsonObject("key") { issue.key.toSortedMap().forEach { (id, value) -> put(id, value) } }
				put("points", issue.points)
				put("total", issue.total)
				issue.reason?.let { put("reason", it.wire) }
				issue.channel?.let { put("channel", it) }
			}
		}
	}

	/** [seeThrough]: the copies over the bangs not made, as (original mesh, reason) pairs. */
	fun report(issues: List<GeneratedOverrideIssue>, notes: List<SupersededEntryNote> = emptyList(),
	           seeThrough: List<Pair<String, String>> = emptyList()): JsonObject = buildJsonObject {
		put("version", VERSION)
		put("domain", DOMAIN)
		put("fence", "observation")
		put("decision", if (issues.isEmpty() && notes.isEmpty() && seeThrough.isEmpty()) "accept" else "accept_with_diagnostics")
		put("can_proceed", true)
		put("complete", true)
		put("scope", SCOPE)
		putJsonArray("checks") {
			add(buildJsonObject { put("id", CHECK_ID); put("scope", SCOPE); put("complete", true) })
			add(buildJsonObject { put("id", SUPERSEDED_CHECK_ID); put("scope", SUPERSEDED_SCOPE); put("complete", true) })
			add(buildJsonObject { put("id", SEE_THROUGH_CHECK_ID); put("scope", SEE_THROUGH_SCOPE); put("complete", true) })
		}
		put("findings", JsonArray(issues.map(::finding) + notes.map(::finding) + seeThrough.map { (original, reason) -> finding(original, reason) }))
	}
}
