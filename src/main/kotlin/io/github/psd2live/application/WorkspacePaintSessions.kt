package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class WorkspacePaintStroke(val id: String, val name: String)

/** What [WorkspacePaintSession.lift] took off the raster for a transform in hand: [region] in raster pixels, its straight ARGB [pixels]. */
class WorkspacePaintLift(val region: Rectangle, val pixels: IntArray)

/**
 * One private raster and stroke history, shared by UI and public session controls.
 *
 * The raster is the layer's own pixel grid over the canvas ([PaintSpace]): for a layer at one pixel per canvas
 * unit the canvas itself, for a denser one its raster extended at the same density. Everything callers pass -
 * points, radii, widths, sample positions - is in canvas units; [space] maps it onto the raster.
 */
class WorkspacePaintSession internal constructor(
    val id: String, val projectId: String, val workspaceState: String,
    val layerId: String, val layerName: String, image: BufferedImage,
    internal val space: PaintSpace = PaintSpace.canvas(image.width, image.height),
    /** The document canvas, in canvas units. */
    val canvasWidth: Int = image.width, val canvasHeight: Int = image.height,
    private val validateWorkspace: () -> Unit,
    /**
     * The material item painted: the one the layer shows (the paint on its meshes reaches the material), or the item
     * itself when [paintsMaterial].
     */
    val materialId: String? = null,
    /**
     * Whether the session paints material item [materialId] of the library rather than a layer: its raster at one
     * pixel per canvas unit, [layerId] [MATERIAL_PREFIX] and the id. Its commit is `material_set_pixels`.
     */
    val paintsMaterial: Boolean = false,
) {
    private val lock = Any()
    private val buffer = PaintRasterSession(layerId, layerName, image, copy(image))
    private var version = 0L
    private var active = false
    /** Whether the live edit in hand is a [lift], which a `transform` gesture puts back before it lands. */
    private var lifted = false
    private var committing = false
    private var closed = false
    private var completion: WorkspaceMutationResult? = null
    private val observers = linkedSetOf<(List<Rectangle>) -> Unit>()
    private val changedRegions = mutableListOf<Rectangle>()
    val width: Int get() = imageWidth
    val height: Int get() = imageHeight
    private val imageWidth = image.width
    private val imageHeight = image.height
    val sessionState: String get() = synchronized(lock) { token() }
    val isDirty: Boolean get() = synchronized(lock) { buffer.isDirty }
    val activeStroke: Boolean get() = synchronized(lock) { active }
    val finished: Boolean get() = synchronized(lock) { closed }
    val index: Int get() = synchronized(lock) { buffer.currentStrokeIndex }
    val strokes: List<WorkspacePaintStroke> get() = synchronized(lock) {
        buffer.strokeRecords.map { WorkspacePaintStroke(it.id, it.name) }
    }
    init { buffer.onChanged = { regions -> changedRegions.addAll(regions) } }
    internal fun <T> atomic(expected: String, block: () -> T): T = synchronized(lock) {
        if (expected != token()) throw WorkspaceConflict(expected, token())
        block()
    }
    fun observe(observer: (List<Rectangle>) -> Unit): () -> Unit {
        synchronized(lock) { observers += observer }
        return { synchronized(lock) { observers -= observer } }
    }
    fun image(): BufferedImage = synchronized(lock) { copy(buffer.workingImage) }
    fun tile(x: Int, y: Int, width: Int, height: Int): BufferedImage = synchronized(lock) {
        BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also {
            it.setRGB(0, 0, width, height, buffer.workingImage.getRGB(x, y, width, height, null, 0, width), 0, width)
        }
    }
    /** Canvas units of the raster's left and top edges, and raster pixels per canvas unit. */
    val originX: Float get() = space.originX
    val originY: Float get() = space.originY
    val scaleX: Float get() = space.scaleX
    val scaleY: Float get() = space.scaleY

    /** The colour of the raster pixel under canvas pixel ([x], [y]); transparent where the raster does not reach. */
    fun sample(x: Int, y: Int): Int = synchronized(lock) {
        require(x in 0 until canvasWidth && y in 0 until canvasHeight) { "Sample point is outside the canvas" }
        val (px, py) = space.imagePixel(x, y) ?: return 0
        buffer.workingImage.getRGB(px, py)
    }
    fun canUndo(): Boolean = synchronized(lock) { !closed && !active && buffer.canUndo() }
    fun canRedo(): Boolean = synchronized(lock) { !closed && !active && buffer.canRedo() }
    private fun token() = "$id:$version"
    private fun writable(expected: String? = null, allowActive: Boolean = false) {
        expected?.let { if (it != token()) throw WorkspaceConflict(it, token()) }
        check(!closed) { "Paint session is closed" }
        if (committing || (active && !allowActive)) throw WorkspaceBusy()
        validateWorkspace()
    }
    private fun publish() {
        val regions = changedRegions.toList(); changedRegions.clear()
        observers.toList().forEach { runCatching { it(regions) } }
    }
    private fun changed() { version++; publish() }
    fun beginStroke() = synchronized(lock) {
        writable(); active = true; buffer.beginStroke(); changed()
    }
    internal fun segment(x0: Float, y0: Float, x1: Float, y1: Float, tip: RasterPaintEngine.Tip,
        color: Int, opacity: Float, erase: Boolean, lockAlpha: Boolean = false) = synchronized(lock) {
        writable(allowActive = true); check(active) { "No live paint stroke" }
        val rasterTip = if (space.isCanvas) tip else RasterPaintEngine.Tip(space.imageLength(tip.radius), tip.hardness, tip.antialias)
        buffer.stroke().addSegment(space.canvasToImageX(x0), space.canvasToImageY(y0),
            space.canvasToImageX(x1), space.canvasToImageY(y1), rasterTip)?.let {
            buffer.landSegment(it, color, opacity, erase, selection, lockAlpha); changed()
        }
    }

    /** One segment of a live blur stroke: [strength] 0..1 of the way towards the blurred pixels under the tip. */
    internal fun blurSegment(x0: Float, y0: Float, x1: Float, y1: Float, tip: RasterPaintEngine.Tip, strength: Float) = synchronized(lock) {
        writable(allowActive = true); check(active) { "No live paint stroke" }
        val rasterTip = if (space.isCanvas) tip else RasterPaintEngine.Tip(space.imageLength(tip.radius), tip.hardness, tip.antialias)
        buffer.stroke().addSegment(space.canvasToImageX(x0), space.canvasToImageY(y0),
            space.canvasToImageX(x1), space.canvasToImageY(y1), rasterTip)?.let {
            buffer.landBlurSegment(it, strength, blurKernel(rasterTip.radius), selection); changed()
        }
    }

    /**
     * The selection every edit of the session lands through, in raster pixels; null with nothing selected, when
     * edits reach the whole raster. It is the session's, not the stroke history's: undo leaves it alone.
     */
    internal var selection: PaintSelection? = null
        private set

    /** Bumped by every [select], so a view of the selection's edge knows when to trace it again. */
    var selectionRevision = 0
        private set

    /** Raster pixels of the selection's bounds, or null without one. */
    val selectionBounds: Rectangle? get() = synchronized(lock) { selection?.bounds }

    /** The selection's edge for the marching ants, as [PaintSelection.outline] segments in raster pixels. */
    fun selectionOutline(): FloatArray = synchronized(lock) { selection?.outline() ?: FloatArray(0) }

    /**
     * Changes the selection: [request] is one [WorkspacePaintSessionSchemas.selection] - a shape in canvas units
     * (`rectangle`, `ellipse`, `lasso`, `wand`, `quick`) or the layer's alpha (`opaque`) met with the current selection
     * through `op`, or `all`, `none`, `invert`, `feather`, `expand` and `contract`. A selection that ends up empty is no
     * selection.
     */
    fun select(request: JsonObject, expected: String? = null) = synchronized(lock) {
        writable(expected)
        validateOperationSchema(request, WorkspacePaintSessionSchemas.selection)
        val w = imageWidth; val h = imageHeight
        fun point(value: JsonElement): Pair<Float, Float> {
            val n = value.jsonArray.map { it.jsonPrimitive.float }
            require(n.size == 2 && n.all(Float::isFinite)) { "point must be [x,y]" }
            return space.canvasToImageX(n[0]) to space.canvasToImageY(n[1])
        }
        val antialias = request["antialias"]?.jsonPrimitive?.boolean ?: true
        val mode = request.getValue("mode").jsonPrimitive.content
        val shape: PaintSelection? = when (mode) {
            "all" -> PaintSelection.all(w, h)
            "none" -> null
            // Nothing selected has nothing to turn inside out; Select All is the way to everything.
            "invert" -> selection?.inverted()
            "feather" -> selection?.feathered(space.imageLength(request.getValue("radius").jsonPrimitive.float))
            "expand" -> selection?.grown(space.imageLength(request.getValue("radius").jsonPrimitive.float))
            "contract" -> selection?.grown(-space.imageLength(request.getValue("radius").jsonPrimitive.float))
            "opaque" -> PaintSelection.opaque(buffer.workingImage)
            "quick" -> PaintSelection.quick(buffer.workingImage, request.getValue("points").jsonArray
                .flatMap { point(it).toList() }.toFloatArray(), space.imageLength(request.getValue("radius").jsonPrimitive.float),
                request["tolerance"]?.jsonPrimitive?.int ?: 32)
            "rectangle", "ellipse" -> {
                val (x0, y0) = point(request.getValue("from")); val (x1, y1) = point(request.getValue("to"))
                if (mode == "rectangle") PaintSelection.rectangle(w, h, x0, y0, x1, y1, antialias)
                else PaintSelection.ellipse(w, h, x0, y0, x1, y1, antialias)
            }
            "lasso" -> PaintSelection.polygon(w, h, request.getValue("points").jsonArray
                .flatMap { point(it).toList() }.toFloatArray(), antialias)
            "wand" -> {
                val (x, y) = point(request.getValue("point"))
                PaintSelection.wand(buffer.workingImage, kotlin.math.floor(x).toInt(), kotlin.math.floor(y).toInt(),
                    request["tolerance"]?.jsonPrimitive?.int ?: 32, request["contiguous"]?.jsonPrimitive?.boolean ?: true)
            }
            else -> throw IllegalArgumentException("Unknown selection mode: $mode")
        }
        val op = PaintSelectionOp.valueOf((request["op"]?.jsonPrimitive?.content ?: "replace").uppercase())
        val current = selection
        val next = when {
            // These act on the selection as a whole; the op is for a shape meeting it.
            mode in WHOLE_SELECTION_MODES -> shape
            // Nothing selected yet: a shape added is the selection, one taken away or intersected leaves none.
            current == null -> shape.takeIf { op == PaintSelectionOp.REPLACE || op == PaintSelectionOp.ADD }
            else -> current.combine(requireNotNull(shape), op)
        }
        selection = next?.takeUnless { it.isEmpty }
        selectionRevision++
        changed()
    }
    fun recordStroke(name: String) = synchronized(lock) {
        writable(allowActive = true); buffer.recordStroke(name); active = false; changed()
    }
    fun abandonStroke(expected: String? = null) = synchronized(lock) {
        writable(expected, allowActive = true); buffer.abandonStroke(); active = false; lifted = false; changed()
    }

    /** What a `transform` gesture takes: the selection's bounds, else every pixel of the raster that is not transparent. */
    private fun transformRegion(): Rectangle? =
        selection?.bounds ?: PaintPixelTransform.contentBounds(buffer.workingImage, Rectangle(0, 0, imageWidth, imageHeight))

    /**
     * Takes what a `transform` would move off the raster while it is being placed, as Photoshop shows a free transform:
     * a live edit, outside the history, that [abandonStroke] puts back and a `transform` gesture puts back before it
     * lands. Null - and nothing taken - when there is nothing to move.
     */
    fun lift(): WorkspacePaintLift? = synchronized(lock) {
        writable()
        val region = transformRegion() ?: return null
        val pixels = PaintPixelTransform.lifted(buffer.workingImage, region, selection)
        buffer.willWrite(region)
        PaintPixelTransform.clear(buffer.workingImage, region, selection)
        active = true; lifted = true
        changed()
        WorkspacePaintLift(region, pixels)
    }
    fun gesture(request: JsonObject, name: String = request.getValue("mode").jsonPrimitive.content,
        expected: String? = null, checkpoint: () -> Unit = {}) = synchronized(lock) {
        val mode = request.getValue("mode").jsonPrimitive.content
        val transform = mode == "transform"
        writable(expected, allowActive = transform && lifted)
        validateOperationSchema(request, WorkspacePaintSessionSchemas.gesture)
        if (lifted) { buffer.abandonStroke(); active = false; lifted = false }
        val lockAlpha = request["lock_alpha"]?.jsonPrimitive?.boolean ?: false
        // Keeping the alpha of what is painted while taking it away contradicts itself; the editor's eraser paints the
        // background colour under lock transparency instead, which a public gesture asks for as a brush stroke.
        require(!lockAlpha || (mode != "eraser" && mode != "clear")) { "lock_alpha does not apply to $mode: paint the colour with a brush instead" }
        // One tile patch is the rollback boundary for a fill, shape or complete public stroke.
        // A transform moves the selection's pixels rather than painting inside it; with nothing to move it does nothing.
        val area = if (transform) transformRegion() ?: run { changed(); return@synchronized } else gestureArea()
        try {
            paintRasterGesture(buffer.workingImage, request, object : WorkspaceRasterWork {
                override fun checkpoint() = checkpoint()
                override fun progress(fraction: Float, message: String) {}
            }, before = { checkpoint(); buffer.willWrite(it) }, space = space, area = area, selection = selection.takeIf { transform },
                symmetryAxes = canvasWidth / 2f to canvasHeight / 2f)
            // Every gesture lands through the selection, and with lock_alpha only on what is already painted.
            if (!transform) buffer.confinePending(selection, lockAlpha)
            checkpoint(); buffer.recordStroke(name)
            // The selection goes where its pixels went.
            if (transform) selection?.let { current ->
                selection = PaintPixelTransform.mapSelection(current, rasterTransformMatrix(request, space))
                selectionRevision++
            }
            changed()
        } catch (failure: Throwable) { buffer.abandonStroke(); publish(); throw failure }
    }
    /**
     * What a fill, gradient or clear covers: the selection's bounds, else the layer's own area and whatever the session
     * has painted - so a fill without a selection fills the layer rather than the whole extended raster, and a commit
     * keeping the meshes does not split a canvas-sized layer off it. A layer with nothing on it yet takes the canvas.
     */
    private fun gestureArea(): Rectangle {
        selection?.bounds?.let { return it }
        val layer = space.layerArea()
        val painted = buffer.touched?.let { if (layer.isEmpty) it else layer.union(it) } ?: layer
        if (!painted.isEmpty) return painted
        val left = kotlin.math.floor(space.canvasToImageX(0f)).toInt(); val top = kotlin.math.floor(space.canvasToImageY(0f)).toInt()
        val right = kotlin.math.ceil(space.canvasToImageX(canvasWidth.toFloat())).toInt()
        val bottom = kotlin.math.ceil(space.canvasToImageY(canvasHeight.toFloat())).toInt()
        return Rectangle(left, top, right - left, bottom - top).intersection(Rectangle(0, 0, imageWidth, imageHeight))
    }
    fun undo(expected: String? = null) = synchronized(lock) { writable(expected); buffer.undo(); changed() }
    fun redo(expected: String? = null) = synchronized(lock) { writable(expected); buffer.redo(); changed() }
    fun jump(index: Int, expected: String? = null) = synchronized(lock) {
        writable(expected); require(index in buffer.strokeRecords.indices) { "Paint stroke index is out of range" }
        buffer.jumpToStroke(index); changed()
    }
    fun cancel(expected: String? = null) = synchronized(lock) {
        expected?.let { if (it != token()) throw WorkspaceConflict(it, token()) }
        if (committing) throw WorkspaceBusy()
        if (!closed) { buffer.discard(); closed = true; active = false; changed() }
    }
    /** UI projection may be retired during a successful pre-CAS projection without cancelling its commit. */
    fun dismiss() = synchronized(lock) { if (!committing) cancel() }
    internal fun freeze(expected: String, rebuildMesh: Boolean, preserve: Boolean, checkpoint: () -> Unit): WorkspacePaintRaster {
        val region = synchronized(lock) {
            writable(expected); committing = true
            // Only the layer's own area and what the session wrote can hold its pixels.
            val area = space.layerArea()
            buffer.touched?.let { if (area.isEmpty) it else area.union(it) } ?: area
        }
        // While committing every write is refused, so the capture and its mesh rebuild read the raster without the
        // lock; the editor's reads of the session do not wait for them.
        return try { WorkspacePaintRaster.capture(layerId, buffer.workingImage, space, region, rebuildMesh, preserve, checkpoint) }
        catch (failure: Throwable) { synchronized(lock) { committing = false }; throw failure }
    }
    internal fun finish(result: WorkspaceMutationResult) = synchronized(lock) {
        completion = result; committing = false; closed = true; changed()
    }
    internal fun retry() = synchronized(lock) { committing = false }
    internal fun completed(): WorkspaceMutationResult? = synchronized(lock) { completion }
    fun snapshot(): JsonObject = synchronized(lock) { buildJsonObject {
        put("session_id", id); put("session_state", token()); put("project_id", projectId)
        put("workspace_state", workspaceState); put("layer_id", layerId); put("layer_name", layerName)
        materialId?.let { put("material_id", it) }
        put("width", width); put("height", height)
        put("canvas_rect", JsonArray(listOf(space.originX, space.originY, space.canvasWidth, space.canvasHeight).map(::JsonPrimitive)))
        selection?.bounds?.let { put("selection", JsonArray(listOf(it.x, it.y, it.width, it.height).map(::JsonPrimitive))) }
        put("index", buffer.currentStrokeIndex)
        put("dirty", buffer.isDirty); put("active_stroke", active); put("closed", closed)
        putJsonArray("strokes") { buffer.strokeRecords.forEach { record ->
            add(buildJsonObject { put("id", record.id); put("name", record.name) })
        } }
    } }
    /** The session's pixels for the commit, every write refused meanwhile; [retry] or [finish] ends that. */
    internal fun freezeImage(expected: String): BufferedImage = synchronized(lock) {
        writable(expected); committing = true
        copy(buffer.workingImage)
    }
    companion object {
        /** The [layerId] of a session painting material item `<id>`. */
        const val MATERIAL_PREFIX = "material:"
        /** Selection modes that change the selection as a whole; the op is for a shape meeting it. */
        private val WHOLE_SELECTION_MODES = setOf("all", "none", "invert", "feather", "expand", "contract")
        private fun copy(image: BufferedImage) = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB).also {
            it.setRGB(0, 0, image.width, image.height, image.getRGB(0, 0, image.width, image.height, null, 0, image.width), 0, image.width)
        }
    }
}

