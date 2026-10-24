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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pilcrowmd.R
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
        is ImageFolderBanner.AskForFolder ->
            picturesInFolder(banner.pictureCount) to stringResource(R.string.banner_ask_folder_detail)
        ImageFolderBanner.CannotShowHere ->
            stringResource(R.string.banner_cannot_show_title) to stringResource(R.string.banner_cannot_show_detail)
        is ImageFolderBanner.PickNoteFolder ->
            picturesInFolder(banner.pictureCount) to
                stringResource(R.string.banner_pick_folder_detail, banner.noteName)
        is ImageFolderBanner.WrongFolder ->
            stringResource(R.string.banner_wrong_folder_title, banner.noteName) to
                stringResource(R.string.banner_wrong_folder_detail)
    }
    // The button that opens the picker; null for the can't-show banner, which only has "OK".
    val allowLabel = when (banner) {
        is ImageFolderBanner.AskForFolder -> stringResource(R.string.banner_allow_folder)
        is ImageFolderBanner.PickNoteFolder -> stringResource(R.string.banner_pick_folder)
        is ImageFolderBanner.WrongFolder -> stringResource(R.string.banner_pick_again)
        ImageFolderBanner.CannotShowHere -> null
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
                    if (allowLabel != null) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.banner_not_now), color = c.secondaryText, fontSize = 14.sp)
                        }
                        TextButton(onClick = onAllow) {
                            Text(allowLabel, color = c.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        TextButton(onClick = onDismiss) {
                            Text(
                                stringResource(R.string.banner_ok),
                                color = c.accent,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
        // The 1 dp line under the strip, in the border token.
        Spacer(Modifier.height(1.dp))
    }
}

/** "This note has N pictures in its folder.", with the plural form picked by [count]. */
@Composable
private fun picturesInFolder(count: Int): String =
    pluralStringResource(R.plurals.banner_note_pictures_in_folder, count, count)
