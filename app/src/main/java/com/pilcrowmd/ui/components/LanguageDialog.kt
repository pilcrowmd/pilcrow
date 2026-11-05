// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
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
import com.pilcrowmd.ui.theme.mdColors

/** The words that name [language] in the Settings Language row and dialog (System default is the one resource). */
@Composable
internal fun AppLanguage.label(): String = nativeName ?: stringResource(R.string.settings_language_system)

/**
 * Settings Language dialog (languages spec D1): one radio row per [AppLanguage], System default first.
 * Choosing a row reports it and closes; Cancel closes without a choice.
 */
@Composable
internal fun LanguageDialog(selected: AppLanguage, onSelected: (AppLanguage) -> Unit, onDismiss: () -> Unit) {
    val c = mdColors()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.secondarySurface,
        titleContentColor = c.primaryText,
        textContentColor = c.secondaryText,
        title = { Text(stringResource(R.string.settings_language)) },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                AppLanguage.entries.forEach { language ->
                    val isSelected = language == selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = isSelected, role = Role.RadioButton) {
                                onSelected(language)
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
                        Text(text = language.label(), color = c.primaryText)
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
