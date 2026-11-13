// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pilcrowmd.R
import com.pilcrowmd.domain.markdown.HeadingFold
import com.pilcrowmd.domain.model.HeadingNode
import com.pilcrowmd.ui.theme.PilcrowSpacing
import com.pilcrowmd.ui.theme.mdColors

/**
 * Slide-in drawer content for table-of-contents navigation.
 * Displays all headings from the document, indented by level.
 * User taps a heading to jump to that section in the preview.
 *
 * S8-A49: a heading with sub-headings has an arrow that folds them away; one title button collapses
 * or expands them all. Only the list folds, never the reading text.
 */
// focusProperties.enter, as ThemedNavigationDrawer uses it; FlowRow is experimental in this Compose
@OptIn(ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
@Composable
fun HeadingsDrawer(
    modifier: Modifier = Modifier,
    headings: List<HeadingNode> = emptyList(),
    onHeadingSelected: (HeadingNode) -> Unit = {},
    // M-307: read when a focus move is made, so it is current the moment a drawer opens. The drawer
    // stays composed while closed; its rows must not be Tab stops then.
    rowsFocusable: () -> Boolean = { true },
    // S8-A49: the open file. A new file starts with nothing folded.
    documentKey: Any? = null,
) {
    val c = mdColors()
    // Folds name headings by index, so they hold only while the levels are the same; an edit that
    // changes them starts again with nothing folded, as on the Mac. Saveable for rotation; the
    // drawer stays composed while closed, so closing it keeps them too. Not kept past a restart.
    val levels = remember(headings) { headings.map { it.level } }
    var folded by rememberSaveable(documentKey, levels) { mutableStateOf(emptySet<Int>()) }
    val foldable = remember(headings) { HeadingFold.foldable(headings) }
    val visible = remember(headings, folded) { HeadingFold.visible(headings, folded) }
    val listState = rememberLazyListState()
    val firstRow = remember { FocusRequester() }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(280.dp)
            .background(c.primaryBackground)
            // Inset below the status bar / above the nav bar so the title doesn't draw
            // under the system clock (API 35 edge-to-edge). The cutout padding is Start-only,
            // unlike the Horizontal used elsewhere: the drawer opens from the start edge, so a
            // camera on the end side is not under it and must not take a cutout-wide margin out
            // of the 280 dp.
            .systemBarsPadding()
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Start))
            .padding(PilcrowSpacing.sm)
            // A keyboard open lands on the first heading, as before the fold controls, not on the
            // title button or an arrow. Only while that row is composed and may take focus; anything
            // else is the default first-child entry (M-307: a closed drawer's rows are no stops).
            .focusProperties {
                enter = {
                    if (rowsFocusable() && visible.isNotEmpty() && listState.firstVisibleItemIndex == 0) {
                        firstRow
                    } else {
                        FocusRequester.Default
                    }
                }
            }
            .focusGroup(),
    ) {
        // Title, and the one Collapse all / Expand all button when anything can fold. The button
        // drops to its own line when the two do not fit side by side, so a long translation of the
        // title ("Inhaltsverzeichnis") is never squeezed into breaking mid-word.
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(bottom = PilcrowSpacing.md),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            FittedTitle(
                text = stringResource(R.string.contents_title),
                color = c.primaryText,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            if (foldable.isNotEmpty()) {
                Text(
                    text = stringResource(
                        if (folded.isEmpty()) R.string.headings_collapse_all else R.string.headings_expand_all,
                    ),
                    color = c.accent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .focusProperties { canFocus = rowsFocusable() }
                        .clickable(role = Role.Button) { folded = if (folded.isEmpty()) foldable else emptySet() }
                        .padding(horizontal = PilcrowSpacing.xs),
                )
            }
        }

        // Contents list
        LazyColumn(state = listState) {
            items(visible, key = { it }) { index ->
                val heading = headings[index]
                HeadingItem(
                    heading = heading,
                    fold = if (index in foldable) index in folded else null,
                    onToggleFold = { folded = if (index in folded) folded - index else folded + index },
                    onSelected = onHeadingSelected,
                    focusable = rowsFocusable,
                    focusRequester = if (index == 0) firstRow else null,
                )
            }
        }
    }
}

private val TitleMaxSize = 18.sp
private val TitleMinSize = 10.sp

/**
 * The drawer title: one line, never broken mid-word, never cut. It is [TitleMaxSize] unless that is
 * wider than the whole row (a long word at a large system font scale); then the largest whole-sp
 * size down to [TitleMinSize] that fits. Compose 1.7 has no `autoSize` on Text, so the fit is
 * measured here, which also avoids a first frame at the wrong size.
 */
@Composable
private fun FittedTitle(text: String, color: Color, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val base = LocalTextStyle.current.copy(fontWeight = FontWeight.Bold)
    BoxWithConstraints(modifier) {
        val fontSize = remember(text, base, maxWidth, measurer) {
            var size = TitleMaxSize
            fun widthAt(s: TextUnit) = measurer.measure(text, base.copy(fontSize = s), softWrap = false).size.width
            while (size > TitleMinSize && widthAt(size) > constraints.maxWidth) {
                size = (size.value - 1f).sp
            }
            size
        }
        Text(
            text = text,
            color = color,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** [fold] is null for a heading with no sub-headings (no arrow), else whether it is folded. */
@Suppress("LongParameterList") // the heading, its fold, its two taps and its two focus inputs
@Composable
private fun HeadingItem(
    heading: HeadingNode,
    fold: Boolean? = null,
    onToggleFold: () -> Unit = {},
    onSelected: (HeadingNode) -> Unit = {},
    focusable: () -> Boolean = { true },
    focusRequester: FocusRequester? = null,
) {
    val c = mdColors()
    val levelIndent = (heading.level - 1) * 16 // 0dp for H1, 16dp for H2, etc.

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The arrow column is 28dp on every row, so the text lines up whether or not it has an
        // arrow. The arrow is laid out at 28dp but Compose hit-tests a small touch target out to
        // 48dp where no other target is hit, so the indent below stays outside the heading's tap.
        Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            if (fold != null) {
                val label = stringResource(
                    if (fold) R.string.headings_expand_heading else R.string.headings_collapse_heading,
                    heading.text,
                )
                val state = stringResource(
                    if (fold) R.string.headings_state_collapsed else R.string.headings_state_expanded,
                )
                Icon(
                    imageVector = if (fold) {
                        Icons.AutoMirrored.Filled.KeyboardArrowRight
                    } else {
                        Icons.Filled.KeyboardArrowDown
                    },
                    contentDescription = null,
                    tint = c.secondaryText,
                    modifier = Modifier
                        .size(28.dp)
                        .focusProperties { canFocus = focusable() }
                        .clickable(role = Role.Button, onClick = onToggleFold)
                        .semantics {
                            contentDescription = label
                            stateDescription = state
                        }
                        .padding(4.dp),
                )
            }
        }
        Text(
            text = heading.text,
            color = c.primaryText,
            fontSize = maxOf(18 - heading.level, 14).sp,
            fontWeight = if (heading.level == 1) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier
                .padding(start = levelIndent.dp)
                .weight(1f)
                .heightIn(min = 32.dp)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .focusProperties { canFocus = focusable() }
                .clickable(role = Role.Button) { onSelected(heading) }
                .wrapContentHeight()
                .padding(top = PilcrowSpacing.xs, bottom = PilcrowSpacing.xs, end = PilcrowSpacing.sm),
        )
    }
}
