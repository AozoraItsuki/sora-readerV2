package app.sorareader.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.sorareader.data.settings.ThemeMode
import app.sorareader.ui.AppViewModelProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory)) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    Scaffold(topBar = { LargeTopAppBar(title = { Text("Settings") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { inner ->
        Column(Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState())) {
            Section("Appearance")
            ThemeSelector(prefs.reader.theme, vm::setTheme)
            Toggle("Dynamic color", "Use Material You colors on Android 12+", prefs.dynamicColor, vm::setDynamicColor)
            Toggle("AMOLED dark", "Use pure black in dark mode", prefs.amoled, vm::setAmoled)

            Section("Reader")
            SliderSetting(
                title = "Font size",
                valueText = "${prefs.reader.fontSize.toInt()}sp",
                value = prefs.reader.fontSize,
                range = 12f..30f,
                steps = 8,
                onChange = vm::setFontSize,
            )
            SliderSetting(
                title = "Line height",
                valueText = String.format("%.1f", prefs.reader.lineHeight),
                value = prefs.reader.lineHeight,
                range = 1f..2.4f,
                steps = 6,
                onChange = vm::setLineHeight,
            )
            SliderSetting(
                title = "Padding",
                valueText = "${prefs.reader.padding}dp",
                value = prefs.reader.padding.toFloat(),
                range = 0f..40f,
                steps = 4,
                onChange = { vm.setPadding(it.toInt()) },
            )
            Toggle("Keep screen on", "Keep the display awake while reading", prefs.reader.keepScreenOn, vm::setKeepScreenOn)
            Toggle("Fullscreen", "Hide system bars while reading", prefs.reader.fullscreen, vm::setFullscreen)

            Section("Library")
            Toggle("Incognito mode", "Pause reading history while enabled", prefs.incognito, vm::setIncognito)

            Section("Downloads")
            Toggle("Wi-Fi only", "Download chapters only on Wi-Fi", prefs.wifiOnlyDownloads, vm::setWifiOnly)

            Section("About")
            ListItem(headlineContent = { Text("SoraReader Native") }, supportingContent = { Text("Kotlin + Jetpack Compose + Material 3 rebuild MVP") })
        }
    }
}

@Composable private fun Section(title: String) {
    Text(title, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable private fun Toggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(headlineContent = { Text(title) }, supportingContent = { Text(subtitle) }, trailingContent = { Switch(checked = checked, onCheckedChange = onChange) })
}

@Composable private fun SliderSetting(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(valueText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ThemeSelector(current: String, onChange: (String) -> Unit) {
    val modes = listOf(
        ThemeMode.SYSTEM to "System",
        ThemeMode.LIGHT to "Light",
        ThemeMode.DARK to "Dark",
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        modes.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = current.equals(value, ignoreCase = true),
                onClick = { onChange(value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
            ) { Text(label) }
        }
    }
}
