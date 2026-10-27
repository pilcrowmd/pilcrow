// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

@file:Suppress("TooManyFunctions") // one small composable per part of the screen

package com.pilcrowmd.ui.components

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pilcrowmd.R
import com.pilcrowmd.domain.folders.Ago
import com.pilcrowmd.domain.folders.Mark
import com.pilcrowmd.ui.theme.mdColors
import com.pilcrowmd.viewmodel.FileRowUi
import com.pilcrowmd.viewmodel.FolderMessage
import com.pilcrowmd.viewmodel.FolderRowUi
import com.pilcrowmd.viewmodel.ListingUi
import com.pilcrowmd.viewmodel.NotesFoldersUiState
import kotlinx.coroutines.delay

/** How long a message about a refused folder stays up, like the app's error toast. */
private const val MESSAGE_MS = 2800L

/** Indent per subfolder level in the Markdown files tree. */
private val LEVEL_INDENT = 16.dp

/** The slot left of a row's icon: the chevron of a folder, the N or U mark of a note (M1, M2). */
private val LEAD_SLOT = 20.dp

private const val UNAVAILABLE_ALPHA = 0.5f

/**
 * M-162b: the Files screen (spec sections 2, 3 and 3b; section 6 Android). The added folders, one
 * ticked, and the ticked folder's Markdown files as a tree with New and Updated marks. A full
 * screen like Settings; state in, events out, no logic here: the rows arrive already built.
 * [openNoteId] is the note the reader shows, which keeps the grey highlight (M8).
 * Colours come only from the token layer (Safeguard 4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongParameterList") // a leaf screen's events
@Composable
fun FilesScreen(
    state: NotesFoldersUiState,
    modifier: Modifier = Modifier,
    openNoteId: String? = null,
    onBack: () -> Unit = {},
    onAddFolder: () -> Unit = {},
    onSelectFolder: (Uri) -> Unit = {},
    onRemoveFolder: (Uri) -> Unit = {},
    onFindAgain: (Uri) -> Unit = {},
    onMarkAllSeen: () -> Unit = {},
    onToggleFolder: (String) -> Unit = {},
    onOpenNote: (String) -> Unit = {},
    onRefresh: () -> Unit = {},
    onMessageShown: () -> Unit = {},
) {
    val c = mdColors()
    val ticked = state.folders.firstOrNull { it.selected }
    Box(modifier = modifier.fillMaxSize().background(c.primaryBackground)) {
        Column(modifier = Modifier.fillMaxSize()) {
            FilesHeader(
                ticked = ticked,
                onBack = onBack,
                onAddFolder = onAddFolder,
                onMarkAllSeen = onMarkAllSeen,
                onRemoveFolder = onRemoveFolder,
            )
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (state.folders.isNotEmpty()) {
                        item(key = "folders-label") { SectionLabel(stringResource(R.string.files_section_folders)) }
                        items(state.folders, key = { "folder:${it.treeUri}" }) { folder ->
                            FolderListRow(folder, onClick = { onSelectFolder(folder.treeUri) })
                        }
                        item(key = "files-label") { SectionLabel(stringResource(R.string.files_section_markdown)) }
                    }
                    listingItems(state.listing, ticked, openNoteId, onAddFolder, onFindAgain, onRemoveFolder) {
                        when {
                            it is FileRowUi.Folder -> onToggleFolder(it.documentId)
                            // The note already in the reader: back to it as it is, with no reload and
                            // no Save/Discard prompt for its own unsaved edits.
                            it.documentId == openNoteId -> onBack()
                            else -> onOpenNote(it.documentId)
                        }
                    }
                }
            }
        }
        FolderMessageToast(state.message, onMessageShown, Modifier.align(Alignment.Center))
    }
}

@Composable
private fun FilesHeader(
    ticked: FolderRowUi?,
    onBack: () -> Unit,
    onAddFolder: () -> Unit,
    onMarkAllSeen: () -> Unit,
    onRemoveFolder: (Uri) -> Unit,
) {
    val c = mdColors()
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.files_back),
                tint = c.primaryText,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = stringResource(R.string.files_title),
            color = c.primaryText,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onAddFolder) {
            Icon(
                imageVector = Icons.Outlined.CreateNewFolder,
                contentDescription = stringResource(R.string.files_add_folder),
                tint = c.primaryText,
                modifier = Modifier.size(22.dp),
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }, enabled = ticked != null) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = stringResource(R.string.files_more),
                    tint = c.primaryText,
                    modifier = Modifier.size(22.dp),
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.files_mark_all_seen)) },
                    enabled = (ticked?.markCount ?: 0) > 0,
                    onClick = {
                        menuOpen = false
                        onMarkAllSeen()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.files_remove_folder)) },
                    onClick = {
                        menuOpen = false
                        ticked?.let { onRemoveFolder(it.treeUri) }
                    },
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = mdColors().secondaryText,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 6.dp),
    )
}

/** An added folder: name, grey suffix (P15), its mark total (M7), the tick. Greyed when unavailable (F4). */
@Composable
private fun FolderListRow(folder: FolderRowUi, onClick: () -> Unit) {
    val c = mdColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp)
            .alpha(if (folder.available) 1f else UNAVAILABLE_ALPHA),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Folder, contentDescription = null, tint = c.secondaryText, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = folder.name,
                color = c.primaryText,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            folder.suffix?.let {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = it,
                    color = c.secondaryText,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!folder.available) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.files_folder_unavailable),
                    color = c.secondaryText,
                    fontSize = 13.sp,
                )
            }
        }
        val marks = folder.markCount ?: 0
        if (marks > 0) {
            Text(text = marks.toString(), color = c.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(10.dp))
        }
        if (folder.selected) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = stringResource(R.string.files_selected),
                tint = c.accent,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** The area under "Markdown files": the tree, or what stands in for it (F4, F6, never a silent empty list). */
