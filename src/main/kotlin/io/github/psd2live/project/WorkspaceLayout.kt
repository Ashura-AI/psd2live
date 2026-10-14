package io.github.psd2live.project

import io.github.psd2live.core.LayerClassificationOverride
import io.github.psd2live.core.LayerType
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.Side
import kotlinx.serialization.json.*
import org.umamo.format.art.LayerRaster

/**
 * The document's Layout state: what the material library holds, which source layers come from which material, the
 * user's own layer tags, and where generation stands. Every part is empty in a document written before Layout, which
 * then reads and hashes exactly as it did ([isEmpty]).
 */
data class WorkspaceLayout(
	/** The user's own tags per source layer: words outside the generator vocabulary ([io.github.psd2live.core.LayerTags]). */
	val layerTags: Map<String, Set<String>> = emptyMap(),
	/** The material each placed source layer shows, by layer ID. */
	val layerMaterials: Map<String, String> = emptyMap(),
	val generation: WorkspaceGeneration = WorkspaceGeneration.Generated,
	val materials: MaterialLibrary = MaterialLibrary.Empty,
) {
	val isEmpty: Boolean get() = this == Empty

	/** [layerTags] and [layerMaterials] without layers no longer in [layerIds]. */
	fun retaining(layerIds: Set<String>): WorkspaceLayout = copy(
		layerTags = layerTags.filterKeys { it in layerIds },
		layerMaterials = layerMaterials.filterKeys { it in layerIds },
		generation = generation.retaining(layerIds),
	)

	companion object {
		val Empty = WorkspaceLayout()
	}
}

/**
 * Where generation stands. A document is generated unless it was created empty for Layout and never generated
 * ([generated] false): its rig then is only the placement preview. After generation, layers placed since
 * ([pendingLayerIds]) and classification or parent changes ([pendingClassifications], [pendingParents]) wait for the
 * next generation; the rig keeps reading the values the last one used ([WorkspaceDocument.layerOverrides],
 * [WorkspaceDocument.parentOverrides]). [refreshLayerIds] are layers whose image was replaced, which the next
 * generation may mesh again from their new pixels.
 */
data class WorkspaceGeneration(
	val generated: Boolean = true,
	val pendingLayerIds: Set<String> = emptySet(),
	val pendingClassifications: Map<String, LayerClassificationOverride> = emptyMap(),
	/** A parent each layer waits for; a null value returns the layer to its generated parent. */
	val pendingParents: Map<String, String?> = emptyMap(),
	val refreshLayerIds: Set<String> = emptySet(),
) {
	/** Nothing waits for the next generation. */
	val settled: Boolean get() = generated && pendingLayerIds.isEmpty() && pendingClassifications.isEmpty() && pendingParents.isEmpty()

	fun retaining(layerIds: Set<String>) = copy(
		pendingLayerIds = pendingLayerIds.filterTo(LinkedHashSet()) { it in layerIds },
		pendingClassifications = pendingClassifications.filterKeys { it in layerIds },
		pendingParents = pendingParents.filterKeys { it in layerIds },
		refreshLayerIds = refreshLayerIds.filterTo(LinkedHashSet()) { it in layerIds },
	)

	companion object {
		val Generated = WorkspaceGeneration()
		val NotGenerated = WorkspaceGeneration(generated = false)
	}
}

enum class MaterialKind { BITMAP, VECTOR }

/** A folder of the material library. A null [parentId] puts it at the top. */
data class MaterialFolder(val id: String, val name: String, val parentId: String?, val tags: Set<String> = emptySet())

/**
 * One material: a raster, or a vector drawing with the raster it is rendered to at its texture resolution
 * ([raster]'s size). [placement] is the canvas rectangle its source gave it (a PSD layer's position), used as its
 * default placement; [origin] names where it was imported from.
 */
data class MaterialItem(
	val id: String,
	val name: String,
	val folderId: String?,
	val kind: MaterialKind,
	val raster: LayerRaster,
	val placement: LayerCanvasRect? = null,
	val svg: String? = null,
	val tags: Set<String> = emptySet(),
	val origin: String = "",
	val opacity: Float = 1f,
	val visible: Boolean = true,
) {
	init {
		require(kind != MaterialKind.VECTOR || svg != null) { "A vector material keeps its drawing" }
		require(raster.width > 0 && raster.height > 0) { "A material raster cannot be empty" }
	}
}

/** The material library: folders and items, each list in display order. */
data class MaterialLibrary(val folders: List<MaterialFolder> = emptyList(), val items: List<MaterialItem> = emptyList()) {
	private val folderById by lazy { folders.associateBy { it.id } }
	private val itemById by lazy { items.associateBy { it.id } }

