package com.example.gravimediaplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.gravimediaplayer.GraviPickerSettings
import com.example.gravimediaplayer.ui.theme.GraviMediaPlayerTheme

@Composable
fun SettingsScreen(
    rootUriString: String?,
    genreSeparator: String,
    showBrowserThumbnails: Boolean,
    graviPickerSettings: GraviPickerSettings,
    onChooseFolder: () -> Unit,
    onGenreSeparatorChanged: (String) -> Unit,
    onApplyGenreSeparator: (String) -> Unit,
    onShowBrowserThumbnailsChanged: (Boolean) -> Unit,
    onGraviPickerSettingsChanged: (GraviPickerSettings) -> Unit,
    onResetSettings: () -> Unit,
    onClearCaches: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 12.dp, top = 12.dp, end = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Button(onClick = onChooseFolder) {
            Text(if (rootUriString == null) "Choose music folder" else "Change music folder")
        }
        SwitchSettingRow(
            label = "Show browser thumbnails",
            checked = showBrowserThumbnails,
            onCheckedChanged = onShowBrowserThumbnailsChanged,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = genreSeparator,
                onValueChange = onGenreSeparatorChanged,
                label = { Text("Genre separator") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { onApplyGenreSeparator(genreSeparator) }) {
                Text("Apply")
            }
        }
        Text(
            "Gravi shuffle",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        SwitchSettingRow(
            label = "Parent odds",
            checked = graviPickerSettings.parentOdds,
            onCheckedChanged = {
                onGraviPickerSettingsChanged(graviPickerSettings.copy(parentOdds = it).sanitized())
            },
        )
        SwitchSettingRow(
            label = "Child odds",
            checked = graviPickerSettings.childOdds,
            onCheckedChanged = {
                onGraviPickerSettingsChanged(graviPickerSettings.copy(childOdds = it).sanitized())
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IntegerSettingField(
                label = "Queue entries",
                value = graviPickerSettings.queueEntries,
                onValueChanged = {
                    onGraviPickerSettingsChanged(
                        graviPickerSettings.copy(queueEntries = it).sanitized()
                    )
                },
                modifier = Modifier.weight(1f),
            )
            IntegerSettingField(
                label = "Depth",
                value = graviPickerSettings.depth,
                onValueChanged = {
                    onGraviPickerSettingsChanged(graviPickerSettings.copy(depth = it).sanitized())
                },
                modifier = Modifier.weight(1f),
            )
        }
        IntegerSettingField(
            label = "Even odds min file count",
            value = graviPickerSettings.evenOddsMinFileCount,
            onValueChanged = {
                onGraviPickerSettingsChanged(
                    graviPickerSettings.copy(evenOddsMinFileCount = it).sanitized()
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        DecimalSettingField(
            label = "Less likely divisor",
            value = graviPickerSettings.lessLikelyDivisor,
            onValueChanged = {
                onGraviPickerSettingsChanged(
                    graviPickerSettings.copy(lessLikelyDivisor = it).sanitized()
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onClearCaches,
                modifier = Modifier.weight(1f),
            ) {
                Text("Clear cache")
            }
            Button(
                onClick = onResetSettings,
                modifier = Modifier.weight(1f),
            ) {
                Text("Reset settings")
            }
        }
    }
}

@Composable
private fun IntegerSettingField(
    label: String,
    value: Int,
    onValueChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { newValue ->
            newValue.toIntOrNull()?.let(onValueChanged)
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = modifier,
    )
}

@Composable
private fun DecimalSettingField(
    label: String,
    value: Float,
    onValueChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { newValue ->
            newValue.toFloatOrNull()?.let(onValueChanged)
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = modifier,
    )
}

@Composable
private fun SwitchSettingRow(
    label: String,
    checked: Boolean,
    onCheckedChanged: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChanged)
    }
}

@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
    GraviMediaPlayerTheme {
        SettingsScreen(
            rootUriString = null,
            genreSeparator = ";",
            showBrowserThumbnails = false,
            graviPickerSettings = GraviPickerSettings(),
            onChooseFolder = {},
            onGenreSeparatorChanged = {},
            onApplyGenreSeparator = {},
            onShowBrowserThumbnailsChanged = {},
            onGraviPickerSettingsChanged = {},
            onResetSettings = {},
            onClearCaches = {},
        )
    }
}
