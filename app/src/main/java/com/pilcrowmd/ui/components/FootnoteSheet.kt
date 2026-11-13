// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import android.widget.TextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.pilcrowmd.R
import com.pilcrowmd.domain.markdown.FootnoteDefinitionBlock
import com.pilcrowmd.rendering.showFootnoteNote
import com.pilcrowmd.ui.theme.FontSet
import com.pilcrowmd.ui.theme.PilcrowTypography
import com.pilcrowmd.ui.theme.mdColors
import io.noties.markwon.Markwon
import kotlinx.coroutines.launch

/**
 * A footnote's note, over the page (M-76): a tap on a marker opens this, and the reader underneath
 * never moves. Back, a tap outside, a drag down and Close all dismiss it.
 *
 * The note is rendered by the reader's own Markwon into a TextView, exactly as the end-of-document
 * entry renders it, because a Compose `Text` cannot take Markwon's spans (bold, links, maths). It is
 * prefixed by its ordinal, which TalkBack reads first as the title, "Footnote 1". Long notes scroll
 * inside the sheet. Colours come from the token layer only (Safeguard 4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FootnoteSheet(
    note: FootnoteDefinitionBlock,
    markwon: Markwon,
    fontScale: Float,
    fontSet: FontSet,
    onDismiss: () -> Unit,
) {
    val c = mdColors()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val number = note.ordinal?.toString() ?: note.label
    val title = stringResource(R.string.footnote_marker, number)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        containerColor = c.secondarySurface,
        contentColor = c.primaryText,
        scrimColor = c.scrimOverlay,
        dragHandle = { BottomSheetDefaults.DragHandle(color = c.border) },
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = number + ".",
                    color = c.footnoteMarker,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = (PilcrowTypography.FOOTNOTE_FONT_SIZE_SP * fontScale).sp,
                    modifier = Modifier.semantics {
                        contentDescription = title
                        heading()
                    },
                )
                AndroidView(
                    factory = { TextView(it) },
                    update = { it.showFootnoteNote(markwon, note, c, fontScale, fontSet) },
                    modifier = Modifier.weight(1f),
                )
            }
            TextButton(
                onClick = { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.footnote_sheet_close), color = c.secondaryText)
            }
        }
    }
}
