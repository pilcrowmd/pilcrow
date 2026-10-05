// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pilcrowmd.ui.theme.mdColors
import com.pilcrowmd.viewmodel.ImageFolderBanner

/**
 * M-93: the strip under the toolbar that explains a note's missing pictures. Part of the page, not
 * floating: it pushes the note down. Passive — every answer goes back to the ViewModel. Colours come
 * from the token layer only (Safeguard 4).
 */
@Composable
fun ImageFolderBannerView(
    banner: ImageFolderBanner,
    onAllow: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = mdColors()
    val (title, detail) = when (banner) {
        is ImageFolderBanner.AskForFolder -> {
            val pictures = if (banner.pictureCount == 1) "1 picture" else "${banner.pictureCount} pictures"
            "This note has $pictures in its folder." to "Allow PilcrowMD to open the folder to show them."
        }
        ImageFolderBanner.CannotShowHere ->
            "Pictures can't be shown from this folder." to "Move the note and its pictures into a subfolder."
    }
    Column(modifier = modifier.fillMaxWidth().background(c.border)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(c.secondarySurface)
                .padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 4.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Image,
                contentDescription = null,
                tint = c.secondaryText,
                modifier = Modifier.padding(top = 1.dp).size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, color = c.primaryText, fontSize = 15.sp, modifier = Modifier.padding(end = 8.dp))
                Text(text = detail, color = c.secondaryText, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when (banner) {
                        is ImageFolderBanner.AskForFolder -> {
                            TextButton(onClick = onDismiss) {
                                Text("Not now", color = c.secondaryText, fontSize = 14.sp)
                            }
                            TextButton(onClick = onAllow) {
                                Text("Allow folder", color = c.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        ImageFolderBanner.CannotShowHere -> TextButton(onClick = onDismiss) {
                            Text("OK", color = c.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        // The 1 dp line under the strip, in the border token.
        Spacer(Modifier.height(1.dp))
    }
}
