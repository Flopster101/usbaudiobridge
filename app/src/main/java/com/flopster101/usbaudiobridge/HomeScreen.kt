package com.flopster101.usbaudiobridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: MainUiState,
    onToggleGadget: (Boolean) -> Unit,
    onToggleCapture: () -> Unit,
    onToggleSpeakerMute: () -> Unit,
    onToggleMicMute: () -> Unit,
    onToggleRecording: () -> Unit,
    onShareRecording: () -> Unit,
    onToggleLogs: () -> Unit
) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Card 1: Main Controls
            item {
                val heroContainer by animateColorAsState(
                    targetValue = if (state.isGadgetEnabled) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    label = "heroContainer"
                )
                val heroContent by animateColorAsState(
                    targetValue = if (state.isGadgetEnabled) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onPrimary
                    },
                    label = "heroContent"
                )
                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    ),
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(vertical = 24.dp)) {
                        Column(
                            modifier = Modifier.padding(horizontal = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                FilledTonalButton(
                                    onClick = { onToggleGadget(!state.isGadgetEnabled) },
                                    enabled = !state.isGadgetPending,
                                    modifier = Modifier.weight(1f).height(56.dp),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = heroContainer,
                                        contentColor = heroContent
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp)
                                ) {
                                    val isPending = state.isGadgetPending
                                    val label = when {
                                        isPending && state.lastGadgetActionWasEnable -> "Enabling..."
                                        isPending -> "Disabling..."
                                        state.isGadgetEnabled -> "Disable gadget"
                                        else -> "Enable gadget"
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        if (isPending) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp
                                            )
                                            Spacer(Modifier.width(8.dp))
                                        }
                                        Text(label, style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                                FilledTonalButton(
                                    onClick = onToggleCapture,
                                    enabled = state.isGadgetEnabled && !state.isCapturePending,
                                    modifier = Modifier.weight(1f).height(56.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp)
                                ) {
                                    val buttonText = when {
                                        state.isCapturePending -> if (state.isServiceRunning) "Stopping..." else "Starting..."
                                        state.isServiceRunning -> "Stop capture"
                                        else -> "Start capture"
                                    }
                                    Text(buttonText, style = MaterialTheme.typography.labelLarge)
                                }
                            }
                            AnimatedVisibility(
                                visible = state.gadgetStatusError != null,
                                enter = expandVertically(),
                                exit = shrinkVertically()
                            ) {
                                Text(
                                    text = state.gadgetStatusError ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp)
                                        .padding(top = 10.dp)
                                )
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(12.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Record incoming audio",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = when {
                                        state.isRecordingPending && state.isRecordingActive -> "Stopping..."
                                        state.isRecordingPending -> "Starting..."
                                        state.isRecordingActive -> "${state.recordingDuration} · ${state.recordingFileName?.substringAfterLast('.')?.uppercase() ?: ""}"
                                        state.recordingError != null -> state.recordingError
                                        state.recordingFileName != null -> "Saved ${state.recordingFileName}"
                                        else -> "Off · ${if (state.recordingFormat == 1) "FLAC" else "WAV"}"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (state.recordingError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2
                                )
                            }
                            if (state.recordingFileName != null && !state.isRecordingActive && !state.isRecordingPending && state.recordingError == null) {
                                IconButton(onClick = onShareRecording) {
                                    Icon(
                                        Icons.Filled.Share,
                                        contentDescription = "Share last recording",
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            FilledTonalButton(
                                onClick = onToggleRecording,
                                enabled = state.isServiceRunning && (state.runningDirections and 1) != 0 && !state.isRecordingPending,
                                contentPadding = PaddingValues(horizontal = 16.dp)
                            ) {
                                Icon(
                                    Icons.Filled.FiberManualRecord,
                                    contentDescription = null,
                                    tint = if (state.isRecordingActive) MaterialTheme.colorScheme.error else LocalContentColor.current,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(if (state.isRecordingActive) "Stop" else "Record")
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(12.dp))

                        // Active bridges row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.padding(top = 2.dp)) {
                                Text(
                                    text = "Active bridges",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (state.isServiceRunning && ((state.runningDirections and 1) != 0 || (state.runningDirections and 2) != 0)) {
                                    Text(
                                        text = "Tap icons to mute/unmute",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                            }
                            if (!state.isServiceRunning) {
                                Text(
                                    text = "--",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val isSpeaker = (state.runningDirections and 1) != 0
                                    val isMic = (state.runningDirections and 2) != 0
                                    if (isSpeaker) {
                                        Surface(
                                            onClick = onToggleSpeakerMute,
                                            shape = CircleShape,
                                            color = if (state.speakerMuted) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                                            contentColor = if (state.speakerMuted) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    painter = painterResource(if (state.speakerMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up),
                                                    contentDescription = if (state.speakerMuted) "Unmute Speaker" else "Mute Speaker",
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                    }
                                    if (isMic) {
                                        Surface(
                                            onClick = onToggleMicMute,
                                            shape = CircleShape,
                                            color = if (state.micMuted) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                                            contentColor = if (state.micMuted) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    painter = painterResource(if (state.micMuted) R.drawable.ic_mic_off else R.drawable.ic_mic),
                                                    contentDescription = if (state.micMuted) "Unmute Microphone" else "Mute Microphone",
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        // Playback device row, icon aligned right
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Playback device",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                val iconRes = when (state.playbackDeviceType) {
                                    PlaybackDeviceType.BLUETOOTH -> R.drawable.ic_playback_bluetooth
                                    PlaybackDeviceType.HEADPHONES -> R.drawable.ic_playback_headphones
                                    PlaybackDeviceType.SPEAKER -> R.drawable.ic_playback_speaker
                                    else -> null
                                }
                                if (iconRes != null) {
                                    Icon(
                                        painter = painterResource(iconRes),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Text(
                                    text = state.playbackDeviceType.name.lowercase().replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Card 2: System Status
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 24.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "System status",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Surface(
                        shape = RoundedCornerShape(percent = 50),
                        color = Color(state.serviceStateColor).copy(alpha = 0.15f),
                        tonalElevation = 0.dp
                    ) {
                        Text(
                            text = state.serviceState,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = Color(state.serviceStateColor),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                StatusCell("Gadget", state.getGadgetStatusLabel(), Color(state.getGadgetStatusColor()))
                                StatusCell("Sample rate", state.sampleRate)
                                StatusCell("Period size", state.periodSize)
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                StatusCell("Buffer", state.currentBuffer)
                                StatusCell("Controller", state.udcController)
                                StatusCell("Functions", state.activeFunctions)
                            }
                        }
                    }
                }
            }

            // Card 4: Logs
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleLogs() }
                        .padding(start = 16.dp, end = 24.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Debug logs",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )

                    // Copy Button
                    val context = LocalContext.current
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("UsbAudioLogs", state.logText)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(32.dp) // Slightly smaller than default
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_copy),
                            contentDescription = "Copy All Logs",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    // Share Button
                    IconButton(
                        onClick = {
                            try {
                                val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.getDefault()).format(Date())
                                val fileName = "UsbAudioBridge_$timestamp.log"
                                val logFile = File(context.cacheDir, fileName)
                                logFile.writeText(state.logText)

                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    logFile
                                )

                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share logs"))
                            } catch (e: Exception) {
                                Toast.makeText(context, "Failed to share logs", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = "Share Logs",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    val rotation by animateFloatAsState(
                        targetValue = if (state.isLogsExpanded) 180f else 0f,
                        label = "arrowRotation"
                    )
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.rotate(rotation),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                AnimatedVisibility(
                    visible = state.isLogsExpanded,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    ElevatedCard(
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        ),
                        shape = RoundedCornerShape(28.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)
                    ) {
                        val logScroll = rememberScrollState()
                        var previousMaxValue by remember { mutableIntStateOf(0) }

                        LaunchedEffect(state.logText) {
                            if (previousMaxValue == 0 || logScroll.value >= previousMaxValue - 50) {
                                logScroll.animateScrollTo(logScroll.maxValue)
                            }
                            previousMaxValue = logScroll.maxValue
                        }

                        val nestedScrollInterop = remember {
                            object : NestedScrollConnection {
                                override fun onPostScroll(
                                    consumed: Offset,
                                    available: Offset,
                                    source: NestedScrollSource
                                ): Offset {
                                    return available // Consume remaining scroll to prevent parent (LazyColumn) from getting it
                                }
                                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                                    return available
                                }
                            }
                        }

                        Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                             SelectionContainer {
                                 Text(
                                     text = state.logText,
                                     fontFamily = FontFamily.Monospace,
                                     fontSize = 12.sp,
                                     color = MaterialTheme.colorScheme.onSurface,
                                     modifier = Modifier
                                         .nestedScroll(nestedScrollInterop)
                                         .verticalScroll(logScroll)
                                 )
                             }
                        }
                    }
                }
            }

            // Bottom Padding
            item {
                Spacer(Modifier.height(32.dp))
            }
        }
}