/** Process resources are captured from committed document/model once; only commit enters history. */
internal class WorkspacePaintSessions(private val runtime: WorkspaceRuntime<RigPreviewModel>) {
    private val sessions = ConcurrentHashMap<String, WorkspacePaintSession>()
    private val commands = WorkspaceRasterCommands(runtime)
    fun begin(state: String, layerId: String): WorkspacePaintSession = synchronized(sessions) {
        val captured = runtime.capture()
        if (captured.state != state) throw WorkspaceConflict(state, captured.state)
        val layer = RasterPaintCommit.sourceLayerFor(captured.model, captured.model.analysis, layerId)
            ?: throw IllegalArgumentException("Paint layer not found: $layerId")
        val painting = captured.document.layerPaintImage(layer.id.raw)
        require(sessions.values.count { !it.finished } < 32) { "Close a paint session before opening another" }
        sessions.entries.removeIf { it.value.finished }
        val session = WorkspacePaintSession(UUID.randomUUID().toString(), captured.projectId, state,
            layerId, layer.name, painting.image, painting.space,
            captured.document.source.widthPx, captured.document.source.heightPx, validateWorkspace = {
            val now = runtime.capture()
            if (now.projectId != captured.projectId || now.state != state) throw WorkspaceConflict(state, now.state)
        }, materialId = WorkspaceMaterialLinks.shared(captured.document, layer.id.raw)?.first?.id, paintsMaterial = false)
        sessions[session.id] = session
        session
    }
    /**
     * A session painting material item [materialId] at its own pixels, one pixel per unit. It stays open while other
     * edits commit: only a change to the material's own pixels, or another project, ends it.
     */
    fun beginMaterial(materialId: String): WorkspacePaintSession = synchronized(sessions) {
        val captured = runtime.capture()
        val item = captured.document.layout.materials.item(materialId) ?: throw IllegalArgumentException("Material not found: $materialId")
        val image = BufferedImage(item.raster.width, item.raster.height, BufferedImage.TYPE_INT_ARGB)
        val rgba = item.raster.rgba
        image.setRGB(0, 0, item.raster.width, item.raster.height, IntArray(item.raster.width * item.raster.height) { i ->
            val o = i * 4
            ((rgba[o + 3].toInt() and 255) shl 24) or ((rgba[o].toInt() and 255) shl 16) or ((rgba[o + 1].toInt() and 255) shl 8) or (rgba[o + 2].toInt() and 255)
        }, 0, item.raster.width)
        require(sessions.values.count { !it.finished } < 32) { "Close a paint session before opening another" }
        sessions.entries.removeIf { it.value.finished }
        var pixels = item.raster
        val session = WorkspacePaintSession(UUID.randomUUID().toString(), captured.projectId, captured.state,
            WorkspacePaintSession.MATERIAL_PREFIX + materialId, item.name, image, validateWorkspace = {
                val now = runtime.capture()
                val current = now.document.layout.materials.item(materialId)?.raster
                if (now.projectId != captured.projectId || current == null) throw WorkspaceConflict(captured.state, now.state)
                // The same pixels under a new object (an undo back to them) still match.
                if (current !== pixels) {
                    if (current.width != pixels.width || current.height != pixels.height || !current.rgba.contentEquals(pixels.rgba))
                        throw WorkspaceConflict(captured.state, now.state)
                    pixels = current
                }
            }, materialId = materialId, paintsMaterial = true)
        sessions[session.id] = session
        session
    }

