package com.flopster101.usbaudiobridge

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    state: MainUiState,
    onBufferSizeChange: (Float) -> Unit,
    onBufferModeChange: (Int) -> Unit,
    onLatencyPresetChange: (Int) -> Unit,
    onPeriodSizeChange: (Int) -> Unit,
    onEngineTypeChange: (Int) -> Unit,
    onUseOboeChange: (Boolean) -> Unit,
    onSampleRateChange: (Int) -> Unit,
    onUacVersionChange: (Int) -> Unit,
    onKeepAdbChange: (Boolean) -> Unit,
    onAutoRestartChange: (Boolean) -> Unit,
    onActiveDirectionsChange: (Int) -> Unit,
    onMicSourceChange: (Int) -> Unit,
    onRecordingFormatChange: (Int) -> Unit,
    onChooseRecordingDirectory: () -> Unit,
    onUseDefaultRecordingDirectory: () -> Unit,
    onNotificationEnabledChange: (Boolean) -> Unit,
    onKeepScreenOnChange: (Boolean) -> Unit,
    onScreensaverEnabledChange: (Boolean) -> Unit,
    onScreensaverTimeoutChange: (Int) -> Unit,
    onScreensaverRepositionIntervalChange: (Int) -> Unit,
    onScreensaverDvdModeChange: (Boolean) -> Unit,
    onScreensaverDvdSpeedChange: (Int) -> Unit,
    onScreensaverFpsLimitChange: (Int) -> Unit,
    onScreensaverFullscreenChange: (Boolean) -> Unit,
    onMuteOnMediaButtonChange: (Boolean) -> Unit,
    onThemeModeChange: (Int) -> Unit,
    onDynamicColorsChange: (Boolean) -> Unit,
    onResetSettings: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 0.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item {
            SettingsSectionTitle("Audio configuration")
            Spacer(Modifier.height(8.dp))
        }

        // Buffer Configuration
        item {
            GroupedSettingsCard(position = SettingsGroupPosition.Top) {
                Column(modifier = Modifier.fillMaxWidth()) {

                    // Title
                    Text(
                        text = "Audio Buffer",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 16.dp, start = 16.dp, end = 16.dp)
                    )
                    Spacer(Modifier.height(8.dp))

                    // Content
                    if (state.bufferMode == 0) {
                        // SIMPLE MODE
                        var showLatencyDialog by remember { mutableStateOf(false) }
                        val presets = (0..8).toList()
                        val labels = listOf(
                            "Minimum (10ms)",
                            "Very Low (20ms)",
                            "Low (30ms)",
                            "Normal (40ms)",
                            "Balanced (50ms)",
                            "High (60ms)",
                            "Very High (80ms)",
                            "Stable (100ms)",
                            "Maximum (200ms)"
                        )

                        ListItem(
                            headlineContent = { Text("Target latency") },
                            supportingContent = {
                                Column {
                                    Text(
                                        text = labels.getOrElse(state.latencyPreset) { "Unknown" },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = "Lower latency can require a more powerful device and a stable USB connection. Increase if audio crackles.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            modifier = Modifier.clickable { showLatencyDialog = true },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )

                        if (showLatencyDialog) {
                            SelectionDialog(
                                title = "Target Latency",
                                options = presets,
                                labels = labels,
                                selectedOption = state.latencyPreset,
                                onDismiss = { showLatencyDialog = false },
                                onOptionSelected = {
                                    onLatencyPresetChange(it)
                                    showLatencyDialog = false
                                }
                            )
                        }

                    } else {
                        // ADVANCED MODE (Slider)
                         Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            val rate = state.sampleRateOption.toFloat()
                            val minBuffer = rate * 0.01f // 10ms
                            val maxBuffer = rate * 0.5f  // 500ms

                            val ms = (state.bufferSize / (rate / 1000f)).toInt()
                            Text(
                                text = "${state.bufferSize.toInt()} frames (~${ms}ms)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Slider(
                                value = state.bufferSize.coerceIn(minBuffer, maxBuffer),
                                onValueChange = onBufferSizeChange,
                                valueRange = minBuffer..maxBuffer,
                                steps = 48 // 10ms increments (10..500ms)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Lower Latency (10ms)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                Text(
                                    text = "Higher Stability (500ms)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                         }
                    }

                    HorizontalDivider()

                    // Advanced Toggle Footer
                    ListItem(
                        headlineContent = { Text("Advanced configuration") },
                        trailingContent = {
                            Switch(
                                checked = state.bufferMode == 1,
                                onCheckedChange = { onBufferModeChange(if (it) 1 else 0) }
                            )
                        },
                        modifier = Modifier.clickable { onBufferModeChange(if (state.bufferMode == 0) 1 else 0) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
            }
        }
        item { Spacer(Modifier.height(2.dp)) }

        // Active Directions (Devices)
        item {
            GroupedSettingsCard(position = SettingsGroupPosition.Middle) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Audio devices", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Select which devices to enable.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))

                    MultiChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        val isSpeaker = (state.activeDirectionsOption and 1) != 0
                        val isMic = (state.activeDirectionsOption and 2) != 0

                        SegmentedButton(
                            checked = isSpeaker,
                            onCheckedChange = {
                                val newMask = if (it) state.activeDirectionsOption or 1 else state.activeDirectionsOption and 1.inv()
                                onActiveDirectionsChange(newMask)
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                        ) {
                            Text("Speaker (Output)")
                        }

                        SegmentedButton(
                            checked = isMic,
                            onCheckedChange = {
                                val newMask = if (it) state.activeDirectionsOption or 2 else state.activeDirectionsOption and 2.inv()
                                onActiveDirectionsChange(newMask)
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                        ) {
                            Text("Mic (Input)")
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(2.dp)) }

        // Mic Source
        item {
            var showMicDialog by remember { mutableStateOf(false) }
            val options = listOf(6, 1, 5, 7, 9, 10)
            val labels = listOf("Auto (voice rec)", "Mic", "Camcorder", "Voice comm", "Unprocessed", "Performance")

            GroupedSettingsCard(position = SettingsGroupPosition.Middle) {
                val index = options.indexOf(state.micSourceOption)
                val label = if (index >= 0) labels[index] else "Unknown"
                ListItem(
                    headlineContent = { Text("Microphone source", style = MaterialTheme.typography.titleMedium) },
                    supportingContent = {
                        Text(
                            text = "Select input preset. Affects processing (echo cancellation, noise suppression).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingContent = {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    },
                    modifier = Modifier.clickable { showMicDialog = true },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }

            if (showMicDialog) {
                SelectionDialog(
                    title = "Microphone source",
                    options = options,
                    labels = labels,
                    selectedOption = state.micSourceOption,
                    onDismiss = { showMicDialog = false },
                    onOptionSelected = {
                        onMicSourceChange(it)
                        showMicDialog = false
                    }
                )
            }
        }
        item { Spacer(Modifier.height(2.dp)) }

        // Sample Rate
        item {
            var showSampleRateDialog by remember { mutableStateOf(false) }
            val rates = listOf(22050, 32000, 44100, 48000, 88200, 96000, 192000)
            val labels = rates.map { "$it Hz" }

            GroupedSettingsCard(position = SettingsGroupPosition.Middle) {
                ListItem(
                    headlineContent = { Text("Sample rate", style = MaterialTheme.typography.titleMedium) },
                    supportingContent = {
                        Text(
                            text = "48kHz is standard for Android. Higher rates increase CPU load and may require larger buffers.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingContent = {
                        Text(
                            text = "${state.sampleRateOption} Hz",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    },
                    modifier = Modifier.clickable { showSampleRateDialog = true },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }

            if (showSampleRateDialog) {
                SelectionDialog(
                    title = "Sample Rate",
                    options = rates,
                    labels = labels,
                    selectedOption = state.sampleRateOption,
                    onDismiss = { showSampleRateDialog = false },
                    onOptionSelected = {
                        onSampleRateChange(it)
                        showSampleRateDialog = false
                    },
                    headerContent = {
                        Column {
                            Text(
                                text = "Changing this requires restarting/resetting the USB Gadget.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Higher sample rates (e.g. 96kHz) increase CPU load significantly. You may need to increase the Buffer Size to prevent audio overruns.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                             HorizontalDivider()
                             Spacer(Modifier.height(12.dp))
                        }
                    }
                )
            }
        }
        item { Spacer(Modifier.height(2.dp)) }

        // Output Engine
        item {
            GroupedSettingsCard(position = SettingsGroupPosition.Middle) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onUseOboeChange(!state.useOboeOption) },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Use Oboe", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "Recommended. Automatically picks the best backend (AAudio on Android 8.1+, OpenSL ES otherwise) with low latency.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.useOboeOption,
                            onCheckedChange = onUseOboeChange,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }

                    if (!state.useOboeOption) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "Select the backend driver for playback.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))

                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = state.engineTypeOption == 0,
                                onClick = { onEngineTypeChange(0) },
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3)
                            ) {
                                Text("AAudio")
                            }
                            SegmentedButton(
                                selected = state.engineTypeOption == 1,
                                onClick = { onEngineTypeChange(1) },
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3)
                            ) {
                                Text("OpenSL ES")
                            }
                            SegmentedButton(
                                selected = state.engineTypeOption == 2,
                                onClick = { onEngineTypeChange(2) },
                                shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3)
                            ) {
                                Text("AudioTrack")
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        val desc = when(state.engineTypeOption) {
                            0 -> "AAudio: Low latency, high performance. Recommended for Android 8.1+."
                            1 -> "OpenSL ES: Native audio standard. Good alternative if AAudio has glitches."
                            2 -> "AudioTrack: Legacy Java-based audio. Highest compatibility, higher latency."
                            else -> ""
                        }
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(2.dp)) }

        // Period Size
        item {
            var showPeriodDialog by remember { mutableStateOf(false) }
            val options = listOf(0, 4096, 2048, 1024, 960, 512, 480, 360, 256, 240, 192, 128, 120, 96, 64)
            val labels = listOf("Auto", "4096", "2048", "1024", "960", "512", "480", "360", "256", "240", "192", "128", "120", "96", "64")

            GroupedSettingsCard(
                position = SettingsGroupPosition.Bottom
            ) {
                val index = options.indexOf(state.periodSizeOption)
                val label = if (index >= 0) labels[index] else state.periodSizeOption.toString()
                ListItem(
                    headlineContent = { Text("Period size (frames)", style = MaterialTheme.typography.titleMedium) },
                    supportingContent = {
                        Text(
                            text = "Controls capture latency and CPU load.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingContent = {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    },
                    modifier = Modifier.clickable { showPeriodDialog = true },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }

            if (showPeriodDialog) {
                SelectionDialog(
                    title = "Period size (frames)",
                    options = options,
                    labels = labels,
                    selectedOption = state.periodSizeOption,
                    onDismiss = { showPeriodDialog = false },
                    onOptionSelected = {
                        onPeriodSizeChange(it)
                        showPeriodDialog = false
                    }
                )
            }
        }
        item { Spacer(Modifier.height(20.dp)) }

        item {
            SettingsSectionTitle("Recording")
            Spacer(Modifier.height(8.dp))
        }

        item {
            GroupedSettingsCard(position = SettingsGroupPosition.Top) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("File format", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Records the stereo host audio before playback or speaker mute.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = state.recordingFormat == SettingsRepository.RECORDING_FORMAT_WAV,
                            onClick = { onRecordingFormatChange(SettingsRepository.RECORDING_FORMAT_WAV) },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                            enabled = !state.isRecordingActive && !state.isRecordingPending
                        ) {
                            Text("WAV")
                        }
                        SegmentedButton(
                            selected = state.recordingFormat == SettingsRepository.RECORDING_FORMAT_FLAC,
                            onClick = { onRecordingFormatChange(SettingsRepository.RECORDING_FORMAT_FLAC) },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                            enabled = !state.isRecordingActive && !state.isRecordingPending
                        ) {
                            Text("FLAC")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "FLAC uses Android's C2 encoder. Files contain 16-bit stereo PCM at the capture sample rate.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item { Spacer(Modifier.height(2.dp)) }

        item {
            GroupedSettingsCard(position = SettingsGroupPosition.Bottom) {
                ListItem(
                    headlineContent = { Text("Save folder") },
                    supportingContent = {
                        Text(
                            text = "Tap to choose a folder, or use the default Music/UsbAudioBridge folder.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingContent = {
                        Text(
                            text = state.recordingDirectory,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 2,
                            modifier = Modifier.widthIn(max = 140.dp)
                        )
                    },
                    modifier = Modifier.clickable(enabled = !state.isRecordingActive && !state.isRecordingPending) { onChooseRecordingDirectory() },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
                if (state.recordingDirectory != "Music/UsbAudioBridge") {
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text("Use default folder") },
                        supportingContent = { Text("Music/UsbAudioBridge") },
                        modifier = Modifier.clickable(enabled = !state.isRecordingActive && !state.isRecordingPending) { onUseDefaultRecordingDirectory() },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }

        // USB Settings
        item {
            SettingsSectionTitle("USB settings")
            Spacer(Modifier.height(8.dp))
        }

        item {
            GroupedSettingsCard(
                position = SettingsGroupPosition.Top
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("USB audio class", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Use UAC2 for best quality/performance. Use UAC1 for compatibility with older hosts (e.g. pre-Windows 10 1703).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(12.dp))

                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = state.uacVersionOption == 2,
                            onClick = { onUacVersionChange(2) },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                        ) {
                            Text("UAC2")
                        }
                        SegmentedButton(
                            selected = state.uacVersionOption == 1,
                            onClick = { onUacVersionChange(1) },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                        ) {
                            Text("UAC1")
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    Text(
                        text = "Changing this requires restarting/resetting the USB Gadget.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
        item { Spacer(Modifier.height(2.dp)) }

        item {
            GroupedSettingsCard(position = SettingsGroupPosition.Bottom) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = {
                            Column {
                                Text("Keep ADB enabled")
                                state.usbEnvironmentLabel?.let {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        supportingContent = {
                            Text(
                                text = if (state.keepAdbSupported) {
                                    "Forces ADB to remain active (Composite Gadget). May not work on some devices.."
                                } else {
                                    state.keepAdbReason ?: "Not supported on this device."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (state.keepAdbSupported) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.error
                                }
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = state.keepAdbOption,
                                enabled = state.keepAdbSupported,
                                onCheckedChange = onKeepAdbChange
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }

        // Audio Behavior
        item {
            SettingsSectionTitle("Audio behavior")
            Spacer(Modifier.height(8.dp))
        }

        item {
            GroupedSettingsCard(position = SettingsGroupPosition.Top) {
                ListItem(
                    headlineContent = { Text("Always continue on output change") },
                    supportingContent = {
                        Text(
                            text = "Keep playing when any output change occurs, including when headphones or Bluetooth are disconnected. When disabled, behaves like music apps (stops on disconnect).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = state.autoRestartOnOutputChange,
                            onCheckedChange = onAutoRestartChange
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
        }
        item { Spacer(Modifier.height(2.dp)) }

        item {
            GroupedSettingsCard(position = SettingsGroupPosition.Bottom) {
                ListItem(
                    headlineContent = { Text("Control via Headset buttons") },
                    supportingContent = {
                        Text(
                            text = "Use headset/Bluetooth Play/Pause buttons to mute/unmute the speaker bridge.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = state.muteOnMediaButton,
                            onCheckedChange = onMuteOnMediaButtonChange
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
        }
        item { Spacer(Modifier.height(20.dp)) }

        // Notification
        item {
            SettingsSectionTitle("Notification")
            Spacer(Modifier.height(8.dp))
        }

        item {
            GroupedSettingsCard(position = SettingsGroupPosition.Standalone) {
                ListItem(
                    headlineContent = { Text("Enable interactive notification") },
                    supportingContent = {
                        Text(
                            text = "Enable persistent status notification with controls.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = state.notificationEnabled,
                            onCheckedChange = onNotificationEnabledChange
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
        }
        item { Spacer(Modifier.height(20.dp)) }

        // Display
        item {
            SettingsSectionTitle("Display")
            Spacer(Modifier.height(8.dp))
        }

        item {
            GroupedSettingsCard(position = SettingsGroupPosition.Top) {
                ListItem(
                    headlineContent = { Text("Keep screen on") },
                    supportingContent = {
                        Text(
                            text = "Prevent the screen from turning off while the app is open. Might be useful if audio lags when screen is off.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = state.keepScreenOnOption,
                            onCheckedChange = onKeepScreenOnChange
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
        }
        item { Spacer(Modifier.height(2.dp)) }

        // Screensaver
        item {
            GroupedSettingsCard(position = SettingsGroupPosition.Bottom) {
                Column(modifier = Modifier.fillMaxWidth()) {

                    ListItem(
                        headlineContent = { Text("Enable screensaver") },
                        supportingContent = {
                            Text(
                                text = "Show a screensaver to prevent burn-in on OLED displays and image retention on LCDs. Only available when 'Keep screen on' is enabled.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = state.screensaverEnabled,
                                onCheckedChange = onScreensaverEnabledChange,
                                enabled = state.keepScreenOnOption
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )

                    if (state.screensaverEnabled && state.keepScreenOnOption) {
                        var showFpsDialog by remember { mutableStateOf(false) }
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Timeout: ${state.screensaverTimeout}s",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )

                            Slider(
                                value = ((state.screensaverTimeout - 5) / 5).toFloat(),
                                onValueChange = { val snapped = it.roundToInt(); val timeout = 5 + snapped * 5; onScreensaverTimeoutChange(timeout) },
                                valueRange = 0f..11f,
                                steps = 11,
                                modifier = Modifier.weight(2f)
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                        ListItem(
                            headlineContent = { Text("DVD bounce mode", style = MaterialTheme.typography.bodyLarge) },
                            supportingContent = {
                                Text(
                                    text = "Glides diagonally and bounces off screen edges.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = state.screensaverDvdMode,
                                    onCheckedChange = onScreensaverDvdModeChange
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )

                        if (state.screensaverDvdMode) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "DVD speed: ${state.screensaverDvdSpeed}px/s",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )

                                Slider(
                                    value = state.screensaverDvdSpeed.toFloat(),
                                    onValueChange = {
                                        val snapped = (it.roundToInt() / 10) * 10
                                        onScreensaverDvdSpeedChange(snapped.coerceIn(40, 320))
                                    },
                                    valueRange = 40f..320f,
                                    steps = 27,
                                    modifier = Modifier.weight(2f)
                                )
                            }
                        }

                        if (!state.screensaverDvdMode) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Reposition: ${state.screensaverRepositionInterval}s",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )

                                Slider(
                                    value = ((state.screensaverRepositionInterval - 5) / 5).toFloat(),
                                    onValueChange = { val snapped = it.roundToInt(); val interval = 5 + snapped * 5; onScreensaverRepositionIntervalChange(interval) },
                                    valueRange = 0f..5f,
                                    steps = 5,
                                    modifier = Modifier.weight(2f)
                                )
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                        ListItem(
                            headlineContent = { Text("Fullscreen mode", style = MaterialTheme.typography.bodyLarge) },
                            supportingContent = {
                                Text(
                                    text = "Hide system UI elements when screensaver is active",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = state.screensaverFullscreen,
                                    onCheckedChange = onScreensaverFullscreenChange
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )

                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                        ListItem(
                            headlineContent = { Text("Frame rate limit", style = MaterialTheme.typography.bodyLarge) },
                            supportingContent = {
                                Text(
                                    text = "Limits screensaver redraw rate to save power on devices where rendering is expensive.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            trailingContent = {
                                Text(
                                    text = "${state.screensaverFpsLimit} fps",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            },
                            modifier = Modifier.clickable { showFpsDialog = true },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )

                        if (showFpsDialog) {
                            FpsLimitDialog(
                                currentValue = state.screensaverFpsLimit,
                                onDismiss = { showFpsDialog = false },
                                onSave = {
                                    onScreensaverFpsLimitChange(it)
                                    showFpsDialog = false
                                }
                            )
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(20.dp)) }

        // Appearance
        item {
            SettingsSectionTitle("Appearance")
            Spacer(Modifier.height(8.dp))
        }

        item {
            GroupedSettingsCard(position = SettingsGroupPosition.Top) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Theme", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Choose between Auto (follows system), Dark or Light mode.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = state.themeMode == 0,
                            onClick = { onThemeModeChange(0) },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3)
                        ) {
                            Text("Auto")
                        }
                        SegmentedButton(
                            selected = state.themeMode == 1,
                            onClick = { onThemeModeChange(1) },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3)
                        ) {
                            Text("Dark")
                        }
                        SegmentedButton(
                            selected = state.themeMode == 2,
                            onClick = { onThemeModeChange(2) },
                            shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3)
                        ) {
                            Text("Light")
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(2.dp)) }

        item {
            GroupedSettingsCard(position = SettingsGroupPosition.Bottom) {
                ListItem(
                    headlineContent = {
                        Text(
                            text = "Dynamic colors",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
                                MaterialTheme.colorScheme.onSurface
                            else
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    },
                    supportingContent = {
                        Text(
                            text = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
                                "Use colors derived from your wallpaper (Material You)."
                            else
                                "Requires Android 12 or later.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = state.dynamicColorsEnabled,
                            onCheckedChange = onDynamicColorsChange,
                            enabled = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
        }

        // Reset
        item {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = onResetSettings,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Reset to defaults")
            }
        }
    }
}

private enum class SettingsGroupPosition {
    Standalone,
    Top,
    Middle,
    Bottom
}

private val SettingsOuterCorner = 20.dp
private val SettingsInnerCorner = 4.dp

private fun groupedSettingsShape(position: SettingsGroupPosition): RoundedCornerShape {
    return when (position) {
        SettingsGroupPosition.Standalone -> RoundedCornerShape(SettingsOuterCorner)
        SettingsGroupPosition.Top -> RoundedCornerShape(
            topStart = SettingsOuterCorner,
            topEnd = SettingsOuterCorner,
            bottomStart = SettingsInnerCorner,
            bottomEnd = SettingsInnerCorner
        )
        SettingsGroupPosition.Middle -> RoundedCornerShape(SettingsInnerCorner)
        SettingsGroupPosition.Bottom -> RoundedCornerShape(
            topStart = SettingsInnerCorner,
            topEnd = SettingsInnerCorner,
            bottomStart = SettingsOuterCorner,
            bottomEnd = SettingsOuterCorner
        )
    }
}

@Composable
private fun GroupedSettingsCard(
    position: SettingsGroupPosition,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = groupedSettingsShape(position)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            content = content
        )
    }
}

@Composable
private fun SettingsSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp)
    )
}
