package io.github.psd2live.application

import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.project.WorkspaceDocument
import kotlinx.serialization.json.JsonObject

/**
 * Carries an editor's field draft over a commit it did not make. [base] is what the draft's untouched fields equal,
 * [draft] what the editor shows, [next] the committed document. A field the draft left alone follows [next]; a field
 * only the draft changed keeps the draft; maps and settings merge key by key, and two journals that only grew keep
 * the draft's new commands after the committed ones. Where both changed the same thing the commit wins and the
 * field is named in [Result.conflicts].
 */
internal object WorkspaceDraftRebase {
    data class Result(val document: WorkspaceDocument, val conflicts: List<String>) {
        val clean: Boolean get() = conflicts.isEmpty()
    }

    fun rebase(base: WorkspaceDocument, draft: WorkspaceDocument, next: WorkspaceDocument): Result {
        if (draft == base || draft == next) return Result(next, emptyList())
        if (next == base) return Result(draft, emptyList())
        val conflicts = mutableListOf<String>()
        fun <T> field(name: String, of: (WorkspaceDocument) -> T): T {
            val (b, d, n) = Triple(of(base), of(draft), of(next))
            return when {
                d == b || d == n -> n
                n == b -> d
                else -> { conflicts += name; n }
            }
        }
        @Suppress("UNCHECKED_CAST")
        fun <K, T> map(name: String, of: (WorkspaceDocument) -> Map<K, T>): Map<K, T> {
            val (b, d, n) = Triple(of(base), of(draft), of(next))
            if (d == b || d == n) return n
            if (n == b) return d
            val merged = LinkedHashMap(n)
            for (key in b.keys + d.keys) {
                val (bv, dv, nv) = Triple(b[key], d[key], n[key])
                val present = Triple(key in b, key in d, key in n)
                if (present.second == present.first && dv == bv) continue
                if (present.second == present.third && dv == nv) continue
                if (present.third == present.first && nv == bv) {
                    if (present.second) merged[key] = dv as T else merged.remove(key)
                } else conflicts += "$name.$key"
            }
            return merged
        }
        val document = next.copy(
            source = field("source") { it.source },
            layerVisibility = map("layerVisibility") { it.layerVisibility },
            deletedLayerIds = field("deletedLayerIds") { it.deletedLayerIds },
            layerOverrides = map("layerOverrides") { it.layerOverrides },
            parentOverrides = map("parentOverrides") { it.parentOverrides },
            rigEdits = rigEdits(base.rigEdits, draft.rigEdits, next.rigEdits) ?: field("rigEdits") { it.rigEdits },
            settings = JsonObject(map("settings") { it.settings }),
            meshOverrides = map("meshOverrides") { it.meshOverrides },
            generationSource = field("generationSource") { it.generationSource },
            meshSource = field("meshSource") { it.meshSource },
            textureOverrides = map("textureOverrides") { it.textureOverrides },
            layout = field("layout") { it.layout },
        )
        return Result(document, conflicts)
    }

    /** Both sides only appended to the journal of [base]: the draft's commands replay after the committed ones. */
    private fun rigEdits(base: RigEditOverlay, draft: RigEditOverlay, next: RigEditOverlay): RigEditOverlay? {
        if (draft == base || draft == next || next == base) return null
        val journal = base.authoringJournal
        val added = draft.authoringJournal
        if (added.size <= journal.size || added.subList(0, journal.size) != journal) return null
        if (draft.copy(authoringJournal = journal) != base) return null
        return next.copy(authoringJournal = next.authoringJournal + added.subList(journal.size, added.size))
    }
}
