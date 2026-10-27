// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pilcrowmd.R
import com.pilcrowmd.domain.folders.OpenFileLine
import com.pilcrowmd.ui.theme.PilcrowTypography
import com.pilcrowmd.ui.theme.mdColors

/** Test tag of the open-file line. */
internal const val OPEN_FILE_CAPTION_TAG = "open_file_caption"

/**
 * One small line that names the open file: a folder icon and "Folder › sub › name.md" for a note
 * opened from My folders, a document icon and the file name for any other file. Not tappable. Long
 * text is shortened in the middle, so the end of the name (".md") stays visible. TalkBack reads it
 * once, as "File: Folder, sub, name.md", whatever the line shows. Colours come from the token layer.
 */
@Composable
internal fun OpenFileCaption(line: OpenFileLine, modifier: Modifier = Modifier) {
    val c = mdColors()
    val spoken = if (line.inFolder) {
        stringResource(R.string.main_open_file_description, line.spokenFolderPath, line.name)
    } else {
        stringResource(R.string.main_open_file_description_plain, line.name)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag(OPEN_FILE_CAPTION_TAG)
            .clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (line.inFolder) Icons.Outlined.Folder else Icons.Outlined.Description,
            contentDescription = null,
            tint = c.secondaryText,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        FileNameText(
            name = line.caption,
            color = c.secondaryText,
            fontSize = PilcrowTypography.CAPTION_FONT_SIZE_SP.sp,
        )
    }
}