@Suppress("LongParameterList")
private fun LazyListScope.listingItems(
    listing: ListingUi,
    ticked: FolderRowUi?,
    openNoteId: String?,
    onAddFolder: () -> Unit,
    onFindAgain: (Uri) -> Unit,
    onRemove: (Uri) -> Unit,
    onRowClick: (FileRowUi) -> Unit,
) {
    when (listing) {
        ListingUi.Loading -> item(key = "loading") { LoadingItem() }
        ListingUi.NoFolders -> item(key = "no-folders") { NoFoldersItem(onAddFolder) }
        ListingUi.Unavailable -> item(key = "unavailable") {
            UnavailableItem(
                onFindAgain = { ticked?.let { onFindAgain(it.treeUri) } },
                onRemove = { ticked?.let { onRemove(it.treeUri) } },
            )
        }
        is ListingUi.Loaded -> if (listing.rows.isEmpty()) {
            item(key = "no-notes") { MessageText(stringResource(R.string.files_no_notes)) }
        } else {
            items(
                listing.rows,
                key = { "row:${it.key}" },
                contentType = { if (it is FileRowUi.Folder) "folder" else "note" },
            ) { row ->
                when (row) {
                    is FileRowUi.Folder -> FolderTreeRow(row) { onRowClick(row) }
                    is FileRowUi.Note -> NoteTreeRow(row, open = row.documentId == openNoteId) { onRowClick(row) }
                }
            }
        }
    }
}

