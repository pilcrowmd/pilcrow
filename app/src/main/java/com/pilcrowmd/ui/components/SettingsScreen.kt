// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pilcrowmd.domain.model.ThemeMode
import com.pilcrowmd.ui.theme.FontSet
import com.pilcrowmd.ui.theme.FontSets
import com.pilcrowmd.ui.theme.PilcrowTypography
import com.pilcrowmd.ui.theme.mdColors
import kotlin.math.roundToInt

/**
 * Full-screen Settings modal, reachable from the Welcome screen.
 *
 * Compact card layout grouped into APPEARANCE / INTEGRATIONS / PRO PLUGINS / ABOUT.
 * All colors come from the token layer (Safeguard 4 — no hardcoded hex).
 */
@Suppress("LongParameterList")
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    fontSetId: String = "source",
    onFontSetSelected: (String) -> Unit = {},
    previewFontScale: Float = 1.0f,
    onPreviewFontScaleChanged: (Float) -> Unit = {},
    editorFontScale: Float = 1.0f,
    onEditorFontScaleChanged: (Float) -> Unit = {},
    lineNumbersEnabled: Boolean = true,
    onLineNumbersChanged: (Boolean) -> Unit = {},
    mermaidCloudEnabled: Boolean = false,
    onMermaidCloudChanged: (Boolean) -> Unit = {},
    themeMode: ThemeMode = ThemeMode.DARK,
    onThemeSelected: (ThemeMode) -> Unit = {},
    appVersion: String = "",
    onClose: () -> Unit = {},
    onOpenLicenses: () -> Unit = {},
    onOpenGitHub: () -> Unit = {},
) {
    val c = mdColors()
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(c.primaryBackground)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        // Header — title + subtitle, circular close button.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "Settings",
                    style = PilcrowTypography.h3Style,
                    color = c.primaryText,
                )
                Text(
                    text = "Customize your reading experience",
                    color = c.secondaryText,
                    fontSize = 12.sp,
                )
            }
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(c.secondarySurface)
                    .clickable { onClose() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Close Settings",
                    tint = c.primaryText,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // ───────────── APPEARANCE ─────────────
        SectionLabel("Appearance")

        SettingsCard {
            CardTitle("Theme")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeOption(
                    icon = Icons.Outlined.DarkMode,
                    label = "Dark",
                    selected = themeMode == ThemeMode.DARK,
                    onClick = { onThemeSelected(ThemeMode.DARK) },
                )
                ThemeOption(
                    icon = Icons.Outlined.LightMode,
                    label = "Light",
                    selected = themeMode == ThemeMode.LIGHT,
                    onClick = { onThemeSelected(ThemeMode.LIGHT) },
                )
            }
        }

        CardGap()
        SettingsCard {
            CardTitle("Reading & code font")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FontSets.ALL.forEach { set ->
                    FontPill(set = set, selected = set.id == fontSetId, onClick = { onFontSetSelected(set.id) })
                }
            }
        }

        CardGap()
        SettingsCard {
            CardTitle("Preview text size")
            Spacer(Modifier.height(4.dp))
            SizeControl(scale = previewFontScale, onChange = onPreviewFontScaleChanged)
            Spacer(Modifier.height(8.dp))
            // Live one-line sample in the selected reading font, sized to the preview scale.
            FontSizeSample(
                text = "The quick brown fox jumps over the lazy dog.",
                family = FontSets.byId(fontSetId).readingFamily,
                sizeSp = PilcrowTypography.PROSE_BODY_FONT_SIZE_SP * previewFontScale,
            )
        }

        CardGap()
        SettingsCard {
            CardTitle("Edit text size")
            Spacer(Modifier.height(4.dp))
            SizeControl(scale = editorFontScale, onChange = onEditorFontScaleChanged)
            Spacer(Modifier.height(8.dp))
            // Live one-line sample in the selected mono font, sized to the editor scale.
            FontSizeSample(
                text = "val sample = 42  // editor preview",
                family = FontSets.byId(fontSetId).monoFamily,
                sizeSp = PilcrowTypography.CODE_BLOCK_FONT_SIZE_SP * editorFontScale,
            )
        }

        CardGap()
        SettingsCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    CardTitle("Line numbers")
                    Text(
                        text = "Show line numbers in the editor",
                        color = c.secondaryText,
                        fontSize = 12.sp,
                    )
                }
                Switch(
                    checked = lineNumbersEnabled,
                    onCheckedChange = onLineNumbersChanged,
                    modifier = Modifier.scale(0.8f),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = c.primaryText,
                        checkedTrackColor = c.accent,
                        uncheckedThumbColor = c.secondaryText,
                        uncheckedTrackColor = c.secondarySurface,
                    ),
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        // ───────────── INTEGRATIONS ─────────────
        SectionLabel("Integrations")
        InfoCard(
            leadingIcon = Icons.Outlined.Cloud,
            title = "GitHub integration",
            subtitle = "Open and browse Markdown from your repos.",
            onClick = onOpenGitHub,
            trailing = {
                ComingSoonBadge()
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = c.secondaryText,
                    modifier = Modifier.size(20.dp),
                )
            },
        )

        Spacer(Modifier.height(18.dp))

        // ───────────── PRO PLUGINS ─────────────
        SectionLabel("Pro Plugins")
        // LaTeX already renders natively/offline — show it as a built-in feature.
        ProPluginCard(
            glyph = "{x}",
            title = "LaTeX Math",
            subtitle = "Render beautiful math equations.",
            comingSoon = false,
        )
        CardGap()
        // Mermaid — opt-in cloud rendering, OFF by default.
        MermaidToggleCard(checked = mermaidCloudEnabled, onCheckedChange = onMermaidCloudChanged)

        Spacer(Modifier.height(18.dp))

        // ───────────── ABOUT ─────────────
        AboutCard(appVersion = appVersion, onOpenLicenses = onOpenLicenses)

        Spacer(Modifier.height(16.dp))
    }
}

