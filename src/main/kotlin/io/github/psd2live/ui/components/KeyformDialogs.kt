package io.github.psd2live.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.psd2live.core.KeyformTools
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.CanvasEditor
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import kotlinx.serialization.json.*

/** The keyform dialogs a Deform canvas opens from its context menu, shown over the workspace by [KeyformDialogHost]. */
internal object KeyformDialogs {
    sealed class Request(val editor: CanvasEditor) {
        /** Paste with options: what to carry, a blend ratio, a flip, and which copied shape goes onto which object. */
        class Paste(editor: CanvasEditor) : Request(editor)
        /** Opacity, draw order and colours of many keyforms of the objects in hand at once. */
        class MultiKey(editor: CanvasEditor) : Request(editor)
    }

    var request by mutableStateOf<Request?>(null)
}

@Composable
internal fun KeyformDialogHost() {
    when (val request = KeyformDialogs.request) {
        is KeyformDialogs.Request.Paste -> KeyformPasteDialog(request.editor) { KeyformDialogs.request = null }
        is KeyformDialogs.Request.MultiKey -> MultiKeyDialog(request.editor) { KeyformDialogs.request = null }
        null -> Unit
    }
}

private val CHANNEL_LABELS = listOf("geometry" to "keyform.paste.geometry") + KeyformTools.CHANNELS.keys.map { it to "keyform.channel.$it" }