@Composable
private fun FolderTreeRow(row: FileRowUi.Folder, onClick: () -> Unit) {
    val c = mdColors()
    val described = folderDescription(row)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clickable(onClick = onClick)
            .clearAndSetSemantics { contentDescription = described }
            .padding(start = indentOf(row.depth), end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(LEAD_SLOT), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = c.secondaryText,
                modifier = Modifier.size(18.dp).rotate(if (row.expanded) 90f else 0f),
            )
        }
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Outlined.Folder, contentDescription = null, tint = c.secondaryText, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = row.name,
            color = c.primaryText,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // M7: a closed folder says how many marked notes it hides; an open one shows them.
        if (!row.expanded && row.markCount > 0) {
            Spacer(Modifier.width(8.dp))
            Text(text = row.markCount.toString(), color = c.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun NoteTreeRow(row: FileRowUi.Note, open: Boolean, onClick: () -> Unit) {
    val c = mdColors()
    val described = noteDescription(row)
    val whenLine = row.mark?.let { mark -> row.ago?.let { whenText(mark, it) } }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .background(if (open) c.secondarySurface else c.primaryBackground)
            .clickable(onClick = onClick)
            .clearAndSetSemantics { contentDescription = described }
            .padding(start = indentOf(row.depth), end = 20.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(LEAD_SLOT), contentAlignment = Alignment.Center) {
            row.mark?.let { MarkBadge(it) }
        }
        Spacer(Modifier.width(4.dp))
        Icon(
            imageVector = Icons.Outlined.Description,
            contentDescription = null,
            tint = c.secondaryText,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name,
                color = c.primaryText,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (whenLine != null) Text(text = whenLine, color = c.accent, fontSize = 12.sp, maxLines = 1)
        }
    }
}

/** M1: a small filled accent dot with N. M2: an accent ring with U. */
@Composable
internal fun MarkBadge(mark: Mark) {
    val c = mdColors()
    val dot = Modifier.size(16.dp).clip(CircleShape)
    val (letter, look, letterColor) = when (mark) {
        Mark.NEW -> Triple(R.string.files_mark_new_letter, dot.background(c.accent), c.onAccent)
        Mark.UPDATED -> Triple(R.string.files_mark_updated_letter, dot.border(1.5.dp, c.accent, CircleShape), c.accent)
    }
    // S8-A38: sized in dp, not sp. The circle is 16 dp whatever the font scale, so a letter that grew
    // with it filled the circle, ran past its box and sat low (measured: 2 to 2.5 px at 2.0). The
    // row's description already says "new" or "updated" for a reader who needs larger text.
    val letterSize = with(LocalDensity.current) { LETTER_SIZE.toSp() }
    Box(modifier = look, contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(letter),
            color = letterColor,
            style = TextStyle(
                fontSize = letterSize,
                fontWeight = FontWeight.Bold,
                // The line box is the letter's box, so Center puts the letter in the middle of the circle.
                lineHeight = letterSize,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
            ),
        )
    }
}

private val LETTER_SIZE = 9.dp

private fun indentOf(depth: Int): Dp = 16.dp + LEVEL_INDENT * depth

@Composable
private fun whenText(mark: Mark, ago: Ago): String {
    val short = shortAgo(ago)
    return when (mark) {
        Mark.NEW -> short?.let { stringResource(R.string.files_added_ago, it) }
            ?: stringResource(R.string.files_added_just_now)
        Mark.UPDATED -> short?.let { stringResource(R.string.files_updated_ago, it) }
            ?: stringResource(R.string.files_updated_just_now)
    }
}

/** "12 min", "1 h", "2 d"; null for just now. */
@Composable
private fun shortAgo(ago: Ago): String? = when (ago) {
    Ago.JustNow -> null
    is Ago.Minutes -> count(R.plurals.files_minutes_short, ago.count)
    is Ago.Hours -> count(R.plurals.files_hours_short, ago.count)
    is Ago.Days -> count(R.plurals.files_days_short, ago.count)
}

/** "12 minutes", "1 hour", "2 days" for TalkBack; null for just now. */
@Composable
private fun longAgo(ago: Ago): String? = when (ago) {
    Ago.JustNow -> null
    is Ago.Minutes -> count(R.plurals.files_minutes_long, ago.count)
    is Ago.Hours -> count(R.plurals.files_hours_long, ago.count)
    is Ago.Days -> count(R.plurals.files_days_long, ago.count)
}