	fun folder(id: String): MaterialFolder? = folderById[id]
	fun item(id: String): MaterialItem? = itemById[id]

	/** [folderId] and the folders above it, nearest first. */
	fun ancestry(folderId: String?): List<MaterialFolder> = generateSequence(folderId?.let(::folder)) { it.parentId?.let(::folder) }.toList()

	/** [folderId] and every folder below it. */
	fun subtree(folderId: String): Set<String> {
		val children = folders.groupBy { it.parentId }
		val out = LinkedHashSet<String>()
		fun visit(id: String) { if (out.add(id)) children[id]?.forEach { visit(it.id) } }
		visit(folderId)
		return out
	}

	/** The folder path of [folderId] as names, top first. */
	fun path(folderId: String?): List<String> = ancestry(folderId).reversed().map { it.name }

	companion object {
		val Empty = MaterialLibrary()
	}
}

/**
 * JSON form of [WorkspaceLayout], under two top-level snapshot keys: [LAYOUT] (tags, material links, generation)
 * and [MATERIALS] (the library). Each is written only when not empty. Rasters are written as references
 * ([rasterRef]) and read back through [rasterOf].
 */
internal object WorkspaceLayoutCodec {
	const val LAYOUT = "layout"
	const val MATERIALS = "materials"

	fun encodeLayout(layout: WorkspaceLayout): JsonObject? {
		if (layout.layerTags.isEmpty() && layout.layerMaterials.isEmpty() && layout.generation == WorkspaceGeneration.Generated) return null
		return buildJsonObject {
			if (layout.layerTags.isNotEmpty()) putJsonObject("layerTags") {
				layout.layerTags.toSortedMap().forEach { (id, tags) -> putJsonArray(id) { tags.sorted().forEach { add(JsonPrimitive(it)) } } }
			}
			if (layout.layerMaterials.isNotEmpty()) putJsonObject("layerMaterials") {
				layout.layerMaterials.toSortedMap().forEach { (id, material) -> put(id, material) }
			}
			val generation = layout.generation
			if (generation != WorkspaceGeneration.Generated) putJsonObject("generation") {
				put("generated", generation.generated)
				if (generation.pendingLayerIds.isNotEmpty()) putJsonArray("pendingLayerIds") { generation.pendingLayerIds.sorted().forEach { add(JsonPrimitive(it)) } }
				if (generation.pendingClassifications.isNotEmpty()) putJsonObject("pendingClassifications") {
					generation.pendingClassifications.toSortedMap().forEach { (id, value) -> put(id, encodeClassification(value)) }
				}
				if (generation.pendingParents.isNotEmpty()) putJsonObject("pendingParents") {
					generation.pendingParents.toSortedMap().forEach { (id, parent) -> if (parent == null) put(id, JsonNull) else put(id, parent) }
				}
				if (generation.refreshLayerIds.isNotEmpty()) putJsonArray("refreshLayerIds") { generation.refreshLayerIds.sorted().forEach { add(JsonPrimitive(it)) } }
			}
		}
	}

	fun encodeMaterials(library: MaterialLibrary, rasterRef: (LayerRaster) -> String): JsonObject? {
		if (library.folders.isEmpty() && library.items.isEmpty()) return null
		return buildJsonObject {
			putJsonArray("folders") {
				library.folders.forEach { folder -> addJsonObject {
					put("id", folder.id); put("name", folder.name)
					folder.parentId?.let { put("parentId", it) }
					if (folder.tags.isNotEmpty()) putJsonArray("tags") { folder.tags.sorted().forEach { add(JsonPrimitive(it)) } }
				} }
			}
			putJsonArray("items") {
				library.items.forEach { item -> addJsonObject {
					put("id", item.id); put("name", item.name)
					item.folderId?.let { put("folderId", it) }
					put("kind", item.kind.name)
					put("rasterWidth", item.raster.width); put("rasterHeight", item.raster.height)
					put("rgbaBlob", rasterRef(item.raster))
					item.placement?.let { rect -> putJsonArray("placement") { listOf(rect.left, rect.top, rect.width, rect.height).forEach { add(JsonPrimitive(it)) } } }
					item.svg?.let { put("svg", it) }
					if (item.tags.isNotEmpty()) putJsonArray("tags") { item.tags.sorted().forEach { add(JsonPrimitive(it)) } }
					if (item.origin.isNotEmpty()) put("origin", item.origin)
					if (item.opacity != 1f) put("opacity", item.opacity)
					if (!item.visible) put("visible", false)
				} }
			}
		}
	}

