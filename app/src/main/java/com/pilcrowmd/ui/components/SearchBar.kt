// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pilcrowmd.ui.theme.PilcrowSpacing
import com.pilcrowmd.ui.theme.mdColors

/**
 * Search bar for in-document search.
 *
 * Layout: text field (left) → match count display → prev/next navigation arrows → close button (right).
 * Match count shows as "(current+1) / total" (human-readable, 1-indexed display).
 * Prev/next buttons disabled when no matches.
 * All colors from design tokens (no hardcoded hex).
 */
@Composable
fun SearchBar(
    modifier: Modifier = Modifier,
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    matchCount: Int = 0,
    currentIndex: Int = 0,
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    onClose: () -> Unit = {},
) {
    val c = mdColors()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(c.primaryBackground)
            .padding(
                horizontal = PilcrowSpacing.sm,
                vertical = PilcrowSpacing.xs,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PilcrowSpacing.sm),
    ) {
        // Search text field (left)
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .height(40.dp),
            textStyle = TextStyle(
                color = c.primaryText,
                fontSize = 16.sp,
            ),
            // Single-line so Enter never inserts a newline into the query (which corrupted the
            // search term and dropped all matches). The IME shows a Search action that jumps to
            // the next match — results already update live as you type.
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onNext() }),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            c.secondarySurface,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                        )
                        .padding(horizontal = PilcrowSpacing.sm),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (query.isEmpty()) {
                        Text(
                            text = "Search",
                            color = c.secondaryText,
                            fontSize = 16.sp,
                        )
                    }
                    innerTextField()
                }
            },
        )

        // Match count (center-right)
        Text(
            text = if (matchCount > 0) "${currentIndex + 1}/$matchCount" else "0/0",
            color = c.secondaryText,
            fontSize = 14.sp,
            modifier = Modifier.widthIn(min = 36.dp),
        )

        // Previous match button
        IconButton(
            onClick = onPrevious,
            enabled = matchCount > 0,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowUp,
                contentDescription = "Previous match",
                tint = if (matchCount > 0) {
                    c.primaryText
                } else {
                    c.secondaryText
                },
            )
        }

        // Next match button
        IconButton(
            onClick = onNext,
            enabled = matchCount > 0,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowDown,
                contentDescription = "Next match",
                tint = if (matchCount > 0) {
                    c.primaryText
                } else {
                    c.secondaryText
                },
            )
        }

        // Close button (far right)
        IconButton(
            onClick = onClose,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "Close search",
                tint = c.primaryText,
            )
        }
    }
}
