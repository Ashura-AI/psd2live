package io.github.psd2live.ui.views

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.IconMeshWireframe
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.theme.Motion
import io.github.psd2live.ui.views.texture.BarTooltipPanel
import org.umamo.runtime.model.Drawable

internal data class MaterialUsageEntry(val layerId: String, val name: String, val drawable: Drawable?)

/** A source layer can own several ArtMeshes; pending layers still have a named entry. */
internal fun materialUsageEntries(layerIds: List<String>, drawables: List<Drawable>, layerByDrawable: Map<String, String>,
                                  layerNames: Map<String, String>): List<MaterialUsageEntry> {
    val byLayer = drawables.filter { it.mesh != null }.groupBy { layerByDrawable[it.id.raw] }
    return layerIds.distinct().flatMap { id ->
        byLayer[id]?.map { MaterialUsageEntry(id, it.name, it) }
            ?: listOf(MaterialUsageEntry(id, layerNames[id] ?: id, null))
    }
}

/** A quiet, legible usage count on either a material thumbnail or a list row. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MaterialUsageBadge(state: PSD2LiveState, layerIds: List<String>, modifier: Modifier = Modifier) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val fill by animateColorAsState(colors.accent.copy(alpha = if (hovered) 0.18f else 0.08f), tween(Motion.Instant))
    val label = tr("materials.references", layerIds.size)
    TooltipArea(modifier = modifier, delayMillis = Motion.TooltipDelay, tooltip = {
        BarTooltipPanel { MaterialUsagePreview(state, layerIds) }
    }) {
        Row(Modifier.heightIn(min = 18.dp).clip(RoundedCornerShape(5.dp))
            .background(colors.panelElevated).background(fill)
            .border(0.5.dp, colors.accent.copy(alpha = if (hovered) 0.7f else 0.35f), RoundedCornerShape(5.dp))
            .hoverable(interaction).semantics { contentDescription = label }
            .padding(horizontal = 4.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            IconMeshWireframe(colors.accent, Modifier.size(11.dp))
            Text(layerIds.size.toString(), color = colors.textPrimary, style = typography.monoSmall)
        }
    }
}

@Composable
internal fun MaterialUsagePreview(state: PSD2LiveState, layerIds: List<String>) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val model = state.previewModel
    val entries = remember(model, state.analysis?.source, layerIds) {
        materialUsageEntries(layerIds, model?.rig?.puppet?.drawables.orEmpty(), model?.rig?.layerIdByDrawableId.orEmpty(),
            state.analysis?.source?.layers?.associate { it.id.raw to it.name }.orEmpty())
    }
    val columns = if (entries.size == 1) 1 else 2
    val side = if (columns == 1) 160.dp else 104.dp
    Column(Modifier.width(side * columns + 22.dp).padding(7.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(tr("materials.meshPreview", entries.count { it.drawable != null }), style = typography.caption, color = colors.textPrimary)
        Text(tr("materials.references", layerIds.size), style = typography.caption, color = colors.textMuted)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            items(entries.chunked(columns)) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { entry ->
                        Column(Modifier.width(side), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            val image = if (model != null && entry.drawable != null) rememberMeshThumbnail(model, entry.drawable) else null
                            Box(Modifier.size(side).clip(RoundedCornerShape(3.dp)).background(colors.checkerDark)
                                .border(0.5.dp, colors.border, RoundedCornerShape(3.dp)), contentAlignment = Alignment.Center) {
                                if (image != null) Image(image, entry.name, Modifier.fillMaxSize().padding(5.dp), contentScale = ContentScale.Fit)
                                else IconMeshWireframe(colors.textMuted, Modifier.size(24.dp))
                            }
                            Text(entry.name, style = typography.caption, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(entry.drawable?.id?.raw ?: tr("materials.meshPreview.pending"), style = typography.monoSmall,
                                color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
