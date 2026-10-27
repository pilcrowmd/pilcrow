// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import android.content.Context
import android.view.WindowManager
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Toc
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pilcrowmd.R
import com.pilcrowmd.ui.theme.PilcrowSpacing
import com.pilcrowmd.ui.theme.mdColors
import com.pilcrowmd.viewmodel.ViewMode

/**
 * Top toolbar (49dp: a 48dp row plus a 1px bottom border, toolbarBorder token). The row is 48dp so the
 * Read/Edit switch's 48dp touch area lies inside the bar; Compose does not route a tap outside a parent.
 *
 * Left: one mode button showing the action (Edit in Read mode, Read in Edit mode).
 * Right: Search, TOC, then explicit Save (Edit mode only; no auto-save) or Export PDF (Read mode
 * only), Undo/Redo (Edit mode), overflow and Close. Opening/switching a file is done from the
 * Home screen: the folder icon was removed so the remaining actions can hit a 42dp touch target
 * within phone width; Close returns to Home, where Open lives.
 */
@Suppress("LongParameterList") // one callback per toolbar action
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
    // Non-null only for .txt documents; the label reflects the mode to switch TO.
    plainToggleLabel: String? = null,
    onTogglePlainView: () -> Unit = {},
    // M-93: present only when a folder grant would show the note's pictures.
    onShowFolderPictures: (() -> Unit)? = null,
    // M-162b: opens the Files screen; no menu item when null.
    onOpenFiles: (() -> Unit)? = null,
    // S8-A43: told when the ⋮ menu opens or closes, so full-screen reading keeps the bar while it is open.
    onMenuOpenChanged: (Boolean) -> Unit = {},
) {
    val c = mdColors()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(49.dp)
            .background(c.primaryBackground),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = PilcrowSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // One mode button (left): shows the action, not the state. Read mode offers Edit, Edit mode
            // offers Read; long-press shows the same word.
            ModeButton(currentMode = currentMode, onModeSelected = onModeSelected)

            // Actions (right): Search + TOC, then Save (Edit only) or Export PDF (Read only), Undo + Redo,
            // overflow, Close.
            // Icons are 22dp glyphs (PDF export 24dp so its "PDF" lettering stays legible) inside 42dp
            // touch targets (glyph bumped from 18dp so the icons read visibly larger; target
            // stays 42dp). The Open folder icon was removed (open from Home) so the full
            // set fits on a phone width with a 42dp tap target and Close (X) is pinned at the far right.
            // Search and TOC now work in both READER and EDITOR modes.
            Row(verticalAlignment = Alignment.CenterVertically) {
                ActionIcon(
                    icon = Icons.Outlined.Search,
                    contentDescription = stringResource(R.string.toolbar_search),
                    onClick = onSearch,
                    enabled = !isSaving,
                )
                ActionIcon(
                    icon = Icons.AutoMirrored.Outlined.Toc,
                    contentDescription = stringResource(R.string.toolbar_table_of_contents),
                    onClick = onTOC,
                    enabled = !isSaving,
                )
                // Save (editor mode only). "Save a copy" stays in the overflow menu in both modes.
                if (currentMode == ViewMode.EDITOR) {
                    ActionIcon(
                        icon = Icons.Outlined.Save,
                        contentDescription = if (isSaving) {
                            stringResource(R.string.toolbar_saving)
                        } else {
                            stringResource(R.string.toolbar_save)
                        },
                        onClick = onSave,
                        enabled = !isSaving,
                        tint = if (isSaving) {
                            c.secondaryText
                        } else {
                            c.primaryText
                        },
                    )
                }
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
                        contentDescription = stringResource(R.string.toolbar_undo),
                        onClick = onUndo,
                    )
                }
                // Redo button (editor mode only, if onRedo is provided)
                if (onRedo != null) {
                    ActionIcon(
                        icon = Icons.AutoMirrored.Outlined.Redo,
                        contentDescription = stringResource(R.string.toolbar_redo),
                        onClick = onRedo,
                    )
                }
                // Overflow menu (⋮) — hosts "Save a copy" + the .txt render toggle. Sits left of Close.
                OverflowMenu(
                    onSaveACopy = onSaveACopy,
                    enabled = !isSaving,
                    plainToggleLabel = plainToggleLabel,
                    onTogglePlainView = onTogglePlainView,
                    onShowFolderPictures = onShowFolderPictures,
                    onOpenFiles = onOpenFiles,
                    onOpenChanged = onMenuOpenChanged,
                )
                // Settings moved off the toolbar to the Welcome screen.
                // Close (X) — always the rightmost action.
                ActionIcon(
                    icon = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.toolbar_close_file),
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
 * The single Reader/Editor switch. It shows the action it performs: [Icons.Outlined.EditNote] "Edit"
 * in Read mode, [Icons.AutoMirrored.Outlined.MenuBook] "Read" in Edit mode. A [MODE_BUTTON_WIDTH] by
 * [MODE_BUTTON_HEIGHT] button, so its whole 48 by 48dp touch area lies inside the bar, with a
 * [MODE_ICON_SIZE] accent glyph (10dp clear above and below inside the 48dp row); a long-press tooltip
 * carries the same word for sighted users (TalkBack reads the label).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeButton(currentMode: ViewMode, onModeSelected: (ViewMode) -> Unit) {
    val c = mdColors()
    val editing = currentMode == ViewMode.EDITOR
    val label = stringResource(if (editing) R.string.toolbar_read else R.string.toolbar_edit)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip(containerColor = c.secondarySurface, contentColor = c.primaryText) { Text(label) }
        },
        state = rememberTooltipState(),
    ) {
        IconButton(
            onClick = { onModeSelected(if (editing) ViewMode.READER else ViewMode.EDITOR) },
            modifier = Modifier.size(width = MODE_BUTTON_WIDTH, height = MODE_BUTTON_HEIGHT),
        ) {
            Icon(
                imageVector = if (editing) Icons.AutoMirrored.Outlined.MenuBook else Icons.Outlined.EditNote,
                contentDescription = label,
                tint = c.accent,
                modifier = Modifier.size(MODE_ICON_SIZE),
            )
        }
    }
}

