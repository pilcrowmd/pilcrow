// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pilcrowmd.ui.theme.mdColors
import org.json.JSONArray

/**
 * LicensesScreen: Native Compose modal displaying all third-party dependencies and their licenses.
 *
 * Loads dependency list from bundled JSON (121 runtime deps) + 6 curated entries (5 fonts + vendored grammar).
 * Tappable rows → detail view with full license text. Token-colored. Graceful degradation on missing assets.
 */
@Composable
fun LicensesScreen(modifier: Modifier = Modifier, onClose: () -> Unit) {
    val context = LocalContext.current
    var selectedLicense by rememberSaveable { mutableStateOf<License?>(null) }

    if (selectedLicense != null) {
        LicenseDetailView(
            license = selectedLicense!!,
            onBack = { selectedLicense = null },
        )
    } else {
        LicensesListView(
            modifier = modifier,
            context = context,
            onSelectLicense = { selectedLicense = it },
            onClose = onClose,
        )
    }
}

/**
 * A single license entry (from JSON or curated).
 */
data class License(val name: String, val version: String? = null, val license: String, val description: String? = null)

/**
 * Licenses list (LazyColumn of all deps + curated entries).
 */
@Composable
fun LicensesListView(
    modifier: Modifier = Modifier,
    context: Context,
    onSelectLicense: (License) -> Unit,
    onClose: () -> Unit,
) {
    val c = mdColors()
    val dependencies = loadDependencies(context)

    Column(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding()
            .background(c.primaryBackground),
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Open Source Licenses",
                color = c.primaryText,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Icon(
                imageVector = Icons.Outlined.ArrowBack,
                contentDescription = "Close",
                tint = c.secondaryText,
                modifier = Modifier
                    .size(24.dp)
                    .clickable { onClose() },
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // The app's OWN license (GPL-3.0-or-later) — kept distinct from the third-party notices below.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(c.secondarySurface)
                .padding(horizontal = 12.dp, vertical = 11.dp),
        ) {
            Text(
                text = "Pilcrow",
                color = c.primaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "© 2026 pleree — free software under the GNU General Public " +
                    "License v3.0 or later (GPL-3.0-or-later). You may redistribute and/or modify " +
                    "it under the terms of the GPL; it comes with NO WARRANTY.",
                color = c.secondaryText,
                fontSize = 12.sp,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "The third-party components below have their own licenses.",
                color = c.secondaryText,
                fontSize = 12.sp,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Scrollable list of dependencies and curated entries
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(dependencies) { license ->
                LicenseRowItem(
                    license = license,
                    onSelect = { onSelectLicense(license) },
                )
            }
        }
    }
}

/**
 * A single license row (clickable).
 */
@Composable
fun LicenseRowItem(license: License, onSelect: () -> Unit) {
    val c = mdColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.secondarySurface)
            .clickable { onSelect() }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = license.name,
                color = c.primaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            if (license.version != null) {
                Text(
                    text = "${license.version} • ${license.license}",
                    color = c.secondaryText,
                    fontSize = 12.sp,
                )
            } else {
                Text(
                    text = license.license,
                    color = c.secondaryText,
                    fontSize = 12.sp,
                )
            }
        }
        Icon(
            imageVector = Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = c.secondaryText,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * Detail view: shows full license text for a selected dependency.
 */
@Composable
fun LicenseDetailView(license: License, onBack: () -> Unit) {
    val c = mdColors()
    val context = LocalContext.current
    val licenseText = loadLicenseText(context, license.license)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .background(c.primaryBackground),
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = license.name,
                    color = c.primaryText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (license.version != null) {
                    Text(
                        text = "${license.version} • ${license.license}",
                        color = c.secondaryText,
                        fontSize = 12.sp,
                    )
                } else {
                    Text(
                        text = license.license,
                        color = c.secondaryText,
                        fontSize = 12.sp,
                    )
                }
            }
            Icon(
                imageVector = Icons.Outlined.ArrowBack,
                contentDescription = "Back",
                tint = c.secondaryText,
                modifier = Modifier
                    .size(24.dp)
                    .clickable { onBack() },
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // License text (scrollable)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(c.secondarySurface)
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Column {
                Text(
                    text = licenseText,
                    color = c.primaryText,
                    fontSize = 11.sp,
                )

                // Special notes for certain licenses
                if (license.license.contains("GPL", ignoreCase = true)) {
                    Spacer(modifier = Modifier.height(16.dp))
                    val gplNote = "Note: This library is licensed under GPLv2 with " +
                        "the Classpath Exception, which permits linking into non-GPL " +
                        "applications. See the full license text for details."
                    Text(
                        text = gplNote,
                        color = c.primaryText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                if (license.name.contains("Sora", ignoreCase = true)) {
                    Spacer(modifier = Modifier.height(16.dp))
                    val soraNote = "Note: Sora Editor is licensed under LGPL-2.1. " +
                        "As an unmodified binary dependency, it is used in compliance " +
                        "with LGPL §6 (relink terms). The source code and version are " +
                        "published; users may request the object code or rebuild the " +
                        "app with a modified editor if needed."
                    Text(
                        text = soraNote,
                        color = c.primaryText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/**
 * Load all dependencies from JSON + curated entries.
 * Gracefully handles missing/malformed JSON (shows curated entries only).
 */
fun loadDependencies(context: Context): List<License> {
    val curated = listOf(
        License(name = "Source Serif 4", license = "OFL-1.1"),
        License(name = "JetBrains Mono", license = "OFL-1.1"),
        License(name = "IBM Plex Mono", license = "OFL-1.1"),
        License(name = "Merriweather", license = "OFL-1.1"),
        License(name = "Atkinson Hyperlegible", license = "OFL-1.1"),
        License(name = "VSCode Markdown TextMate Grammar", license = "MIT"),
    )

    // Graceful degradation (Safeguard 3): a missing/malformed JSON asset yields the curated
    // entries only — runCatching keeps the broad failure handling without a generic-catch suppression.
    val jsonDeps = runCatching {
        val jsonString = context.assets.open("open_source_licenses.json")
            .bufferedReader()
            .readText()
        val jsonArray = JSONArray(jsonString)
        (0 until jsonArray.length()).mapNotNull { index ->
            val obj = jsonArray.optJSONObject(index) ?: return@mapNotNull null
            val name = obj.optString("project", "Unknown")
            val version = obj.optString("version", null).takeIf { it?.isNotBlank() == true }
            val licenses = obj.optJSONArray("licenses")
            val license = if (licenses != null && licenses.length() > 0) {
                val licenseObj = licenses.getJSONObject(0)
                licenseObj.optString("license", "Unknown")
            } else {
                "Unknown"
            }
            License(name = name, version = version, license = license)
        }
    }.getOrDefault(emptyList())

    // Combine curated + JSON deps, curated first
    return curated + jsonDeps
}

/**
 * Load license text from assets.
 * Gracefully handles missing/malformed files (shows fallback text, Safeguard 3).
 */
fun loadLicenseText(context: Context, licenseName: String): String {
    val fallback = "License text not available — see LICENSES.md"
    // LGPL must be matched before GPL (LGPL strings also contain "GPL").
    val fileName = when {
        licenseName.contains("OFL", ignoreCase = true) -> "licenses/OFL-1.1.txt"
        licenseName.contains("Apache", ignoreCase = true) -> "licenses/Apache-2.0.txt"
        licenseName.contains("MIT", ignoreCase = true) -> "licenses/MIT.txt"
        licenseName.contains("LGPL", ignoreCase = true) -> "licenses/LGPL-2.1.txt"
        licenseName.contains("GPL", ignoreCase = true) -> "licenses/GPL-2.0-Classpath-Exception.txt"
        else -> return fallback
    }
    // Graceful degradation (Safeguard 3): a missing/unreadable asset falls back, never crashes.
    return runCatching {
        context.assets.open(fileName).bufferedReader().readText()
    }.getOrDefault(fallback)
}