// ── Building blocks ────────────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String) {
    val c = mdColors()
    Text(
        text = text.uppercase(),
        color = c.secondaryText,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    val c = mdColors()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.secondarySurface)
            .border(1.dp, c.toolbarBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 11.dp),
        content = content,
    )
}

@Composable
private fun CardGap() = Spacer(Modifier.height(8.dp))

@Composable
private fun CardTitle(text: String) {
    val c = mdColors()
    Text(
        text = text,
        color = c.primaryText,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
    )
}

/** A theme choice card (icon + label, optional "coming soon" subtitle). */
@Suppress("LongParameterList")
@Composable
private fun RowScope.ThemeOption(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    subtitle: String? = null,
    onClick: () -> Unit = {},
) {
    val c = mdColors()
    val contentColor = when {
        !enabled -> c.secondaryText.copy(alpha = 0.6f)
        selected -> c.accent
        else -> c.primaryText
    }
    val borderColor = if (selected) c.accent else c.border
    Row(
        modifier = Modifier
            .weight(1f)
            .height(40.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(
                if (selected) c.accent.copy(alpha = 0.12f) else c.primaryBackground,
            )
            .border(1.dp, borderColor, RoundedCornerShape(9.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(16.dp))
        Column {
            Text(text = label, color = contentColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Text(text = subtitle, color = c.secondaryText.copy(alpha = 0.7f), fontSize = 10.sp)
            }
        }
    }
}

/** A selectable font pill; its label is shown in that set's own reading font. */
@Composable
private fun RowScope.FontPill(set: FontSet, selected: Boolean, onClick: () -> Unit) {
    val c = mdColors()
    Box(
        modifier = Modifier
            .weight(1f)
            .height(34.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) c.accent else c.primaryBackground)
            .border(
                1.dp,
                if (selected) c.accent else c.border,
                RoundedCornerShape(9.dp),
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = set.displayName,
            color = if (selected) c.primaryBackground else c.primaryText,
            fontFamily = set.readingFamily,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

private const val SCALE_MIN_PCT = 85
private const val SCALE_MAX_PCT = 160
private const val PERCENT = 100
private const val STEP_PCT = 5

/**
 * Scale (0.85–1.6) as a clean integer percent. `roundToInt` (not `toInt`) — 1.05f is really
 * ~1.0499999, so truncation produced "104"; rounding gives 105.
 */
internal fun scaleToPercent(scale: Float): Int = (scale * PERCENT).roundToInt()

/**
 * Step the scale by [deltaPct] off its nearest 5% stop, clamped to range, in integer-percent
 * space so A−/A+ always land on …95, 100, 105, 110… (no float drift).
 */
internal fun steppedScale(scale: Float, deltaPct: Int): Float {
    val nearestFivePct = (scaleToPercent(scale).toFloat() / STEP_PCT).roundToInt() * STEP_PCT
    return (nearestFivePct + deltaPct).coerceIn(SCALE_MIN_PCT, SCALE_MAX_PCT).toFloat() / PERCENT
}

/** Small-A / slider / big-A with a live % readout. The A glyphs step the value. */
@Composable
private fun SizeControl(scale: Float, onChange: (Float) -> Unit) {
    val c = mdColors()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "A",
            color = c.secondaryText,
            fontSize = 12.sp,
            modifier = Modifier
                .clip(CircleShape)
                .clickable { onChange(steppedScale(scale, -5)) }
                .padding(4.dp),
        )
        Slider(
            value = scale,
            onValueChange = { onChange((it * 100f).roundToInt() / 100f) }, // snap to whole-% steps
            valueRange = 0.85f..1.6f,
            // 75 stops = 1% increments across 85%–160%; 100% lands exactly on the grid.
            steps = 74,
            modifier = Modifier
                .weight(1f)
                .height(28.dp)
                .padding(horizontal = 4.dp),
            colors = SliderDefaults.colors(
                thumbColor = c.accent,
                activeTrackColor = c.accent,
                inactiveTrackColor = c.border,
            ),
        )
        Text(
            text = "A",
            color = c.secondaryText,
            fontSize = 19.sp,
            modifier = Modifier
                .clip(CircleShape)
                .clickable { onChange(steppedScale(scale, +5)) }
                .padding(4.dp),
        )
        Box(
            modifier = Modifier
                .padding(horizontal = 10.dp)
                .width(1.dp)
                .height(18.dp)
                .background(c.border),
        )
        Text(
            text = "${scaleToPercent(scale)}%",
            color = c.accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            modifier = Modifier.width(40.dp),
        )
    }
}

/**
 * A single live preview line under a SizeControl, rendered in [family] at [sizeSp] so the
 * user can judge the chosen size in place while dragging the slider. One line, ellipsized so the
 * card height stays stable at large scales. Color from the token layer (Safeguard 4).
 */
@Composable
private fun FontSizeSample(text: String, family: FontFamily, sizeSp: Float) {
    Text(
        text = text,
        color = mdColors().primaryText,
        fontFamily = family,
        fontSize = sizeSp.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A generic icon + title + subtitle row card with an optional trailing slot. */
@Composable
private fun InfoCard(
    leadingIcon: ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = mdColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.secondarySurface)
            .border(1.dp, c.toolbarBorder, RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = leadingIcon,
            contentDescription = null,
            tint = c.accent,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            CardTitle(title)
            Text(text = subtitle, color = c.secondaryText, fontSize = 12.sp)
        }
        if (trailing != null) trailing()
    }
}

/** A small accent "Coming soon" pill (token colours only, Safeguard 4). */
@Composable
private fun ComingSoonBadge() {
    val c = mdColors()
    Text(
        text = "Coming soon",
        color = c.onAccent,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(c.accent)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/**
 * A pro-plugin row: leading glyph/icon, title, subtitle. When [comingSoon] it shows a
 * "Coming soon" + lock; otherwise a "Built-in" badge (the feature is active).
 */
@Composable
private fun ProPluginCard(
    title: String,
    subtitle: String,
    glyph: String? = null,
    leadingIcon: ImageVector? = null,
    comingSoon: Boolean = true,
) {
    val c = mdColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.secondarySurface)
            .border(1.dp, c.toolbarBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (glyph != null) {
                Text(
                    text = glyph,
                    color = c.accent,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
            } else if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = c.accent,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            CardTitle(title)
            Text(text = subtitle, color = c.secondaryText, fontSize = 12.sp)
        }
        if (comingSoon) {
            Text(text = "Coming soon", color = c.secondaryText, fontSize = 11.sp)
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = null,
                tint = c.secondaryText.copy(alpha = 0.7f),
                modifier = Modifier.size(14.dp),
            )
        } else {
            Text(
                text = "Built-in",
                color = c.accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** Mermaid opt-in cloud-render toggle. Off by default; explains the network use. */
@Composable
private fun MermaidToggleCard(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val c = mdColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.secondarySurface)
            .border(1.dp, c.toolbarBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.AccountTree,
            contentDescription = null,
            tint = c.accent,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            CardTitle("Mermaid Diagrams")
            Text(
                text = "Off by default. Turning this on sends each diagram's source text over the internet " +
                    "to mermaid.ink — a third-party service — which renders it as an image. The app makes no " +
                    "other network connections.",
                color = c.secondaryText,
                fontSize = 12.sp,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.scale(0.8f),
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.primaryText,
                checkedTrackColor = c.accent,
                uncheckedThumbColor = c.secondaryText,
                uncheckedTrackColor = c.secondarySurface,
            ),
        )
    }
}

/** About row that expands to show version + OSS/font credits. Expanded by default. */
@Composable
private fun AboutCard(appVersion: String, onOpenLicenses: () -> Unit = {}) {
    val c = mdColors()
    var expanded by remember { mutableStateOf(true) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.secondarySurface)
            .border(1.dp, c.toolbarBorder, RoundedCornerShape(12.dp))
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = c.accent,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = "About Pilcrow",
                color = c.primaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (appVersion.isNotEmpty()) "v$appVersion" else "",
                color = c.secondaryText,
                fontSize = 12.sp,
            )
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = c.secondaryText,
                modifier = Modifier.size(20.dp),
            )
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "A native Markdown reader & editor for Android.",
                color = c.secondaryText,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Built with",
                color = c.primaryText,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(3.dp))
            listOf(
                "Markwon — Markdown rendering (Apache-2.0)",
                "Prism4j — syntax highlighting (Apache-2.0)",
                "Sora Editor — code editor (LGPL-2.1)",
                "JLatexMath — math rendering (GPL w/ classpath)",
                "Source Serif 4, Merriweather, Atkinson Hyperlegible, JetBrains Mono, IBM Plex Mono (OFL)",
            ).forEach { credit ->
                Text(
                    text = "• $credit",
                    color = c.secondaryText,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpenLicenses() }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Open source licenses",
                    color = c.primaryText,
                    fontSize = 12.sp,
                )
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = c.secondaryText,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