    /**
     * Commits material session [id] through [apply], which writes the frozen pixels to the document (as
     * `material_set_pixels`) and returns the result; the session then closes, or opens again for writing on failure.
     */
    suspend fun commitMaterial(id: String, sessionState: String, apply: suspend (String, org.umamo.format.art.LayerRaster) -> WorkspaceMutationResult): WorkspaceMutationResult {
        val session = get(id)
        val materialId = session.materialId?.takeIf { session.paintsMaterial } ?: throw IllegalArgumentException("Not a material paint session: $id")
        session.completed()?.let { return it }
        currentCoroutineContext().ensureActive()
        val image = session.freezeImage(sessionState)
        try {
            val argb = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
            val rgba = ByteArray(argb.size * 4)
            for (i in argb.indices) {
                val p = argb[i]; val a = p ushr 24
                if (a == 0) continue
                rgba[i * 4] = (p shr 16).toByte(); rgba[i * 4 + 1] = (p shr 8).toByte(); rgba[i * 4 + 2] = p.toByte(); rgba[i * 4 + 3] = a.toByte()
            }
            val result = apply(materialId, org.umamo.format.art.LayerRaster(image.width, image.height, rgba))
            session.finish(result)
            currentCoroutineContext()[WorkspacePaintJobExecution]?.committed(result.compact())
            return result
        } catch (failure: Throwable) { session.retry(); throw failure }
    }