	fun decode(layout: JsonObject?, materials: JsonObject?, rasterOf: (hash: String, width: Int, height: Int) -> ByteArray): WorkspaceLayout {
		if (layout == null && materials == null) return WorkspaceLayout.Empty
		fun strings(value: JsonElement?) = value?.jsonArray?.mapTo(LinkedHashSet()) { it.jsonPrimitive.content }.orEmpty()
		val generation = layout?.get("generation")?.jsonObject?.let { value ->
			WorkspaceGeneration(
				generated = value.getValue("generated").jsonPrimitive.boolean,
				pendingLayerIds = strings(value["pendingLayerIds"]),
				pendingClassifications = value["pendingClassifications"]?.jsonObject?.mapValues { decodeClassification(it.value.jsonObject) }.orEmpty(),
				pendingParents = value["pendingParents"]?.jsonObject?.mapValues { it.value.jsonPrimitive.contentOrNull }.orEmpty(),
				refreshLayerIds = strings(value["refreshLayerIds"]),
			)
		} ?: WorkspaceGeneration.Generated
		val library = materials?.let { value ->
			MaterialLibrary(
				folders = value["folders"]?.jsonArray.orEmpty().map { element ->
					val folder = element.jsonObject
					MaterialFolder(folder.getValue("id").jsonPrimitive.content, folder.getValue("name").jsonPrimitive.content,
						folder["parentId"]?.jsonPrimitive?.contentOrNull, strings(folder["tags"]))
				},
				items = value["items"]?.jsonArray.orEmpty().map { element ->
					val item = element.jsonObject
					val width = item.getValue("rasterWidth").jsonPrimitive.int
					val height = item.getValue("rasterHeight").jsonPrimitive.int
					MaterialItem(
						id = item.getValue("id").jsonPrimitive.content,
						name = item.getValue("name").jsonPrimitive.content,
						folderId = item["folderId"]?.jsonPrimitive?.contentOrNull,
						kind = MaterialKind.valueOf(item.getValue("kind").jsonPrimitive.content),
						raster = LayerRaster(width, height, rasterOf(item.getValue("rgbaBlob").jsonPrimitive.content, width, height)),
						placement = item["placement"]?.jsonArray?.map { it.jsonPrimitive.float }?.let { LayerCanvasRect(it[0], it[1], it[2], it[3]) },
						svg = item["svg"]?.jsonPrimitive?.contentOrNull,
						tags = strings(item["tags"]),
						origin = item["origin"]?.jsonPrimitive?.contentOrNull.orEmpty(),
						opacity = item["opacity"]?.jsonPrimitive?.float ?: 1f,
						visible = item["visible"]?.jsonPrimitive?.boolean ?: true,
					)
				},
			)
		} ?: MaterialLibrary.Empty
		return WorkspaceLayout(
			layerTags = layout?.get("layerTags")?.jsonObject?.mapValues { strings(it.value) }.orEmpty(),
			layerMaterials = layout?.get("layerMaterials")?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty(),
			generation = generation,
			materials = library,
		)
	}

	/** The rasters [materials] names, as `(hash, width, height)`, for loading them ahead of decoding. */
	fun rasterRefs(materials: JsonObject?): List<Triple<String, Int, Int>> = materials?.get("items")?.jsonArray.orEmpty().mapNotNull { element ->
		val item = element as? JsonObject ?: return@mapNotNull null
		val hash = item["rgbaBlob"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
		Triple(hash, item["rasterWidth"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null, item["rasterHeight"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null)
	}

	fun encodeClassification(value: LayerClassificationOverride) = buildJsonObject {
		put("type", value.type.name); put("tag", value.tag.name); put("side", value.side.name)
		put("parameter", value.parameter); put("switchId", value.switchId)
	}

	fun decodeClassification(value: JsonObject) = LayerClassificationOverride(
		LayerType.valueOf(value.getValue("type").jsonPrimitive.content), SemanticTag.valueOf(value.getValue("tag").jsonPrimitive.content),
		Side.valueOf(value.getValue("side").jsonPrimitive.content), value.getValue("parameter").jsonPrimitive.content,
		value.getValue("switchId").jsonPrimitive.int)

	/** The text a revision identity hashes for [layout]: its two JSON parts, with rasters as their digests. Empty for an empty layout. */
	fun canonical(layout: WorkspaceLayout): String = buildString {
		encodeLayout(layout)?.let { append("|layout:").append(it) }
		encodeMaterials(layout.materials) { WorkspaceRevisions.rasterDigest(it.rgba) }?.let { append("|materials:").append(it) }
	}
}
