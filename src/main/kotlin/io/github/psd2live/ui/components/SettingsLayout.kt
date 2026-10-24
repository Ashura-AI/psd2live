package io.github.psd2live.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography

/*
 * The settings page's layout, flat as a preferences page: a group is a heading over a rule, its rows a label column
 * and a control column, and what a row explains sits under its control. Nothing is boxed; only what shows something
 * of its own (a theme preview, a code snippet) gets a surface.
 */

/** The label column, wide enough for the longest label in the four languages ("Background frame rate"). */
internal val SettingsLabelWidth = 156.dp

/** A control's height, which a row's label is centred on. */
private val SettingsControlHeight = 24.dp

/** A heading and its rule over the rows of one group, [spacing] apart; [actions] sit at the heading's end. */
@Composable
internal fun SettingsGroup(
	title: String,
	spacing: Dp = 10.dp,
	actions: (@Composable RowScope.() -> Unit)? = null,
	content: @Composable ColumnScope.() -> Unit,
) {
	val colors = LocalToolColors.current
	Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
		Row(
			Modifier.fillMaxWidth().heightIn(min = 22.dp).padding(bottom = 4.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(6.dp),
		) {
			Text(
				title,
				style = LocalToolTypography.current.header.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
				color = colors.textPrimary,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f),
			)
			actions?.invoke(this)
		}
		Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
		Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(spacing), content = content)
	}
}

/**
 * One setting: [label] in the label column, centred on the control's first line, and [control] beside it, with
 * [description] under the control.
 */
@Composable
internal fun SettingsRow(
	label: String,
	modifier: Modifier = Modifier,
	description: String? = null,
	control: @Composable ColumnScope.() -> Unit,
) {
	val colors = LocalToolColors.current
	Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
		Box(Modifier.width(SettingsLabelWidth).heightIn(min = SettingsControlHeight).padding(end = 12.dp), contentAlignment = Alignment.CenterStart) {
			Text(label, style = LocalToolTypography.current.caption.copy(fontSize = 11.5.sp), color = colors.textPrimary,
				maxLines = 2, overflow = TextOverflow.Ellipsis)
		}
		Column(Modifier.weight(1f)) {
			Column(Modifier.fillMaxWidth().heightIn(min = SettingsControlHeight), verticalArrangement = Arrangement.Center, content = control)
			if (description != null) SettingsHint(description, indent = false, modifier = Modifier.padding(top = 3.dp))
		}
	}
}

/** A line of explanation in the caption style; [indent] lines it up with the controls of the rows around it. */
@Composable
internal fun SettingsHint(text: String, modifier: Modifier = Modifier, color: Color = LocalToolColors.current.textMuted, indent: Boolean = true) {
	Text(
		text,
		style = LocalToolTypography.current.caption.copy(fontSize = 10.5.sp, lineHeight = 14.sp),
		color = color,
		modifier = modifier.then(if (indent) Modifier.padding(start = SettingsLabelWidth) else Modifier),
	)
}

/** Content without a label of its own, lined up with the controls of the rows around it. */
@Composable
internal fun SettingsIndented(content: @Composable ColumnScope.() -> Unit) {
	Column(Modifier.fillMaxWidth().padding(start = SettingsLabelWidth), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
}