@Composable
private fun count(plural: Int, n: Long): String {
    val shown = n.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return pluralStringResource(plural, shown, shown)
}

/** M8: "<name>, new, added 12 minutes ago", "<name>, updated, 1 hour ago", or the name alone. */
@Composable
private fun noteDescription(row: FileRowUi.Note): String {
    val mark = row.mark ?: return row.name
    val long = row.ago?.let { longAgo(it) }
    return when (mark) {
        Mark.NEW -> long?.let { stringResource(R.string.files_note_new_ago, row.name, it) }
            ?: stringResource(R.string.files_note_new_just_now, row.name)
        Mark.UPDATED -> long?.let { stringResource(R.string.files_note_updated_ago, row.name, it) }
            ?: stringResource(R.string.files_note_updated_just_now, row.name)
    }
}

/** "<name>, folder, expanded" or "<name>, folder, collapsed, 3 marked notes". */
@Composable
private fun folderDescription(row: FileRowUi.Folder): String {
    val base = stringResource(
        if (row.expanded) R.string.files_folder_expanded else R.string.files_folder_collapsed,
        row.name,
    )
    if (row.expanded || row.markCount == 0) return base
    return base + ", " + pluralStringResource(R.plurals.files_marked_notes, row.markCount, row.markCount)
}

@Composable
private fun LoadingItem() {
    val label = stringResource(R.string.files_loading)
    Box(
        modifier = Modifier.fillMaxWidth().padding(24.dp).clearAndSetSemantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = mdColors().secondaryText)
    }
}

@Composable
private fun MessageText(text: String) {
    Text(
        text = text,
        color = mdColors().secondaryText,
        fontSize = 14.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
    )
}

/** F6/P16: nothing added yet. */
@Composable
private fun NoFoldersItem(onAddFolder: () -> Unit) {
    val c = mdColors()
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.Folder, contentDescription = null, tint = c.secondaryText, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.files_no_folders_title),
            color = c.primaryText,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.files_no_folders_body),
            color = c.secondaryText,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedAction(stringResource(R.string.files_add_folder), onAddFolder)
    }
}

/** F4: the ticked folder cannot be read. */
@Composable
private fun UnavailableItem(onFindAgain: () -> Unit, onRemove: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.files_unavailable),
            color = mdColors().secondaryText,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedAction(stringResource(R.string.files_find_again), onFindAgain)
            OutlinedAction(stringResource(R.string.files_remove), onRemove)
        }
    }
}

@Composable
private fun OutlinedAction(label: String, onClick: () -> Unit) {
    val c = mdColors()
    TextButton(
        onClick = onClick,
        modifier = Modifier.border(1.dp, c.border, RoundedCornerShape(12.dp)),
    ) {
        Text(text = label, color = c.primaryText, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

/** A refused pick, said in the middle of the screen like the app's error toast, then cleared. */
@Composable
private fun FolderMessageToast(message: FolderMessage?, onShown: () -> Unit, modifier: Modifier) {
    val c = mdColors()
    LaunchedEffect(message) {
        if (message != null) {
            delay(MESSAGE_MS)
            onShown()
        }
    }
    AnimatedVisibility(visible = message != null, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        val text = when (message) {
            FolderMessage.ALREADY_LISTED -> stringResource(R.string.files_message_already_listed)
            FolderMessage.NOT_A_FOLDER -> stringResource(R.string.files_message_not_a_folder)
            FolderMessage.PERMISSION_REFUSED -> stringResource(R.string.files_message_permission_refused)
            null -> ""
        }
        Text(
            text = text,
            color = c.onError,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(horizontal = 32.dp)
                .background(c.error, shape = RoundedCornerShape(8.dp))
                .padding(horizontal = 24.dp, vertical = 12.dp),
        )
    }
}
