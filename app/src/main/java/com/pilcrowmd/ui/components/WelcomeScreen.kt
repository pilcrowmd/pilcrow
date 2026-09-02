// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import android.graphics.Paint
import android.graphics.Rect
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import com.pilcrowmd.R
import com.pilcrowmd.ui.theme.mdColors
import com.pilcrowmd.ui.theme.sourceSerif4Family
import com.pilcrowmd.viewmodel.RecentFileUi
import kotlin.math.ceil

private const val PILCROW = "¶"

/**
 * Test handle for the ¶ mark. It is decorative — no text, no content description — and a golden
 * cannot bound it: the welcome goldens' tolerance passes a 40dp shift, and the wordmark's
 * antialiased edges fall inside the mark's own luminance band, so no threshold reads it off an
 * image either. A semantics tag is what is left to assert its placement from.
 */
const val WELCOME_WATERMARK_TEST_TAG = "welcome-watermark"

/**
 * The share of its line the "PilcrowMD" lockup may occupy before it is scaled down to fit.
 *
 * This is a SAFETY MARGIN, not a taste decision. At the S24+'s real portrait viewport the lockup at
 * its design size measured 895px against 900px available — a 0.55% margin — and the final "D" left
 * the line on a physical device at the largest OS font size while every emulator and unit test
 * reported it fitting. Below 384dp it did not fit at all: `maxLines = 1` clipped the "D" outright.
 * Fitting to a budget rather than to the exact width is what stops a sub-percent difference in text
 * advance — a font revision, a rasteriser, an accessibility setting we have not met — from taking
 * the last glyph off the release's headline element again.
 */
private const val WORDMARK_WIDTH_BUDGET = 0.92f

/** The lockup's design size. Density-pixels, so the OS font scale never changes how big it draws. */
private val WORDMARK_SIZE = 58.dp

/**
 * Welcome / start screen (shown when no file is open). Visual design per the
 * reference mockup: faint ¶ pilcrow serif watermark (upper third), purple-accent
 * serif wordmark, sparkle divider, subtitle, cream "Open MD File" button, recents
 * list, footer. All colors from the token layer.
 */
/**
 * Places [content] under a leading gap of [maxGap] where the viewport has room for it, and shrinks
 * that gap by exactly as much as it must where the viewport does not — so the last thing in
 * [content] is never pushed past the bottom of the screen.
 *
 * There is deliberately NO "short viewport" threshold here. A tuned screen constant is what put the
 * Open button off the bottom in landscape in the first place, and any constant picked today is wrong
 * on a phone nobody here has measured. The gap is whatever [viewportHeight] has left once [content]
 * has been measured, clamped to [maxGap]: tall screens clamp and look exactly as before, short ones
 * give back only the difference, and foldables, tablets and split-screen are covered without anyone
 * having tested them because nothing is keyed to a particular screen.
 *
 * [viewportHeight] must be passed in: this sits inside a vertical scroll, where the incoming max
 * height is infinite, so the layout cannot discover the visible height for itself.
 */
@Composable
private fun YieldingTopGap(
    viewportHeight: Dp,
    maxGap: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val placeable = measurables.single().measure(constraints)
        val gap = (viewportHeight.roundToPx() - placeable.height)
            .coerceIn(0, maxGap.roundToPx())
        layout(placeable.width, gap + placeable.height) {
            placeable.placeRelative(0, gap)
        }
    }
}

/**
 * The faint ¶ brand mark, laid out so that **the top edge of its ink is the top edge of this
 * composable**. That is the whole point of it existing rather than a `Text`.
 *
 * A `Text` is laid out to the FONT's line box, and at this size Source Serif 4's ascent leaves
 * ~106dp of empty leading above the ¶'s ink. Anchoring the ink to anything therefore means
 * cancelling that leading, and the only way to cancel it on a `Text` is a negative offset — which
 * drags the glyph's clip rectangle up with it and strands a hard flat edge in mid-screen. So the
 * glyph is measured and drawn directly instead: [android.graphics.Paint.getTextBounds] returns the
 * INK box (relative to the drawing origin, y measured from the baseline and negative above it),
 * which is reported as this layout's height and used to place the baseline. There is no leading to
 * cancel and no negative offset anywhere.
 *
 * The reported WIDTH is the glyph's ADVANCE, not its ink, so that centring this composable places
 * the mark exactly where centring the old `Text` did — the mark moves vertically in this change and
 * in no other direction.
 *
 * The size is taken in [Dp] and converted at the current density on purpose: the mark is a brand
 * graphic, not body text, and must not scale with the OS accessibility font setting.
 */
