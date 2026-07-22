// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pilcrowmd.ui.theme.mdColors
import com.pilcrowmd.ui.theme.sourceSerif4Family
import com.pilcrowmd.viewmodel.RecentFileUi

/**
 * Welcome / start screen (shown when no file is open). Visual design per the
 * reference mockup: faint ¶ pilcrow serif watermark (upper third), purple-accent
 * serif wordmark, sparkle divider, subtitle, cream "Open MD File" button, recents
 * list, footer. All colors from the token layer.
 */
@Composable
fun WelcomeScreen(
    modifier: Modifier = Modifier,
    recentFiles: List<RecentFileUi> = emptyList(),
    onOpenFile: () -> Unit = {},
    onOpenAnyFile: () -> Unit = {},
    onOpenRecent: (Uri) -> Unit = {},
    onRemoveRecent: (Uri) -> Unit = {},
    onClearRecents: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val c = mdColors()
    // Guard the destructive "Clear recents" with a confirm dialog (QA #5). rememberSaveable so the
    // dialog survives a rotation, matching the app's other confirm dialogs.
    var showClearConfirm by rememberSaveable { mutableStateOf(false) }
    // The ¶ mark and wordmark are a brand graphic, not body text: size them in density-pixels
    // (dp → sp at the current density) so they stay stable under the OS accessibility font scale
    // instead of ballooning/truncating. Identical to sp at the default scale.
    val watermarkFontSize = with(LocalDensity.current) { 280.dp.toSp() }
    val wordmarkFontSize = with(LocalDensity.current) { 72.dp.toSp() }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(c.primaryBackground),
    ) {
        // Large faint ¶ watermark behind the wordmark (Pilcrow mockup). Drawn first (behind),
        // anchored near the upper third so the wordmark below sits over its lower stem.
        Text(
            text = "¶",
            fontFamily = sourceSerif4Family,
            fontWeight = FontWeight.Bold,
            fontSize = watermarkFontSize,
            color = c.primaryText.copy(alpha = 0.06f),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 40.dp),
        )

        // Brand + CTA + recents + footer in one scroll. The inner box is at least a viewport
        // tall, so the footer sits at the screen bottom when the list is short, and scrolls
        // into view (no overlap) once the recents list grows past the screen.
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val viewportHeight = maxHeight
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = viewportHeight),
                ) {
                    // Content (top-anchored)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(Modifier.height(186.dp)) // wordmark lands over the ¶ watermark

                        // Serif wordmark over the faint ¶ watermark (drawn in the outer Box).
                        Text(
                            text = buildAnnotatedString {
                                withStyle(SpanStyle(color = c.accent)) { append("P") }
                                withStyle(SpanStyle(color = c.primaryText)) { append("ilcrow") }
                            },
                            fontFamily = sourceSerif4Family,
                            fontWeight = FontWeight.Bold,
                            fontSize = wordmarkFontSize,
                            maxLines = 1,
                        )

                        Spacer(Modifier.height(16.dp))

                        // Sparkle divider: line ✦ line
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                Modifier.height(
                                    1.dp,
                                ).width(44.dp).background(c.accent.copy(alpha = 0.45f)),
                            )
                            Text(text = "✦", color = c.accent, fontSize = 14.sp)
                            Box(
                                Modifier.height(
                                    1.dp,
                                ).width(44.dp).background(c.accent.copy(alpha = 0.45f)),
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        Text(
                            text = "Beautiful Markdown Reading",
                            fontFamily = sourceSerif4Family,
                            fontSize = 16.sp,
                            color = c.secondaryText,
                        )

                        Spacer(Modifier.height(44.dp))

                        // Cream CTA
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .shadow(12.dp, RoundedCornerShape(16.dp), clip = false)
                                .clip(RoundedCornerShape(16.dp))
                                .background(c.creamButton)
                                .clickable { onOpenFile() }
                                .padding(vertical = 18.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.FolderOpen,
                                contentDescription = null,
                                tint = c.onCreamButton,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = "Open MD File",
                                color = c.onCreamButton,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 18.sp,
                            )
                        }

                        // Secondary, low-emphasis fallback: the primary CTA filters the picker to
                        // text/Markdown, but some providers report .md as application/octet-stream
                        // (hidden by that filter). This opens the picker unfiltered so a mislabeled
                        // Markdown file is still reachable. (Opening any file is read-only — rendering
                        // never writes back.)
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = "Can't see your file? Browse all files",
                            color = c.secondaryText,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onOpenAnyFile() }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )

                        // Recents list
                        if (recentFiles.isNotEmpty()) {
                            Spacer(Modifier.height(28.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "RECENT",
                                    color = c.secondaryText,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "Clear",
                                    color = c.accent,
                                    fontSize = 13.sp,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { showClearConfirm = true }
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            recentFiles.forEach { r -> RecentRow(r, onOpenRecent, onRemoveRecent) }
                        }

                        // Clearance so the last item never collides with the footer below.
                        Spacer(Modifier.height(96.dp))
                    }

                    // Footer: line 📄 line / "Supports .md files" — bottom of the >=viewport box.
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                Modifier.height(
                                    1.dp,
                                ).width(36.dp).background(c.secondaryText.copy(alpha = 0.25f)),
                            )
                            Icon(
                                imageVector = Icons.Outlined.Description,
                                contentDescription = null,
                                tint = c.secondaryText.copy(alpha = 0.5f),
                                modifier = Modifier.size(18.dp),
                            )
                            Box(
                                Modifier.height(
                                    1.dp,
                                ).width(36.dp).background(c.secondaryText.copy(alpha = 0.25f)),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Supports .md files",
                            color = c.secondaryText.copy(alpha = 0.6f),
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        }

        // Settings entry: app-wide settings live here, reachable only from home.
        // Drawn last so it sits above the scrollable content and stays tappable.
        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = "Settings",
                tint = c.secondaryText,
                modifier = Modifier.size(22.dp),
            )
        }

        // Confirm before clearing the Recents list (QA #5). The list is a convenience index, not
        // document data — no Essential-Safeguard implications — but the guard prevents an accidental
        // wipe. Token-coloured to match the app's other confirm dialogs (Safeguard 4).
        if (showClearConfirm) {
            AlertDialog(
                onDismissRequest = { showClearConfirm = false },
                containerColor = c.secondarySurface,
                titleContentColor = c.primaryText,
                textContentColor = c.secondaryText,
                title = { Text("Clear recent files?") },
                text = { Text("This removes every entry from your Recent list. Your files are not deleted.") },
                confirmButton = {
                    TextButton(onClick = {
                        showClearConfirm = false
                        onClearRecents()
                    }) {
                        Text("Clear all", color = c.accent)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearConfirm = false }) {
                        Text("Cancel", color = c.secondaryText)
                    }
                },
            )
        }
    }
}

@Composable
private fun RecentRow(r: RecentFileUi, onOpenRecent: (Uri) -> Unit, onRemoveRecent: (Uri) -> Unit) {
    val c = mdColors()
    val nameColor = if (r.available) {
        c.primaryText
    } else {
        c.secondaryText.copy(alpha = 0.5f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = r.available) { onOpenRecent(r.uri) }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Description,
            contentDescription = null,
            tint = if (r.available) {
                c.secondaryText
            } else {
                c.secondaryText.copy(
                    alpha = 0.4f,
                )
            },
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = r.displayName,
                color = nameColor,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = relativeTime(r.lastOpened) + if (!r.available) " · unavailable" else "",
                color = c.secondaryText.copy(alpha = 0.6f),
                fontSize = 12.sp,
            )
        }
        Icon(
            imageVector = Icons.Outlined.Close,
            contentDescription = "Remove from recents",
            tint = c.secondaryText.copy(alpha = 0.5f),
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { onRemoveRecent(r.uri) }
                .padding(4.dp)
                .size(16.dp),
        )
    }
}

private fun relativeTime(ts: Long): String =
    DateUtils.getRelativeTimeSpanString(ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
