// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pilcrowmd.domain.model.HeadingNode
import com.pilcrowmd.ui.theme.PilcrowSpacing
import com.pilcrowmd.ui.theme.mdColors

/**
 * Slide-in drawer content for table-of-contents navigation.
 * Displays all headings from the document, indented by level.
 * User taps a heading to jump to that section in the preview.
 */
@Composable
fun HeadingsDrawer(
    modifier: Modifier = Modifier,
    headings: List<HeadingNode> = emptyList(),
    onHeadingSelected: (HeadingNode) -> Unit = {},
) {
    val c = mdColors()
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(280.dp)
            .background(c.primaryBackground)
            // Inset below the status bar / above the nav bar so the title doesn't draw
            // under the system clock (API 35 edge-to-edge).
            .systemBarsPadding()
            .padding(PilcrowSpacing.sm),
    ) {
        // Title
        Text(
            text = "Headings",
            color = c.primaryText,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = PilcrowSpacing.md),
        )

        // Headings list
        LazyColumn {
            items(headings) { heading ->
                HeadingItem(
                    heading = heading,
                    onSelected = onHeadingSelected,
                )
            }
        }
    }
}

@Composable
private fun HeadingItem(heading: HeadingNode, onSelected: (HeadingNode) -> Unit = {}) {
    val c = mdColors()
    val levelIndent = (heading.level - 1) * 16 // 0dp for H1, 16dp for H2, etc.

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelected(heading) }
            .padding(
                start = levelIndent.dp,
                top = PilcrowSpacing.xs,
                bottom = PilcrowSpacing.xs,
                end = PilcrowSpacing.sm,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = heading.text,
            color = c.primaryText,
            fontSize = maxOf(18 - heading.level, 14).sp,
            fontWeight = if (heading.level == 1) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
    }
}