/**
 * A toolbar action button: a 42dp touch target (up from 36dp for reliable finger taps) with a 22dp
 * icon (up from 18dp so icons read visibly larger), so every icon in the bar reads at the same scale.
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
                contentDescription = stringResource(R.string.toolbar_export_pdf),
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
 * M-308: names the popup's own WINDOW. TalkBack reads the window title, not a pane title inside it,
 * and Compose's default for a popup is "Pop-Up Window".
 */
@Composable
private fun PopupWindowTitle(title: String) {
    val view = LocalView.current
    LaunchedEffect(title) {
        val root = view.rootView
        val params = root.layoutParams as? WindowManager.LayoutParams ?: return@LaunchedEffect
        params.title = title
        (view.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).updateViewLayout(root, params)
    }
}

/**
 * Toolbar overflow (⋮) menu. Owns its own expanded state; hosts "Save a copy" (Save-As) and — for
 * `.txt` documents only ([plainToggleLabel] non-null) — the quiet render-mode toggle
 * ("View as Markdown" ⇄ "View as plain text"). A 42dp target / 22dp glyph to match the
 * neighbouring [ActionIcon]s; the menu anchors to it.
 */
@Suppress("LongParameterList") // one optional item per parameter
@Composable
private fun OverflowMenu(
    onSaveACopy: () -> Unit,
    enabled: Boolean = true,
    plainToggleLabel: String? = null,
    onTogglePlainView: () -> Unit = {},
    onShowFolderPictures: (() -> Unit)? = null,
    onOpenFiles: (() -> Unit)? = null,
    onOpenChanged: (Boolean) -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    val reportOpen by rememberUpdatedState(onOpenChanged)
    val open = expanded
    DisposableEffect(open) {
        reportOpen(open)
        // The bar can leave while the menu is open; then nothing is open any more.
        onDispose { if (open) reportOpen(false) }
    }
    val moreActions = stringResource(R.string.toolbar_more_actions)
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.size(42.dp)) {
            Icon(
                imageVector = Icons.Outlined.MoreVert,
                contentDescription = moreActions,
                tint = mdColors().primaryText,
                modifier = Modifier.size(22.dp),
            )
        }
        // M-308: the popup is a window of its own; TalkBack announced it as "Pop-Up Window".
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.semantics { paneTitle = moreActions },
        ) {
            PopupWindowTitle(moreActions)
            DropdownMenuItem(
                text = { Text(stringResource(R.string.toolbar_save_a_copy)) },
                onClick = {
                    expanded = false
                    onSaveACopy()
                },
            )
            if (plainToggleLabel != null) {
                DropdownMenuItem(
                    text = { Text(plainToggleLabel) },
                    onClick = {
                        expanded = false
                        onTogglePlainView()
                    },
                )
            }
            if (onShowFolderPictures != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.toolbar_show_folder_pictures)) },
                    onClick = {
                        expanded = false
                        onShowFolderPictures()
                    },
                )
            }
            if (onOpenFiles != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.files_title)) },
                    onClick = {
                        expanded = false
                        onOpenFiles()
                    },
                )
            }
        }
    }
}

// The Read/Edit switch is the bar's main control, so its icon is drawn larger than the actions' 22 dp
// (on request, 21 Oct).
private val MODE_ICON_SIZE = 28.dp
private val MODE_BUTTON_WIDTH = 48.dp
private val MODE_BUTTON_HEIGHT = 48.dp
