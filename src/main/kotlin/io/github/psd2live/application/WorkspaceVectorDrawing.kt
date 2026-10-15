package io.github.psd2live.application

import io.github.psd2live.project.DrawingKit
import io.github.psd2live.project.LayerCanvasRect
import org.jetbrains.skia.Path
import org.jetbrains.skia.PathMeasure
import org.umamo.format.art.LayerRaster
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.StringReader
import java.io.StringWriter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Vector drawings as the renderer reads them. A drawing is SVG whose view box is its place on the canvas, in canvas
 * units; the renderer (Skia's SVG module) knows neither style sheets nor custom properties, so they are resolved here:
 *
 * - class, element and id rules from the [DrawingKit]'s styles and the drawing's own `<style>` elements are written
 *   into each element's `style`, in specificity order, the element's own style last;
 * - `var(--name)` anywhere in an attribute is the kit colour of that name;
 * - the kit's definitions join the drawing's `<defs>`, unless the drawing defines the same id;
 * - a path with `data-taper` becomes the filled outline of its stroke, the width along it scaled by the listed
 *   factors (evenly spaced from start to end), so lines can thin in and out as inked lines do;
 * - an `<ellipse>` becomes the same shape as a `<path>`: the renderer paints an ellipse's bounding-box gradient black.
 */
internal object WorkspaceVectorDrawing {
    const val TAPER = "data-taper"
    const val MAX_LENGTH = 4 * 1024 * 1024
    private const val SVG_NS = "http://www.w3.org/2000/svg"
    private val TOKEN = Regex("""var\(\s*--([A-Za-z][A-Za-z0-9_-]*)\s*\)""")
    private val SELECTOR = Regex("""^([A-Za-z][\w-]*)?(#[\w-]+)?((?:\.[\w-]+)*)$""")

    /** The drawing's place on the canvas: its view box. */
    fun viewBox(svg: String): LayerCanvasRect {
        val root = parse(svg).documentElement
        val box = root.getAttribute("viewBox").trim()
        require(box.isNotEmpty()) { "A drawing needs a viewBox: its place on the canvas in canvas units, \"left top width height\"" }
        val values = box.split(Regex("[\\s,]+")).map { it.toFloatOrNull() ?: throw IllegalArgumentException("viewBox takes four numbers: $box") }
        require(values.size == 4 && values.all(Float::isFinite) && values[2] > 0f && values[3] > 0f) { "viewBox takes left top width height, the size positive: $box" }
        return LayerCanvasRect(values[0], values[1], values[2], values[3])
    }

    /** [svg] made a canvas drawing: its view box stretched over whatever raster it renders to. */
    fun normalized(svg: String): String {
        viewBox(svg)
        val document = parse(svg)
        val root = document.documentElement
        root.removeAttribute("width"); root.removeAttribute("height")
        root.setAttribute("preserveAspectRatio", "none")
        if (!root.hasAttribute("xmlns")) root.setAttribute("xmlns", SVG_NS)
        return write(document)
    }

    /**
     * [svg] with [edits] made: each names an element by `id` and sets attributes (a null value removes one); `text`
     * replaces the element's text, for a `<style>` sheet.
     */
    fun patch(svg: String, edits: List<Pair<String, Map<String, String?>>>): String {
        val document = parse(svg)
        val byId = (document.documentElement.descendants() + document.documentElement)
            .mapNotNull { element -> element.getAttribute("id").takeIf { it.isNotEmpty() }?.let { it to element } }.toMap()
        for ((id, attributes) in edits) {
            val element = byId[id] ?: throw IllegalArgumentException("No element with id $id in the drawing")
            for ((name, value) in attributes) when {
                name == "text" -> element.textContent = value.orEmpty()
                value == null -> element.removeAttribute(name)
                else -> {
                    require(name.matches(Regex("[A-Za-z_][A-Za-z0-9_.:-]*")) && name != "id") { "Not an attribute to set: $name" }
                    element.setAttribute(name, value)
                }
            }
        }
        return write(document)
    }

    /** [svg] rendered over [width] x [height] pixels with [kit]. */
    fun render(svg: String, kit: DrawingKit?, width: Int, height: Int): LayerRaster =
        WorkspaceMaterialFiles.rasterize(resolve(svg, kit), width, height)

    /** [svg] with its rules, tokens, kit definitions and tapered strokes resolved, as the renderer reads it. */
    fun resolve(svg: String, kit: DrawingKit?): String {
        val document = parse(svg)
        val root = document.documentElement
        val elements = root.descendants()
        val sheets = elements.filter { it.tagName == "style" }
        val rules = rules(kit?.styles.orEmpty()) + sheets.flatMap { rules(it.textContent) }
        sheets.forEach { it.parentNode.removeChild(it) }
        kit?.defs?.takeIf { it.isNotBlank() }?.let { addDefinitions(document, it) }
        val all = root.descendants() + root
        if (rules.isNotEmpty()) for (element in all) inlineRules(element, rules)
        val unknown = LinkedHashSet<String>()
        for (element in all) {
            val attributes = element.attributes
            for (index in 0 until attributes.length) {
                val attribute = attributes.item(index)
                if (!attribute.nodeValue.contains("var(")) continue
                attribute.nodeValue = TOKEN.replace(attribute.nodeValue) { match ->
                    kit?.colors?.get(match.groupValues[1]) ?: match.value.also { unknown += match.groupValues[1] }
                }
            }
        }
        require(unknown.isEmpty()) { "Colours not in the drawing kit: ${unknown.joinToString { "--$it" }}; layout_set_drawing_kit adds them" }
        for (element in all.filter { it.tagName == "ellipse" }) ellipsePath(document, element)
        for (element in root.descendants().filter { it.tagName == "path" && it.hasAttribute(TAPER) }) taper(element)
        return write(document)
    }

    private fun parse(svg: String): Document {
        require(svg.length <= MAX_LENGTH) { "A drawing is at most ${MAX_LENGTH / 1024 / 1024} MB of SVG" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            isXIncludeAware = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        }
        val document = try {
            factory.newDocumentBuilder().apply { setErrorHandler(null) }.parse(org.xml.sax.InputSource(StringReader(svg)))
        } catch (e: org.xml.sax.SAXException) {
            throw IllegalArgumentException("Not well-formed SVG: ${e.message}")
        }
        require(document.documentElement.tagName == "svg") { "A drawing's root element is <svg>" }
        return document
    }

    private fun write(document: Document): String = StringWriter().also { out ->
        TransformerFactory.newInstance().newTransformer().apply { setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes") }
            .transform(DOMSource(document), StreamResult(out))
    }.toString()

    private fun Element.descendants(): List<Element> {
        val out = ArrayList<Element>()
        fun visit(node: org.w3c.dom.Node) {
            val children = node.childNodes
            for (index in 0 until children.length) (children.item(index) as? Element)?.let { out += it; visit(it) }
        }
        visit(this)
        return out
    }

    /** One rule of a style sheet: a compound selector of an element name, an id and classes. */
    private class Rule(val tag: String?, val id: String?, val classes: Set<String>, val declarations: List<Pair<String, String>>, val order: Int) {
        val specificity = (if (id != null) 100 else 0) + classes.size * 10 + (if (tag != null) 1 else 0)
        fun matches(element: Element): Boolean = (tag == null || element.tagName == tag) &&
            (id == null || element.getAttribute("id") == id) &&
            (classes.isEmpty() || element.getAttribute("class").split(Regex("\\s+")).toSet().containsAll(classes))
    }

    private fun rules(sheet: String): List<Rule> {
        val text = sheet.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
        val out = ArrayList<Rule>()
        val block = Regex("([^{}]+)\\{([^{}]*)\\}")
        var consumed = 0
        for (match in block.findAll(text)) {
            require(text.substring(consumed, match.range.first).isBlank()) { "Unsupported style sheet text: ${text.substring(consumed, match.range.first).trim().take(60)}" }
            consumed = match.range.last + 1
            val declarations = declarations(match.groupValues[2])
            for (selector in match.groupValues[1].split(',').map { it.trim() }) {
                val parts = SELECTOR.matchEntire(selector)
                    ?: throw IllegalArgumentException("Style rules take element, #id and .class selectors, combined but not nested: $selector")
                out += Rule(parts.groupValues[1].ifEmpty { null }, parts.groupValues[2].removePrefix("#").ifEmpty { null },
                    parts.groupValues[3].split('.').filter { it.isNotEmpty() }.toSet(), declarations, out.size)
            }
        }
        require(text.substring(consumed).isBlank()) { "Unsupported style sheet text: ${text.substring(consumed).trim().take(60)}" }
        return out
    }

    private fun declarations(text: String): List<Pair<String, String>> = text.split(';').mapNotNull { part ->
        val colon = part.indexOf(':')
        if (part.isBlank()) null else {
            require(colon > 0) { "A style declaration is name: value: ${part.trim()}" }
            part.substring(0, colon).trim().lowercase() to part.substring(colon + 1).trim()
        }
    }

    private fun inlineRules(element: Element, rules: List<Rule>) {
        val matched = rules.filter { it.matches(element) }.sortedWith(compareBy({ it.specificity }, { it.order }))
        if (matched.isEmpty()) return
        val style = LinkedHashMap<String, String>()
        matched.forEach { rule -> rule.declarations.forEach { (name, value) -> style.remove(name); style[name] = value } }
        declarations(element.getAttribute("style")).forEach { (name, value) -> style.remove(name); style[name] = value }
        element.setAttribute("style", style.entries.joinToString(";") { "${it.key}:${it.value}" })
    }

    private fun addDefinitions(document: Document, defs: String) {
        val kit = try {
            parse("<svg xmlns=\"$SVG_NS\" xmlns:xlink=\"http://www.w3.org/1999/xlink\">$defs</svg>")
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("The drawing kit's defs are not well-formed SVG: ${e.message}")
        }
        val root = document.documentElement
        val ids = root.descendants().mapNotNullTo(HashSet()) { element -> element.getAttribute("id").takeIf { it.isNotEmpty() } }
        val holder = document.createElement("defs")
        val children = kit.documentElement.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index) as? Element ?: continue
            if (child.getAttribute("id").let { it.isNotEmpty() && it in ids }) continue
            holder.appendChild(document.importNode(child, true))
        }
        if (holder.hasChildNodes()) root.insertBefore(holder, root.firstChild)
    }

    /** [element], an ellipse, replaced by a path of two arcs with its other attributes. */
    private fun ellipsePath(document: Document, element: Element) {
        fun number(name: String) = element.getAttribute(name).removeSuffix("px").trim().toFloatOrNull()
        val cx = number("cx") ?: 0f; val cy = number("cy") ?: 0f
        val rx = number("rx") ?: number("ry") ?: 0f; val ry = number("ry") ?: rx
        val path = document.createElement("path")
        val attributes = element.attributes
        for (index in 0 until attributes.length) {
            val attribute = attributes.item(index)
            if (attribute.nodeName !in setOf("cx", "cy", "rx", "ry")) path.setAttribute(attribute.nodeName, attribute.nodeValue)
        }
        if (rx > 0f && ry > 0f) path.setAttribute("d", "M${fmt(cx - rx)} ${fmt(cy)}A${fmt(rx)} ${fmt(ry)} 0 1 0 ${fmt(cx + rx)} ${fmt(cy)}" +
            "A${fmt(rx)} ${fmt(ry)} 0 1 0 ${fmt(cx - rx)} ${fmt(cy)}Z")
        while (element.hasChildNodes()) path.appendChild(element.firstChild)
        element.parentNode.replaceChild(path, element)
    }

    /** The value of presentation property [name] on [element] or the nearest ancestor setting it, style before attribute. */
    private fun property(element: Element, name: String): String? {
        var node: org.w3c.dom.Node? = element
        while (node is Element) {
            declarations(node.getAttribute("style")).lastOrNull { it.first == name }?.let { return it.second }
            node.getAttribute(name).takeIf { it.isNotEmpty() }?.let { return it }
            node = node.parentNode
        }
        return null
    }

    private fun taper(element: Element) {
        val profile = element.getAttribute(TAPER).split(Regex("[\\s,]+")).filter { it.isNotEmpty() }.map {
            it.toFloatOrNull()?.takeIf { value -> value.isFinite() && value in 0f..16f } ?: throw IllegalArgumentException("$TAPER takes width factors 0..16: ${element.getAttribute(TAPER)}")
        }
        require(profile.isNotEmpty()) { "$TAPER takes at least one width factor" }
        val paint = property(element, "stroke")?.takeIf { it != "none" } ?: throw IllegalArgumentException("A tapered path needs a stroke colour")
        val width = property(element, "stroke-width")?.removeSuffix("px")?.toFloatOrNull()?.takeIf { it > 0f && it.isFinite() } ?: 1f
        val outline = taperOutline(element.getAttribute("d"), width, profile.toFloatArray())
        val style = LinkedHashMap<String, String>()
        declarations(element.getAttribute("style")).forEach { (name, value) -> style[name] = value }
        val opacity = property(element, "stroke-opacity")
        listOf("stroke-width", "stroke-opacity", "stroke-linecap", "stroke-linejoin", "stroke-dasharray").forEach { style.remove(it); element.removeAttribute(it) }
        style["fill"] = paint; style["stroke"] = "none"; style["fill-rule"] = "nonzero"
        opacity?.let { style["fill-opacity"] = it } ?: style.remove("fill-opacity")
        element.removeAttribute("fill"); element.removeAttribute("stroke"); element.removeAttribute(TAPER)
        element.setAttribute("style", style.entries.joinToString(";") { "${it.key}:${it.value}" })
        element.setAttribute("d", outline)
    }

    /** The outline of a stroke along [d], [width] wide scaled by [profile] from start to end of each subpath. */
    fun taperOutline(d: String, width: Float, profile: FloatArray): String {
        val path = try { Path.makeFromSVGString(d) } catch (e: IllegalArgumentException) { throw IllegalArgumentException("Not SVG path data: ${d.take(60)}") }
        val out = StringBuilder()
        fun factor(t: Float): Float {
            if (profile.size == 1) return profile[0]
            val position = t.coerceIn(0f, 1f) * (profile.size - 1)
            val index = floor(position).toInt().coerceAtMost(profile.size - 2)
            val fraction = position - index
            return profile[index] + (profile[index + 1] - profile[index]) * fraction
        }
        fun emit(points: List<FloatArray>) {
            points.forEachIndexed { index, point -> out.append(if (index == 0) "M" else "L").append(fmt(point[0])).append(' ').append(fmt(point[1])) }
            out.append('Z')
        }
        path.use {
            PathMeasure(path, false, 1f).use { measure ->
                do {
                    val length = measure.length
                    if (length <= 0f) continue
                    val step = (width / 4f).coerceIn(0.05f, 1f).coerceAtLeast(length / 4000f)
                    val count = ceil(length / step).toInt().coerceIn(2, 4000)
                    val left = ArrayList<FloatArray>(count + 1); val right = ArrayList<FloatArray>(count + 1)
                    for (index in 0..count) {
                        val t = index.toFloat() / count
                        val distance = (length * t).coerceIn(0f, length)
                        val position = measure.getPosition(distance) ?: continue
                        val tangent = measure.getTangent(distance) ?: continue
                        val norm = hypot(tangent.x, tangent.y).takeIf { it > 0f } ?: continue
                        val nx = -tangent.y / norm; val ny = tangent.x / norm
                        val half = width * factor(t) / 2f
                        left += floatArrayOf(position.x + nx * half, position.y + ny * half)
                        right += floatArrayOf(position.x - nx * half, position.y - ny * half)
                    }
                    if (left.size < 2) continue
                    if (measure.isClosed) { emit(left); emit(right.reversed()) } else emit(left + right.reversed())
                } while (measure.nextContour())
            }
        }
        return out.toString()
    }

    private fun fmt(value: Float): String {
        val rounded = (value * 1000f).roundToInt() / 1000.0
        return if (rounded == floor(rounded)) rounded.toLong().toString() else rounded.toString()
    }
}
