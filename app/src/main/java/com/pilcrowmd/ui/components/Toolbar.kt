// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RemoveRedEye
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.pilcrowmd.R
import com.pilcrowmd.ui.theme.PilcrowSpacing
import com.pilcrowmd.ui.theme.mdColors
import com.pilcrowmd.viewmodel.ViewMode

/**
 * Top toolbar (44dp) with a 1px bottom border (toolbarBorder token).
 *
 * Left: segmented Reader/Editor toggle — two icon buttons (eye = Reader,
 * </> = Editor); the active mode is highlighted.
 * Right: explicit Save (no auto-save), Search, TOC, Export/Undo/Redo and
 * Close. Opening/switching a file is done from the Home screen: the
 * folder icon was removed so the remaining actions can hit a 42dp touch target
 * within phone width; Close returns to Home, where Open lives.
 */
@Composable
fun PilcrowToolbar(
    modifier: Modifier = Modifier,
    currentMode: ViewMode = ViewMode.READER,
    onModeSelected: (ViewMode) -> Unit = {},
    onSave: () -> Unit = {},
    onSaveACopy: () -> Unit = {},
    onClose: () -> Unit = {},
    onSearch: () -> Unit = {},
    onTOC: () -> Unit = {},
    onExportPdf: () -> Unit = {},
    isSaving: Boolean = false,
    onUndo: (() -> Unit)? = null,
    onRedo: (() -> Unit)? = null,
) {
    val c = mdColors()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(c.primaryBackground),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(43.dp)
                .padding(horizontal = PilcrowSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Segmented Reader/Editor toggle (left)
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(c.secondarySurface),
            ) {
                SegmentIcon(
                    icon = Icons.Outlined.RemoveRedEye,
                    contentDescription = "Reader",
                    selected = currentMode == ViewMode.READER,
                    onClick = { onModeSelected(ViewMode.READER) },
                )
                SegmentIcon(
                    icon = Icons.Outlined.Code,
                    contentDescription = "Editor",
                    selected = currentMode == ViewMode.EDITOR,
                    onClick = { onModeSelected(ViewMode.EDITOR) },
                )
            }

            // Actions (right): Search + TOC + Save + Export PDF + Undo + Redo, then Close.
            // Icons are 22dp glyphs (PDF export 24dp so its "PDF" lettering stays legible) inside 42dp
            // touch targets (glyph bumped from 18dp so the icons read visibly larger; target
            // stays 42dp). The Open folder icon was removed (open from Home) so the full
            // set fits on a phone width with a 42dp tap target and Close (X) is pinned at the far right.
            // Search and TOC now work in both READER and EDITOR modes.
            Row(verticalAlignment = Alignment.CenterVertically) {
                ActionIcon(
                    icon = Icons.Outlined.Search,
                    contentDescription = "Search",
                    onClick = onSearch,
                    enabled = !isSaving,
                )
                ActionIcon(
                    icon = Icons.AutoMirrored.Outlined.MenuBook,
                    contentDescription = "Table of contents",
                    onClick = onTOC,
                    enabled = !isSaving,
                )
                ActionIcon(
                    icon = Icons.Outlined.Save,
                    contentDescription = if (isSaving) "Saving" else "Save",
                    onClick = onSave,
                    enabled = !isSaving,
                    tint = if (isSaving) {
                        c.secondaryText
                    } else {
                        c.primaryText
                    },
                )
                // Export PDF button (preview mode only) — custom PDF icon with brand-purple badge.
                if (currentMode == ViewMode.READER) {
                    PdfExportActionIcon(
                        onClick = onExportPdf,
                        enabled = !isSaving,
                    )
                }
                // Undo button (editor mode only, if onUndo is provided)
                if (onUndo != null) {
                    ActionIcon(
                        icon = Icons.AutoMirrored.Outlined.Undo,
                        contentDescription = "Undo",
                        onClick = onUndo,
                    )
                }
                // Redo button (editor mode only, if onRedo is provided)
                if (onRedo != null) {
                    ActionIcon(
                        icon = Icons.AutoMirrored.Outlined.Redo,
                        contentDescription = "Redo",
                        onClick = onRedo,
                    )
                }
                // Overflow menu (⋮) — hosts "Save a copy" (Save-As). Sits left of Close.
                OverflowMenu(onSaveACopy = onSaveACopy, enabled = !isSaving)
                // Settings moved off the toolbar to the Welcome screen.
                // Close (X) — always the rightmost action.
                ActionIcon(
                    icon = Icons.Outlined.Close,
                    contentDescription = "Close file",
                    onClick = onClose,
                    enabled = !isSaving,
                    tint = c.secondaryText,
                )
            }
        }

        // 1px bottom border (toolbarBorder token, Safeguard 4)
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(c.toolbarBorder),
        )
    }
}

/**
 * A toolbar action button sized to match the segment toggle: a 42dp touch target
 * (up from 36dp for reliable finger taps) with a 22dp icon (up
 * from 18dp so icons read visibly larger), so every icon in the bar reads at the same scale.
 */
@Composable
private fun ActionIcon(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = mdColors().primaryText,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(42.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * Export-PDF toolbar button. The custom PDF line-art (ic_pdf_export) is rendered as a
 * tinted [Icon] so its page/fold/"PDF" strokes follow the in-app theme exactly like the neighbouring
 * outlined icons; the fixed brand-purple download badge (ic_pdf_export_badge) is overlaid as an
 * untinted [Image] so a blanket tint can't flatten its colour. 42dp target / 24dp glyph (2dp larger
 * than the 22dp neighbours so the "PDF" lettering stays legible). Two layers are required
 * because the app themes via Compose mdColors(), not Android
 * night mode, so a single untinted asset cannot theme its strokes.
 */
@Composable
private fun PdfExportActionIcon(onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(42.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_pdf_export),
                contentDescription = "Export PDF",
                tint = mdColors().primaryText,
                modifier = Modifier.size(24.dp),
            )
            Image(
                painter = painterResource(R.drawable.ic_pdf_export_badge),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * Toolbar overflow (⋮) menu. Owns its own expanded state; currently hosts the "Save a copy" (Save-As)
 * action. A 42dp target / 22dp glyph to match the neighbouring [ActionIcon]s; the menu anchors to it.
 */
@Composable
private fun OverflowMenu(onSaveACopy: () -> Unit, enabled: Boolean = true) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.size(42.dp)) {
            Icon(
                imageVector = Icons.Outlined.MoreVert,
                contentDescription = "More actions",
                tint = mdColors().primaryText,
                modifier = Modifier.size(22.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Save a copy") },
                onClick = {
                    expanded = false
                    onSaveACopy()
                },
            )
        }
    }
}

@Composable
private fun SegmentIcon(icon: ImageVector, contentDescription: String, selected: Boolean, onClick: () -> Unit) {
    val c = mdColors()
    // Active mode = a filled ACCENT chip with an on-accent glyph; inactive = transparent with a muted
    // glyph. The previous "selected = primaryBackground inset on a secondarySurface pill" was two
    // near-identical darks — imperceptible to an external tester (QA #4). The accent fill makes the
    // current mode unmistakable. Colours from the token layer only (Safeguard 4).
    Box(
        modifier = Modifier
            .size(42.dp)
            .padding(2.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) c.accent else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(38.dp)) {
            Icon(
                imageVector = icon,
                // Append the selected state so TalkBack announces the active mode (accessibility).
                contentDescription = if (selected) "$contentDescription, selected" else contentDescription,
                tint = if (selected) {
                    c.onAccent
                } else {
                    c.secondaryText
                },
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