@Composable
private fun KeyformPasteDialog(editor: CanvasEditor, onDismiss: () -> Unit) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    var channels by remember { mutableStateOf(setOf("geometry")) }
    var ratio by remember { mutableStateOf(1f) }
    var flip by remember { mutableStateOf<String?>(null) }
    val destinations = remember { editor.keyformPasteDestinations() }
    var pairs by remember { mutableStateOf(editor.keyformPastePairs()) }
    val options = CanvasEditor.KeyformPasteOptions(channels, ratio, flip)
    val commands = editor.keyformPasteCommands(pairs, options)
    val paste = { editor.runKeyformCommands(commands); onDismiss() }
    ModalDialogFrame(
        title = tr("keyform.paste.title"),
        onDismiss = onDismiss,
        width = 440.dp,
        onConfirm = { if (commands.isNotEmpty()) paste() },
        footer = {
            CompactButton(tr("action.cancel"), onDismiss)
            CompactButton(tr("keyform.paste.confirm"), paste, enabled = commands.isNotEmpty(), isPrimary = true)
        },
    ) {
        Text(tr("keyform.paste.contents"), style = typography.caption, color = colors.textMuted)
        for ((name, label) in CHANNEL_LABELS) CompactCheckbox(name in channels, { on -> channels = if (on) channels + name else channels - name }, label = tr(label))
        Text(tr("keyform.paste.ratio"), style = typography.caption, color = colors.textMuted)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactSlider(ratio, { ratio = (it * 100).toInt() / 100f }, modifier = Modifier.weight(1f))
            Text("${(ratio * 100).toInt()}%", style = typography.body, color = colors.textPrimary, modifier = Modifier.width(40.dp))
        }
        Text(tr("keyform.paste.ratioHint"), style = typography.caption, color = colors.textMuted)
        Text(tr("keyform.paste.flip"), style = typography.caption, color = colors.textMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for ((value, label) in listOf(null to "keyform.paste.flipNone", "horizontal" to "keyform.paste.flipHorizontal", "vertical" to "keyform.paste.flipVertical"))
                CompactRadioButton(flip == value, { flip = value }, label = tr(label))
        }
        Text(tr("keyform.paste.targets"), style = typography.caption, color = colors.textMuted)
        if (destinations.isEmpty()) Text(tr("keyform.paste.noTarget"), style = typography.caption, color = colors.warning)
        pairs.forEachIndexed { index, (clip, destination) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(clip.label, style = typography.body, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                Text("→", style = typography.body, color = colors.textMuted)
                CompactDropdown(items = listOf<String?>(null) + destinations.filter { editor.keyformPasteFits(clip, it) }, selectedItem = destination,
                    onItemSelected = { chosen -> pairs = pairs.mapIndexed { i, pair -> if (i == index) pair.first to chosen else pair } },
                    itemLabel = { it?.let(editor::objectLabel) ?: tr("keyform.paste.skip") }, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MultiKeyDialog(editor: CanvasEditor, onDismiss: () -> Unit) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val targets = remember { editor.multiKeyTargets() }
    val axes = remember { editor.multiKeyAxes(targets) }
    var ranges by remember { mutableStateOf(axes.mapValues { it.value.toSet() }) }
    var opacity by remember { mutableStateOf<Float?>(null) }
    var drawOrder by remember { mutableStateOf<Float?>(null) }
    var multiply by remember { mutableStateOf<List<Float>?>(null) }
    var screen by remember { mutableStateOf<List<Float>?>(null) }
    var method by remember { mutableStateOf("overwrite") }
    val deformers = targets.any { !it.startsWith("mesh:") }
    val count = editor.multiKeyCount(targets, ranges)
    val command = buildJsonObject {
        put("op", KeyformTools.MULTI_KEY); put("targets", JsonArray(targets.map(::JsonPrimitive)))
        put("ranges", JsonObject(ranges.mapValues { (_, values) -> JsonArray(values.sorted().map(::JsonPrimitive)) }))
        opacity?.let { put("opacity", it) }
        drawOrder?.let { put("drawOrder", it) }
        multiply?.let { put("multiplyColor", JsonArray(it.map(::JsonPrimitive))) }
        screen?.let { put("screenColor", JsonArray(it.map(::JsonPrimitive))) }
        put("method", method)
    }
    val ready = count > 0 && listOf(opacity, drawOrder, multiply, screen).any { it != null } && !(deformers && drawOrder != null)
    val apply = { editor.runKeyformCommands(listOf(command)); onDismiss() }
    ModalDialogFrame(
        title = tr("keyform.multi.title"),
        subtitle = tr("keyform.multi.objects", targets.joinToString { editor.objectLabel(it.substringAfter(':')) }),
        onDismiss = onDismiss,
        width = 460.dp,
        onConfirm = { if (ready) apply() },
        footer = {
            CompactButton(tr("action.cancel"), onDismiss)
            CompactButton(tr("keyform.multi.apply"), apply, enabled = ready, isPrimary = true)
        },
    ) {
        Text(tr("keyform.multi.range"), style = typography.caption, color = colors.textMuted)
        for ((id, values) in axes) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(editor.parameterLabel(id), style = typography.body, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(120.dp))
            for (value in values) {
                val on = value in ranges.getValue(id)
                CompactToggleChip(if (value == kotlin.math.round(value)) value.toInt().toString() else "%.2f".format(java.util.Locale.ROOT, value), on, {
                    ranges = ranges + (id to if (on) ranges.getValue(id) - value else ranges.getValue(id) + value)
                }, showCheckWhenSelected = false)
            }
        }
        Text(tr("keyform.multi.count", count), style = typography.caption, color = if (count == 0) colors.warning else colors.textMuted)
        Text(tr("keyform.multi.contents"), style = typography.caption, color = colors.textMuted)
        CompactCheckbox(opacity != null, { opacity = if (it) 1f else null }, label = tr("keyform.channel.opacity"))
        opacity?.let { value ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactSlider(value, { opacity = (it * 100).toInt() / 100f }, modifier = Modifier.weight(1f))
                Text("${(value * 100).toInt()}%", style = typography.body, color = colors.textPrimary, modifier = Modifier.width(40.dp))
            }
        }
        CompactCheckbox(drawOrder != null, { drawOrder = if (it) 500f else null }, label = tr("keyform.channel.drawOrder"))
        drawOrder?.let { value -> CompactNumberSpinner(value.toDouble(), { drawOrder = it.toFloat() }, min = 0.0, max = 1000.0, modifier = Modifier.width(120.dp)) }
        if (deformers && drawOrder != null) Text(tr("keyform.multi.noDrawOrder"), style = typography.caption, color = colors.warning)
        for ((label, get, set, identity) in listOf(
            ColorRow("keyform.channel.multiplyColor", { multiply }, { multiply = it }, listOf(1f, 1f, 1f)),
            ColorRow("keyform.channel.screenColor", { screen }, { screen = it }, listOf(0f, 0f, 0f)),
        )) {
            CompactCheckbox(get() != null, { set(if (it) identity else null) }, label = tr(label))
            get()?.let { color ->
                for ((component, name) in listOf("R", "G", "B").withIndex()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(name, style = typography.caption, color = colors.textMuted, modifier = Modifier.width(12.dp))
                    CompactSlider(color[component], { v -> set(color.toMutableList().also { it[component] = (v * 100).toInt() / 100f }) }, modifier = Modifier.weight(1f))
                    Text("${(color[component] * 100).toInt()}%", style = typography.body, color = colors.textPrimary, modifier = Modifier.width(40.dp))
                }
            }
        }
        Text(tr("keyform.multi.method"), style = typography.caption, color = colors.textMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (choice in listOf("overwrite", "add", "multiply", "screen")) CompactRadioButton(method == choice, { method = choice }, label = tr("keyform.multi.$choice"))
        }
    }
}

private data class ColorRow(val label: String, val get: () -> List<Float>?, val set: (List<Float>?) -> Unit, val identity: List<Float>)