@Composable
private fun PilcrowInkMark(fontSize: Dp, color: Color, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val paint = remember(context, density, fontSize, color) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = ResourcesCompat.getFont(context, R.font.source_serif_4_bold)
            textSize = with(density) { fontSize.toPx() }
            this.color = color.toArgb()
        }
    }
    val ink = remember(paint) { Rect().also { paint.getTextBounds(PILCROW, 0, PILCROW.length, it) } }
    Layout(
        modifier = modifier
            .testTag(WELCOME_WATERMARK_TEST_TAG)
            .drawBehind {
                // ink.top is NEGATIVE (the ink starts above the baseline), so a baseline at
                // -ink.top puts the ink's top edge on this node's own top edge — the anchor.
                drawIntoCanvas { it.nativeCanvas.drawText(PILCROW, 0f, -ink.top.toFloat(), paint) }
            },
    ) { _, _ ->
        layout(ceil(paint.measureText(PILCROW)).toInt(), ink.height()) {}
    }
}

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
    // The wordmark is a brand graphic, not body text: size it in density-pixels (dp → sp at the
    // current density) so it stays stable under the OS accessibility font scale instead of
    // ballooning/truncating. Identical to sp at the default scale. (The ¶ mark is sized the same
    // way, in [PilcrowInkMark], which takes its size in dp directly.)
    //
    // The two edges the ¶ mark is anchored between, in root coordinates, so their difference is
    // this Box's own top-to-gear-bottom distance whatever sits above the screen. Written by
    // onGloballyPositioned during layout; read one frame later. Measuring beats deriving here: the
    // gear's bottom is 4dp of padding plus half a minimum-touch-target IconButton plus half a 22dp
    // glyph, and three numbers that must be kept in step by hand is exactly the kind of tuned
    // arithmetic this rule exists to replace.
    var boxTopInRoot by remember { mutableFloatStateOf(Float.NaN) }
    var gearBottomInRoot by remember { mutableFloatStateOf(Float.NaN) }
    val wordmarkFontSize = with(LocalDensity.current) { WORDMARK_SIZE.toSp() }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(c.primaryBackground)
            .onGloballyPositioned { boxTopInRoot = it.positionInRoot().y },
    ) {
        // Large faint ¶ mark behind the wordmark. It is BACKGROUND TEXTURE, not a frame: its ink
        // is narrower than the wordmark and the word overhangs it, which is intentional.
        //
        // WHERE IT SITS IS A RULE, NOT A NUMBER: the TOP EDGE OF THE ¶'s INK sits on the BOTTOM
        // EDGE OF THE SETTINGS GEAR, in both orientations. That edge is read from the layout below
        // (`gearBottomInRoot`), so moving the gear, repadding it or resizing its glyph moves the
        // mark with it. The figure it replaced — `padding(top = 40.dp)` — was a tuned number that
        // put the mark far too low in landscape and let the wordmark cross its bowl in portrait.
        //
        // DO NOT enlarge it. DO NOT give it a negative offset. The offset ban is why this is drawn
        // by [PilcrowInkMark] and not by a `Text`: a `Text` is 106dp of empty leading above the
        // glyph's ink at this size, so anchoring its INK to an edge 39dp down the screen would have
        // meant placing the Text at -67dp. A negative offset drags the clip rectangle up with the
        // glyph and strands a hard flat edge in MID-SCREEN — that defect reached a device once, at
        // 620dp with offset(y = -206.dp). [PilcrowInkMark] has no leading to cancel: its box IS the
        // ink, so a POSITIVE padding expresses the rule exactly, and the only cut that remains is
        // the screen's own bottom edge, which is flush and invisible.
        //
        // 280dp is the ORIGINAL size and not a free parameter. It was enlarged to 324dp during the
        // PilcrowMD rename on the theory that a wider wordmark needed a wider mark behind it, and the
        // device rejected that: the ¶ reached down to the Open button in portrait, and in landscape
        // the wordmark sat on the bottom edge.
        //
        // DO NOT tune toward "it clears the viewport". At 280dp it does not clear the S24+ — it bleeds
        // a few dp off the bottom edge in landscape, which is the harmless flush case above, and is
        // what the pre-rename composition did too. No size clears every phone; a 360dp-wide one
        // overruns even at 280dp. So there is no dp figure here on purpose: ASK THE DEVICE with
        // `adb shell wm size` and `adb shell wm density`, and never read geometry off a golden. A
        // hardcoded viewport in this comment once went stale against the hardware and a whole round
        // of reasoning was correct arithmetic on a screen nobody owns.
        //
        // The goldens do NOT guard this placement and must not be cited as if they did: their 0.05
        // tolerance passes a 40dp shift and an 11% size change over a mark drawn at 0.06 alpha, and
        // the wordmark's antialiased edges fall inside the mark's own luminance band, so no
        // threshold can bound the glyph from an image either. WelcomeWatermarkAnchorTest reads the
        // rule off the layout instead, in both orientations.
        //
        // Composed only once the gear has been measured — one frame later, at 6% alpha, which is
        // why this is a plain null check and not a placeholder.
        // NaN until both edges have been reported; NaN - NaN is NaN, so one check covers both.
        val gearBottom = with(LocalDensity.current) {
            (gearBottomInRoot - boxTopInRoot).takeIf { it.isFinite() }?.toDp()
        }
        if (gearBottom != null) {
            PilcrowInkMark(
                fontSize = 280.dp,
                color = c.primaryText.copy(alpha = 0.06f),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = gearBottom),
            )
        }

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
                        // The lead gap is 206dp WHERE THERE IS ROOM, and yields where there is not, so
                        // the Open button is never pushed off the bottom. What is reserved runs
                        // through the "Browse all files" line BELOW the button, not just the button:
                        // reserving to the button alone parks it flush against the bottom edge with
                        // no breathing room and pushes the fallback link off screen, which a device
                        // pass rejected. 206dp rather than the
                        // original 186dp because the wordmark font dropped 72dp -> 58dp to fit the
                        // longer "PilcrowMD": the text block is shorter, and the 20dp puts the
                        // divider, tagline and CTA back on their original rows.
                        YieldingTopGap(viewportHeight = viewportHeight, maxGap = 206.dp) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                // Serif wordmark over the faint ¶ watermark (drawn in the outer Box). One
                                // continuous string at a uniform size: the accent brackets the lockup at both
                                // ends (P … MD) so "MD" reads as part of the mark, not as a suffix or a title.
                                //
                                // SHRINK TO FIT THE LINE, never grow past the design size. The lockup is
                                // measured against the width it actually has and scaled down only if it
                                // does not fit inside [WORDMARK_WIDTH_BUDGET]; where it already fits it
                                // renders untouched. `softWrap = false` is the second half of the rule and
                                // the more important half: should a fit ever be missed, the run must run
                                // past its box rather than break "PilcrowMD" in the middle of a word.
                                val wordmark = buildAnnotatedString {
                                    withStyle(SpanStyle(color = c.accent)) { append("P") }
                                    withStyle(SpanStyle(color = c.primaryText)) { append("ilcrow") }
                                    withStyle(SpanStyle(color = c.accent)) { append("MD") }
                                }
                                val measurer = rememberTextMeasurer()
                                BoxWithConstraints(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    val style = TextStyle(
                                        fontFamily = sourceSerif4Family,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = wordmarkFontSize,
                                    )
                                    val budget = constraints.maxWidth * WORDMARK_WIDTH_BUDGET
                                    val natural = measurer
                                        .measure(wordmark, style, softWrap = false, maxLines = 1)
                                        .size.width
                                    // SCALE IN DP, NOT SP. Above the OS's large font settings the sp->px
                                    // conversion is non-linear (API 34+ `FontScaleConverter`), so
                                    // multiplying an sp size by a fraction does NOT shrink the drawn text
                                    // by that fraction — measured here at 842px against an 828px budget
                                    // when the arithmetic was done in sp. Dp is linear, and `toSp()` is an
                                    // exact inverse of the converter, so doing the arithmetic in dp and
                                    // converting once lands on the size that was actually asked for.
                                    val fittedDp = if (natural == 0 || natural <= budget) {
                                        WORDMARK_SIZE
                                    } else {
                                        WORDMARK_SIZE * (budget / natural)
                                    }
                                    val fitted = with(LocalDensity.current) { fittedDp.toSp() }
                                    Text(
                                        text = wordmark,
                                        fontFamily = sourceSerif4Family,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = fitted,
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                }

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
                            }
                        }

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
                // The ¶ mark's anchor: the gear GLYPH's bottom edge, not the 48dp touch target's.
                modifier = Modifier
                    .size(22.dp)
                    .onGloballyPositioned {
                        gearBottomInRoot = it.positionInRoot().y + it.size.height
                    },
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