    fun get(id: String): WorkspacePaintSession = sessions[id] ?: throw IllegalArgumentException("Paint session not found: $id")
    fun list(): List<JsonObject> = sessions.values.map { it.snapshot() }.sortedBy { it.getValue("session_id").jsonPrimitive.content }
    fun clear() { sessions.values.forEach { runCatching { it.cancel() } }; sessions.clear() }
    suspend fun commit(id: String, sessionState: String, rebuildMesh: Boolean, preserve: Boolean, author: MutationAuthor,
        beforeCommit: (WorkspaceCapture<RigPreviewModel>, WorkspaceDocument, RigPreviewModel) -> Unit = { _, _, _ -> }): WorkspaceRasterCommit {
        val session = get(id)
        session.completed()?.let { return WorkspaceRasterCommit(WorkspaceCommit(runtime.capture(), false), it) }
        val context = currentCoroutineContext()
        context.ensureActive()
        val raster = session.freeze(sessionState, rebuildMesh, preserve) { context.ensureActive() }
        try {
            val result = commands.commitRaster(session.projectId, session.workspaceState, raster,
                "Painted source layer "+session.layerId, author, beforeCommit)
            session.finish(result.mutation)
            currentCoroutineContext()[WorkspacePaintJobExecution]?.committed(result.mutation.compact())
            return result
        } catch (failure: Throwable) { session.retry(); throw failure }
    }
}

internal class WorkspacePaintJobExecution(private val completion: WorkspaceJobCompletion) :
    kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<WorkspacePaintJobExecution>
    fun committed(result: JsonObject) { completion.committed(WorkspaceOperationOutput(result)) }
}
