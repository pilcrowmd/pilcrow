// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.pilcrowmd.R
import com.pilcrowmd.domain.model.PdfColors
import com.pilcrowmd.ui.theme.mdColors

@StringRes
internal fun PdfColors.labelRes(): Int = when (this) {
    PdfColors.LIGHT_FOR_PRINTING -> R.string.settings_pdf_colors_light
    PdfColors.MATCH_APP_THEME -> R.string.settings_pdf_colors_theme
}

/** The PDF colors choice, built like [LanguageDialog]: choosing a row reports it and closes. */
@Composable
internal fun PdfColorsDialog(selected: PdfColors, onSelected: (PdfColors) -> Unit, onDismiss: () -> Unit) {
    val c = mdColors()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.secondarySurface,
        titleContentColor = c.primaryText,
        textContentColor = c.secondaryText,
        title = { Text(stringResource(R.string.settings_pdf_colors)) },
        text = {
            Column(Modifier.selectableGroup()) {
                PdfColors.entries.forEach { choice ->
                    val isSelected = choice == selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = isSelected, role = Role.RadioButton) {
                                onSelected(choice)
                                onDismiss()
                            },
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(
                                selectedColor = c.accent,
                                unselectedColor = c.secondaryText,
                            ),
                        )
                        Text(text = stringResource(choice.labelRes()), color = c.primaryText)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_language_cancel), color = c.primaryText)
            }
        },
    )
}
