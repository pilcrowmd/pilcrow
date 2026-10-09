// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.outlined.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.HorizontalRule
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.StrikethroughS
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.pilcrowmd.domain.markdown.FormatAction
import com.pilcrowmd.ui.theme.mdColors

/** One formatting button: what it does, its glyph and its TalkBack label. */
private class FormatButton(val action: FormatAction, val icon: ImageVector, val label: String)

private val MAIN_BUTTONS = listOf(
    FormatButton(FormatAction.Bold, Icons.Outlined.FormatBold, "Bold"),
    FormatButton(FormatAction.Italic, Icons.Outlined.FormatItalic, "Italic"),
    FormatButton(FormatAction.Heading, Icons.Outlined.Title, "Heading"),
    FormatButton(FormatAction.BulletList, Icons.AutoMirrored.Outlined.FormatListBulleted, "Bulleted list"),
    FormatButton(FormatAction.Checkbox, Icons.Outlined.CheckBox, "Checkbox"),
    FormatButton(FormatAction.Link, Icons.Outlined.Link, "Link"),
)

private val MORE_BUTTONS = listOf(
    FormatButton(FormatAction.InlineCode, Icons.Outlined.Code, "Inline code"),
    FormatButton(FormatAction.Quote, Icons.Outlined.FormatQuote, "Quote"),
    FormatButton(FormatAction.Table, Icons.Outlined.TableChart, "Table"),
    FormatButton(FormatAction.Strikethrough, Icons.Outlined.StrikethroughS, "Strikethrough"),
    FormatButton(FormatAction.Divider, Icons.Outlined.HorizontalRule, "Divider"),
    FormatButton(FormatAction.Outdent, Icons.AutoMirrored.Outlined.FormatIndentDecrease, "Outdent"),
    FormatButton(FormatAction.Indent, Icons.AutoMirrored.Outlined.FormatIndentIncrease, "Indent"),
)

/**
 * The editor's formatting bar, right above the keyboard (**M-217**). One row: Bold, Italic, Heading,
 * Bulleted list, Checkbox, Link, then Undo, Redo and More at the end. More opens a second row above
 * it with the rest. It only reports taps; `CodeEditor.applyFormat` does the editing.
 *
 * When the screen is too narrow for every button, the formatting buttons scroll sideways and Undo,
 * Redo and More stay in place, so no button is ever cut off. Colours from the token layer only
 * (Safeguard 4).
 */
@Composable
fun FormattingBar(
    onAction: (FormatAction) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = mdColors()
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth().background(c.secondarySurface)) {
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(c.toolbarBorder),
        )
        if (expanded) {
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                MORE_BUTTONS.forEach { BarButton(it.icon, it.label) { onAction(it.action) } }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                MAIN_BUTTONS.forEach { BarButton(it.icon, it.label) { onAction(it.action) } }
            }
            BarButton(Icons.AutoMirrored.Outlined.Undo, "Undo", onUndo)
            BarButton(Icons.AutoMirrored.Outlined.Redo, "Redo", onRedo)
            MoreButton(expanded = expanded, onClick = { expanded = !expanded })
        }
    }
}

/** A 42dp touch target with a 22dp glyph, the same scale as the top toolbar's buttons. */
@Composable
private fun BarButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(42.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = mdColors().primaryText,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * More: an accent chip with an on-accent glyph while the second row is open, like the mode toggle.
 * The chip is drawn behind the button rather than inside it (IconButton clips its content to a
 * circle), so the chip keeps its shape and the touch target stays 42dp.
 */
@Composable
private fun MoreButton(expanded: Boolean, onClick: () -> Unit) {
    val c = mdColors()
    Box(modifier = Modifier.size(42.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (expanded) c.accent else Color.Transparent),
        )
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(42.dp)
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
        ) {
            Icon(
                imageVector = Icons.Outlined.MoreHoriz,
                contentDescription = "More formatting",
                tint = if (expanded) c.onAccent else c.primaryText,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
