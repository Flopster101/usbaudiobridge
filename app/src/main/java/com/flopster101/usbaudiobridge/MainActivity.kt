package com.flopster101.usbaudiobridge

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize

class MainActivity : ComponentActivity() {

    private var screensaverFullscreenActive = false // Tracks if fullscreen was enabled when screensaver activated
    private lateinit var windowInsetsController: WindowInsetsController
    private var audioService: AudioService? = null
    private lateinit var settingsRepo: SettingsRepository

    // Mutable State Holder
    private var uiState by mutableStateOf(MainUiState())

    // Root status
    private var isRootGranted by mutableStateOf<Boolean?>(null)

    // Playback device broadcast receiver
    private val playbackDeviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updatePlaybackDeviceType()
        }
    }

    private fun updatePlaybackDeviceType() {
        val type = PlaybackDeviceHelper.getCurrentPlaybackDevice(this)
        uiState = uiState.copy(playbackDeviceType = type)
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startBridgeWithState()
        } else {
            appendLog("[App] Microphone permission denied. Input will fail.")
            startBridgeWithState()
        }
    }

    private val requestRecordingStoragePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            audioService?.toggleRecording()
        } else {
            appendLog("[App] Storage permission denied. Choose a recording folder in Settings instead.")
        }
    }

    private val recordingDirectoryLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                AudioRecordingStore(this, settingsRepo).persistDirectoryPermission(uri)
                uiState = uiState.copy(
                    recordingDirectory = AudioRecordingStore(this, settingsRepo).directoryLabel()
                )
            } catch (e: Exception) {
                appendLog("[App] Could not use selected recording folder: ${e.message}")
            }
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _: Boolean ->
        // No-op: notification permission does not gate service binding.
    }

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra(AudioService.EXTRA_MSG) ?: return
            appendLog(msg)

            if (msg.contains("Your kernel does not support UAC")) {
                val short = when {
                    msg.contains("UAC1", ignoreCase = true) -> "No UAC1 support"
                    msg.contains("UAC2", ignoreCase = true) -> "No UAC2 support"
                    uiState.uacVersionOption == 1 -> "No UAC1 support"
                    else -> "No UAC2 support"
                }
                uiState = uiState.copy(
                    showNoUacSupportError = true,
                    gadgetStatusError = short
                )
            }
            if (msg.contains("Cannot keep ADB enabled")) {
                uiState = uiState.copy(
                    showKeepAdbError = true,
                    lastGadgetFailureWasKeepAdb = true,
                    showGadgetSetupError = false,
                    gadgetStatusError = "Keep ADB unsupported"
                )
            }
            if (msg.contains("Failed to bind UDC", ignoreCase = true)) {
                uiState = uiState.copy(gadgetStatusError = "UDC bind failed")
            }
            if (msg.contains("[App] Failed to configure gadget.")) {
                uiState = uiState.copy(gadgetStatusError = uiState.gadgetStatusError ?: "Setup failed")
            }
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val isRunning = intent?.getBooleanExtra(AudioService.EXTRA_IS_RUNNING, false) ?: false
            val label = intent?.getStringExtra(AudioService.EXTRA_STATE_LABEL)
            val color = intent?.getLongExtra(AudioService.EXTRA_STATE_COLOR, 0)
            val directions = intent?.getIntExtra(AudioService.EXTRA_ACTIVE_DIRECTIONS, 0) ?: 0
            val isMuted = intent?.getBooleanExtra(AudioService.EXTRA_IS_MUTED, false) ?: false

            uiState = uiState.copy(
                isServiceRunning = isRunning,
                runningDirections = directions,
                speakerMuted = isMuted,
                serviceState = label ?: if (isRunning) "Active" else "Stopped",
                serviceStateColor = if (color != null && color != 0L) color else if (isRunning) 0xFFFFC107 else 0xFF888888
            )

            // Clear capture pending when state changes
            uiState = uiState.copy(isCapturePending = false)

            if (!isRunning) {
                uiState = uiState.copy(
                    sampleRate = "--",
                    periodSize = "--",
                    currentBuffer = "--"
                )
            }
        }
    }

    private val statsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val rate = intent.getIntExtra(AudioService.EXTRA_RATE, 0)
            val period = intent.getIntExtra(AudioService.EXTRA_PERIOD, 0)
            val buffer = intent.getIntExtra(AudioService.EXTRA_BUFFER, 0)

            // State label is handled by stateReceiver now via Service broadcast
            uiState = uiState.copy(
                sampleRate = "$rate Hz",
                periodSize = "$period frames",
                currentBuffer = "$buffer frames"
            )
        }
    }

    private val recordingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val bytes = intent.getLongExtra(AudioService.EXTRA_RECORDING_BYTES, 0)
            val rate = intent.getIntExtra(AudioService.EXTRA_RECORDING_RATE, uiState.sampleRateOption)
            val seconds = bytes / 4 / rate.coerceAtLeast(1)
            uiState = uiState.copy(
                isRecordingActive = intent.getBooleanExtra(AudioService.EXTRA_RECORDING_RUNNING, false),
                isRecordingPending = intent.getBooleanExtra(AudioService.EXTRA_RECORDING_PENDING, false),
                recordingFileName = intent.getStringExtra(AudioService.EXTRA_RECORDING_NAME),
                recordingDuration = String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60),
                recordingError = intent.getStringExtra(AudioService.EXTRA_RECORDING_ERROR)
            )
        }
    }

    private fun isOldKernelAffected(): Boolean {
        try {
            val version = System.getProperty("os.version") ?: return false

            val parts = version.split(".")
            if (parts.size >= 2) {
                val major = parts[0].toIntOrNull() ?: return false
                val minor = parts[1].split("-")[0].filter { it.isDigit() }.toIntOrNull() ?: return false

                if (major < 5) return true
                if (major == 5 && minor < 4) return true
            }
        } catch (e: Exception) {
            // Ignore
        }
        return false
    }

    private val gadgetResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val success = intent.getBooleanExtra("success", false)
            var showNotice = false

            if (
                success &&
                uiState.uacVersionOption == 2 &&
                isOldKernelAffected() &&
                settingsRepo.shouldShowOldKernelNotice()
            ) {
                showNotice = true
            }

            uiState = uiState.copy(
                isGadgetEnabled = success, 
                isGadgetPending = false,
                showOldKernelNotice = showNotice,
                showGadgetSetupError = !success && uiState.lastGadgetActionWasEnable && !uiState.lastGadgetFailureWasKeepAdb,
                lastGadgetFailureWasKeepAdb = if (success) false else uiState.lastGadgetFailureWasKeepAdb,
                lastGadgetActionWasEnable = if (success) false else uiState.lastGadgetActionWasEnable,
                gadgetStatusError = if (success) null else uiState.gadgetStatusError
            )
            audioService?.setGadgetEnabled(success)
        }
    }

    private val gadgetStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val udcController = intent.getStringExtra(AudioService.EXTRA_UDC_CONTROLLER) ?: "--"
            val activeFunctions = intent.getStringExtra(AudioService.EXTRA_ACTIVE_FUNCTIONS) ?: "--"
            uiState = uiState.copy(
                udcController = udcController,
                activeFunctions = activeFunctions
            )
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as AudioService.LocalBinder
            audioService = binder.getService()
            uiState = uiState.copy(isAppBound = true)
            audioService?.setGadgetEnabled(uiState.isGadgetEnabled)
            appendLog("[App] Service connected")
            restoreUiState()
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            uiState = uiState.copy(isAppBound = false)
            audioService = null
            appendLog("[App] Service disconnected")
        }
    }

    private fun startServiceAndBind() {
        // Start Service
        val intent = Intent(this, AudioService::class.java)
        startService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun checkRoot() {
        // Only show loading state if we are doing a fresh check or retry
        if (isRootGranted == false) {
             isRootGranted = null
        } else if (isRootGranted == null) {
             // Already loading or initial state, keep null
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val granted = UsbGadgetManager.isRootGranted()
            withContext(Dispatchers.Main) {
                isRootGranted = granted
            }
            if (granted) {
                val strategy = UsbGadgetManager.detectGadgetStrategy()
                withContext(Dispatchers.Main) {
                    uiState = uiState.copy(
                        keepAdbSupported = strategy.supportsKeepAdb,
                        keepAdbReason = strategy.keepAdbReason,
                        usbEnvironmentLabel = strategy.label
                    )
                    if (!strategy.supportsKeepAdb && uiState.keepAdbOption) {
                        uiState = uiState.copy(keepAdbOption = false)
                        settingsRepo.saveKeepAdb(false)
                    }
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && isRootGranted == false) {
            checkRoot()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize WindowInsetsController for modern system UI control (screensaver fullscreen)
        windowInsetsController = window.insetsController!!

        settingsRepo = SettingsRepository(this)
        // Load settings
        var loadedState = uiState.copy(
            bufferSize = settingsRepo.getBufferSize(),
            bufferMode = settingsRepo.getBufferMode(),
            latencyPreset = settingsRepo.getLatencyPreset(),
            periodSizeOption = settingsRepo.getPeriodSize(),
            engineTypeOption = settingsRepo.getEngineType(),
            sampleRateOption = settingsRepo.getSampleRate(),
            uacVersionOption = settingsRepo.getUacVersion(),
            keepAdbOption = settingsRepo.getKeepAdb(),
            autoRestartOnOutputChange = settingsRepo.getAutoRestartOnOutputChange(),
            activeDirectionsOption = settingsRepo.getActiveDirections(),
            micSourceOption = settingsRepo.getMicSource(),
            recordingFormat = settingsRepo.getRecordingFormat(),
            recordingDirectory = AudioRecordingStore(this, settingsRepo).directoryLabel(),
            notificationEnabled = settingsRepo.getNotificationEnabled(),
            keepScreenOnOption = settingsRepo.getKeepScreenOn(),
            screensaverEnabled = settingsRepo.getScreensaverEnabled(),
            screensaverTimeout = settingsRepo.getScreensaverTimeout(),
            screensaverRepositionInterval = settingsRepo.getScreensaverRepositionInterval(),
            screensaverDvdMode = settingsRepo.getScreensaverDvdMode(),
            screensaverDvdSpeed = settingsRepo.getScreensaverDvdSpeed(),
            screensaverFpsLimit = settingsRepo.getScreensaverFpsLimit(),
            screensaverFullscreen = settingsRepo.getScreensaverFullscreen(),
            muteOnMediaButton = settingsRepo.getMuteOnMediaButton(),
            themeMode = settingsRepo.getThemeMode(),
            dynamicColorsEnabled = settingsRepo.getDynamicColors()
        )

        // Reconciliation: If in Simple mode, ensure bufferSize matches the preset
        if (loadedState.bufferMode == 0) {
            val ms = when(loadedState.latencyPreset) {
                0 -> 10f
                1 -> 20f
                2 -> 30f
                3 -> 40f
                4 -> 50f
                5 -> 60f
                6 -> 80f
                7 -> 100f
                else -> 200f
            }
            val rate = loadedState.sampleRateOption
            val targetFrames = rate * ms / 1000f

            // If mismatch, update state AND save it (so next time it's correct)
            if (kotlin.math.abs(loadedState.bufferSize - targetFrames) > 1f) {
                 loadedState = loadedState.copy(bufferSize = targetFrames)
                 settingsRepo.saveBufferSize(targetFrames)
            }
        }

        uiState = loadedState

        // Apply initial keep screen on
        if (uiState.keepScreenOnOption) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        // Register playback device receiver
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        registerReceiver(playbackDeviceReceiver, filter)

        // Initial update
        updatePlaybackDeviceType()

        // Fallback timer (5s interval)
        lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(5000)
                updatePlaybackDeviceType()
            }
        }

        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Register Receivers
        ContextCompat.registerReceiver(this, logReceiver, IntentFilter(AudioService.ACTION_LOG), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, stateReceiver, IntentFilter(AudioService.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, statsReceiver, IntentFilter(AudioService.ACTION_STATS_UPDATE), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, gadgetResultReceiver, IntentFilter(AudioService.ACTION_GADGET_RESULT), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, gadgetStatusReceiver, IntentFilter(AudioService.ACTION_GADGET_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, recordingReceiver, IntentFilter(AudioService.ACTION_RECORDING_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)

        setContent {
            LaunchedEffect(Unit) {
                checkRoot()
            }

            LaunchedEffect(isRootGranted) {
                if (isRootGranted == true) {
                    startServiceAndBind()
                }
            }

            // Basic Material Theme wrapper
            val systemInDark = isSystemInDarkTheme()
            val useDark = when (uiState.themeMode) {
                1 -> true
                2 -> false
                else -> systemInDark
            }
            val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val dynamicDark  = dynamicDarkColorScheme(LocalContext.current)
                val dynamicLight = dynamicLightColorScheme(LocalContext.current)
                if (uiState.dynamicColorsEnabled) {
                    if (useDark) dynamicDark else dynamicLight
                } else {
                    if (useDark) darkColorScheme() else lightColorScheme()
                }
            } else {
                if (useDark) darkColorScheme() else lightColorScheme()
            }
            MaterialTheme(colorScheme = animateColorScheme(colorScheme)) {
                when (isRootGranted) {
                    null -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    false -> {
                        NoRootScreen(onRetry = { checkRoot() })
                    }
                    true -> {
                        // First-run kernel notice dialog
                        if (uiState.showKernelNotice) {
                            KernelNoticeDialog(
                                onDismiss = { dontShowAgain ->
                                    if (dontShowAgain) {
                                        settingsRepo.setKernelNoticeDismissed()
                                    }
                                    uiState = uiState.copy(showKernelNotice = false)
                                }
                            )
                        }

                        // Old kernel Windows notice
                        if (uiState.showOldKernelNotice) {
                            OldKernelNoticeDialog(
                                onDismiss = { dontShowAgain ->
                                    if (dontShowAgain) {
                                        settingsRepo.setOldKernelNoticeDismissed()
                                    }
                                    uiState = uiState.copy(showOldKernelNotice = false)
                                }
                            )
                        }

                        // Missing selected UAC support error
                        if (uiState.showNoUacSupportError) {
                            NoUacSupportDialog(
                                uacVersion = uiState.uacVersionOption,
                                onDismiss = { uiState = uiState.copy(showNoUacSupportError = false) }
                            )
                        }

                        // Gadget setup failure notice
                        if (uiState.showGadgetSetupError) {
                            GadgetSetupFailedDialog(
                                onDismiss = { uiState = uiState.copy(showGadgetSetupError = false) }
                            )
                        }

                        // Keep ADB not supported notice
                        if (uiState.showKeepAdbError) {
                            KeepAdbFailedDialog(
                                onDismiss = { uiState = uiState.copy(showKeepAdbError = false) }
                            )
                        }

                        AppNavigation(
                            state = uiState,
                            onToggleGadget = { enable ->
                                 if (enable) {
                                     // Set pending state, wait for result broadcast to confirm
                                     uiState = uiState.copy(
                                         isGadgetPending = true,
                                         lastGadgetActionWasEnable = true,
                                         showGadgetSetupError = false,
                                         gadgetStatusError = null
                                     )
                                     audioService?.enableGadget(uiState.sampleRateOption, uiState.keepAdbOption, uiState.uacVersionOption)
                                 } else {
                                     uiState = uiState.copy(
                                         isGadgetEnabled = false,
                                         isGadgetPending = true,
                                         lastGadgetActionWasEnable = false,
                                         showGadgetSetupError = false,
                                         lastGadgetFailureWasKeepAdb = false,
                                         gadgetStatusError = null
                                     )
                                     audioService?.setGadgetEnabled(false)
                                     audioService?.stopBridge()
                                     // Don't wait for broadcast result for disable
                                 }
                            },
                            onToggleRecording = {
                                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                                    settingsRepo.getRecordingDirectory() == null &&
                                    checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    requestRecordingStoragePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                } else {
                                    audioService?.toggleRecording()
                                }
                            },
                            onShareRecording = {
                                audioService?.createRecordingShareIntent()?.let {
                                    startActivity(Intent.createChooser(it, "Share recording"))
                                }
                            },
                            onRecordingFormatChange = {
                                uiState = uiState.copy(recordingFormat = it)
                                settingsRepo.saveRecordingFormat(it)
                            },
                            onChooseRecordingDirectory = {
                                recordingDirectoryLauncher.launch(null)
                            },
                            onUseDefaultRecordingDirectory = {
                                AudioRecordingStore(this, settingsRepo).useDefaultDirectory()
                                uiState = uiState.copy(recordingDirectory = "Music/UsbAudioBridge")
                            },
                            onToggleCapture = {
                                if (uiState.isServiceRunning) {
                                     uiState = uiState.copy(isCapturePending = true)
                                     audioService?.stopAudioOnly()
                                } else {
                                     uiState = uiState.copy(isCapturePending = true)
                                     val perm = android.Manifest.permission.RECORD_AUDIO
                                     if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                                         requestPermissionLauncher.launch(perm)
                                     } else {
                                         startBridgeWithState()
                                     }
                                }
                            },
                            onBufferSizeChange = {
                                uiState = uiState.copy(bufferSize = it)
                                settingsRepo.saveBufferSize(it)
                            },
                            onBufferModeChange = {
                                uiState = uiState.copy(bufferMode = it)
                                settingsRepo.saveBufferMode(it)
                            },
                            onLatencyPresetChange = { preset ->
                                val ms = when(preset) {
                                    0 -> 10f
                                    1 -> 20f
                                    2 -> 30f
                                    3 -> 40f
                                    4 -> 50f
                                    5 -> 60f
                                    6 -> 80f
                                    7 -> 100f
                                    else -> 200f
                                }
                                val rate = uiState.sampleRateOption
                                val frames = rate * ms / 1000f
                                uiState = uiState.copy(latencyPreset = preset, bufferSize = frames)
                                settingsRepo.saveLatencyPreset(preset)
                                settingsRepo.saveBufferSize(frames)
                            },
                            onPeriodSizeChange = {
                                uiState = uiState.copy(periodSizeOption = it)
                                settingsRepo.savePeriodSize(it)
                            },
                            onEngineTypeChange = {
                                uiState = uiState.copy(engineTypeOption = it)
                                settingsRepo.saveEngineType(it)
                            },
                            onUseOboeChange = {
                                uiState = uiState.copy(useOboeOption = it)
                                settingsRepo.saveUseOboe(it)
                            },
                            onSampleRateChange = { rate ->
                                settingsRepo.saveSampleRate(rate)
                                if (uiState.bufferMode == 0) {
                                    // Recalculate buffer to keep latency constant
                                    val ms = when(uiState.latencyPreset) {
                                        0 -> 10f
                                        1 -> 20f
                                        2 -> 30f
                                        3 -> 40f
                                        4 -> 50f
                                        5 -> 60f
                                        6 -> 80f
                                        7 -> 100f
                                        else -> 200f
                                    }
                                    val frames = rate * ms / 1000f
                                    uiState = uiState.copy(sampleRateOption = rate, bufferSize = frames)
                                    settingsRepo.saveBufferSize(frames)
                                } else {
                                    uiState = uiState.copy(sampleRateOption = rate)
                                }
                            },
                            onUacVersionChange = {
                                uiState = uiState.copy(
                                    uacVersionOption = it,
                                    showOldKernelNotice = if (it == 1) false else uiState.showOldKernelNotice
                                )
                                settingsRepo.saveUacVersion(it)
                            },
                            onKeepAdbChange = {
                                uiState = uiState.copy(keepAdbOption = it)
                                settingsRepo.saveKeepAdb(it)
                            },
                            onAutoRestartChange = {
                                uiState = uiState.copy(autoRestartOnOutputChange = it)
                                settingsRepo.saveAutoRestartOnOutputChange(it)
                            },
                            onActiveDirectionsChange = {
                                uiState = uiState.copy(activeDirectionsOption = it)
                                settingsRepo.saveActiveDirections(it)
                            },
                            onMicSourceChange = {
                                 uiState = uiState.copy(micSourceOption = it)
                                 settingsRepo.saveMicSource(it)
                            },
                            onNotificationEnabledChange = {
                                uiState = uiState.copy(notificationEnabled = it)
                                settingsRepo.saveNotificationEnabled(it)
                                audioService?.refreshNotification()
                            },
                            onKeepScreenOnChange = {
                                uiState = uiState.copy(keepScreenOnOption = it)
                                settingsRepo.saveKeepScreenOn(it)
                                if (it) {
                                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                } else {
                                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                    // Disable screensaver if keep screen on is disabled
                                    if (uiState.screensaverEnabled) {
                                        uiState = uiState.copy(screensaverEnabled = false)
                                        settingsRepo.saveScreensaverEnabled(false)
                                    }
                                }
                            },
                            onScreensaverEnabledChange = {
                                uiState = uiState.copy(screensaverEnabled = it)
                                settingsRepo.saveScreensaverEnabled(it)
                            },
                            onScreensaverTimeoutChange = {
                                uiState = uiState.copy(screensaverTimeout = it)
                                settingsRepo.saveScreensaverTimeout(it)
                            },
                            onScreensaverRepositionIntervalChange = {
                                uiState = uiState.copy(screensaverRepositionInterval = it)
                                settingsRepo.saveScreensaverRepositionInterval(it)
                            },
                            onScreensaverDvdModeChange = {
                                uiState = uiState.copy(screensaverDvdMode = it)
                                settingsRepo.saveScreensaverDvdMode(it)
                            },
                            onScreensaverDvdSpeedChange = {
                                uiState = uiState.copy(screensaverDvdSpeed = it)
                                settingsRepo.saveScreensaverDvdSpeed(it)
                            },
                            onScreensaverFpsLimitChange = {
                                uiState = uiState.copy(screensaverFpsLimit = it)
                                settingsRepo.saveScreensaverFpsLimit(it)
                            },
                            onScreensaverFullscreenChange = {
                                uiState = uiState.copy(screensaverFullscreen = it)
                                settingsRepo.saveScreensaverFullscreen(it)
                            },
                            onScreensaverActivate = {
                                uiState = uiState.copy(screensaverActive = true)
                                screensaverFullscreenActive = uiState.screensaverFullscreen
                                if (screensaverFullscreenActive) {
                                    // Hide system bars using WindowInsetsController for immersive screensaver experience
                                    windowInsetsController.hide(WindowInsets.Type.systemBars())
                                    windowInsetsController.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                                }
                            },
                            onScreensaverDeactivate = {
                                uiState = uiState.copy(screensaverActive = false)
                                if (screensaverFullscreenActive) {
                                    // Show system bars using WindowInsetsController - using separate flag to avoid state issues
                                    windowInsetsController.show(WindowInsets.Type.systemBars())
                                    screensaverFullscreenActive = false
                                }
                            },
                            onToggleSpeakerMute = {
                                uiState = uiState.copy(speakerMuted = !uiState.speakerMuted)
                                audioService?.setSpeakerMuted(uiState.speakerMuted)
                            },
                            onToggleMicMute = {
                                uiState = uiState.copy(micMuted = !uiState.micMuted)
                                audioService?.setMicMuted(uiState.micMuted)
                            },
                            onMuteOnMediaButtonChange = {
                                uiState = uiState.copy(muteOnMediaButton = it)
                                settingsRepo.saveMuteOnMediaButton(it)
                            },
                            onThemeModeChange = {
                                uiState = uiState.copy(themeMode = it)
                                settingsRepo.saveThemeMode(it)
                            },
                            onDynamicColorsChange = {
                                uiState = uiState.copy(dynamicColorsEnabled = it)
                                settingsRepo.saveDynamicColors(it)
                            },
                            onResetSettings = {
                                settingsRepo.resetDefaults()
                                uiState = uiState.copy(
                                    bufferSize = settingsRepo.getBufferSize(),
                                    bufferMode = settingsRepo.getBufferMode(),
                                    latencyPreset = settingsRepo.getLatencyPreset(),
                                    periodSizeOption = settingsRepo.getPeriodSize(),
                                    engineTypeOption = settingsRepo.getEngineType(),
                                    useOboeOption = settingsRepo.getUseOboe(),
                                    sampleRateOption = settingsRepo.getSampleRate(),
                                    uacVersionOption = settingsRepo.getUacVersion(),
                                    keepAdbOption = settingsRepo.getKeepAdb(),

                                    autoRestartOnOutputChange = settingsRepo.getAutoRestartOnOutputChange(),
                                    activeDirectionsOption = settingsRepo.getActiveDirections(),
                                    micSourceOption = settingsRepo.getMicSource(),
                                    recordingFormat = settingsRepo.getRecordingFormat(),
                                    recordingDirectory = AudioRecordingStore(this, settingsRepo).directoryLabel(),
                                    notificationEnabled = settingsRepo.getNotificationEnabled(),
                                    showKernelNotice = false,
                                    showOldKernelNotice = false,
                                    showNoUacSupportError = false,
                                    showGadgetSetupError = false,
                                    showKeepAdbError = false,
                                    lastGadgetFailureWasKeepAdb = false,
                                    lastGadgetActionWasEnable = false,
                                    gadgetStatusError = null,
                                    keepScreenOnOption = settingsRepo.getKeepScreenOn(),
                                    screensaverEnabled = settingsRepo.getScreensaverEnabled(),
                                    screensaverTimeout = settingsRepo.getScreensaverTimeout(),
                                    screensaverRepositionInterval = settingsRepo.getScreensaverRepositionInterval(),
                                    screensaverDvdMode = settingsRepo.getScreensaverDvdMode(),
                                    screensaverDvdSpeed = settingsRepo.getScreensaverDvdSpeed(),
                                    screensaverFpsLimit = settingsRepo.getScreensaverFpsLimit(),
                                    screensaverFullscreen = settingsRepo.getScreensaverFullscreen(),
                                    muteOnMediaButton = settingsRepo.getMuteOnMediaButton(),
                                    themeMode = settingsRepo.getThemeMode(),
                                    dynamicColorsEnabled = settingsRepo.getDynamicColors()
                                )
                            },
                            onToggleLogs = { uiState = uiState.copy(isLogsExpanded = !uiState.isLogsExpanded) }
                        )
                    }
                }
            }
        }
    }

    private fun restoreUiState() {
        audioService?.let { service ->
            Thread {
                val gadgetActive = UsbGadgetManager.isGadgetActive()
                // Clear stale restore data when the gadget isn't active (crash/reboot)
                if (!gadgetActive && settingsRepo.hasSavedRestoreState()) {
                    settingsRepo.clearOriginalIdentity()
                    settingsRepo.clearOriginalGadgetState()
                    settingsRepo.clearOriginalUsbConfig()
                    appendLog("[App] Cleared stale USB restore state from previous session.")
                }
                runOnUiThread {
                    uiState = uiState.copy(isGadgetEnabled = gadgetActive)
                    service.setGadgetEnabled(gadgetActive)

                    if (service.isBridgeRunning) {
                        uiState = uiState.copy(isServiceRunning = true)
                        appendLog("[App] Restored connection to active stream")
                    }
                }
                service.updateUiState()
                service.refreshNotification()
                service.broadcastGadgetStatus()
                service.broadcastRecordingState()
            }.start()
        }
    }

    private fun appendLog(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[$time] $msg\n"

        var currentText = uiState.logText + line
        // Circular Buffer Logic
        if (currentText.length > 100000) {
            val excess = currentText.length - 80000
            val cutIndex = currentText.indexOf('\n', excess)
            if (cutIndex != -1) {
                currentText = currentText.substring(cutIndex + 1)
            }
        }
        uiState = uiState.copy(logText = currentText)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(logReceiver)
        unregisterReceiver(stateReceiver)
        unregisterReceiver(statsReceiver)
        unregisterReceiver(gadgetResultReceiver)
        unregisterReceiver(gadgetStatusReceiver)
        unregisterReceiver(recordingReceiver)
        if (uiState.isAppBound) unbindService(connection)
    }

    private fun startBridgeWithState() {
        audioService?.startBridge(
             uiState.bufferSize.toInt(),
             uiState.periodSizeOption,
             if (uiState.useOboeOption) 3 else uiState.engineTypeOption,
             uiState.sampleRateOption,
             uiState.activeDirectionsOption,
             uiState.micSourceOption
        )
    }
}

/**
 * Returns a [ColorScheme] where every color is individually animated toward [target].
 * This produces a smooth whole-theme transition rather than an instant snap
 * when the theme mode or dynamic colors setting changes.
 */
@Composable
fun animateColorScheme(
    target: ColorScheme,
    durationMs: Int = 400
): ColorScheme {
    val spec = tween<Color>(durationMillis = durationMs, easing = FastOutSlowInEasing)
    @Composable fun Color.anim() = animateColorAsState(this, spec).value
    return ColorScheme(
        primary                  = target.primary.anim(),
        onPrimary                = target.onPrimary.anim(),
        primaryContainer         = target.primaryContainer.anim(),
        onPrimaryContainer       = target.onPrimaryContainer.anim(),
        inversePrimary           = target.inversePrimary.anim(),
        secondary                = target.secondary.anim(),
        onSecondary              = target.onSecondary.anim(),
        secondaryContainer       = target.secondaryContainer.anim(),
        onSecondaryContainer     = target.onSecondaryContainer.anim(),
        tertiary                 = target.tertiary.anim(),
        onTertiary               = target.onTertiary.anim(),
        tertiaryContainer        = target.tertiaryContainer.anim(),
        onTertiaryContainer      = target.onTertiaryContainer.anim(),
        background               = target.background.anim(),
        onBackground             = target.onBackground.anim(),
        surface                  = target.surface.anim(),
        onSurface                = target.onSurface.anim(),
        surfaceVariant           = target.surfaceVariant.anim(),
        onSurfaceVariant         = target.onSurfaceVariant.anim(),
        surfaceTint              = target.surfaceTint.anim(),
        inverseSurface           = target.inverseSurface.anim(),
        inverseOnSurface         = target.inverseOnSurface.anim(),
        error                    = target.error.anim(),
        onError                  = target.onError.anim(),
        errorContainer           = target.errorContainer.anim(),
        onErrorContainer         = target.onErrorContainer.anim(),
        outline                  = target.outline.anim(),
        outlineVariant           = target.outlineVariant.anim(),
        scrim                    = target.scrim.anim(),
        surfaceBright            = target.surfaceBright.anim(),
        surfaceDim               = target.surfaceDim.anim(),
        surfaceContainer         = target.surfaceContainer.anim(),
        surfaceContainerHigh     = target.surfaceContainerHigh.anim(),
        surfaceContainerHighest  = target.surfaceContainerHighest.anim(),
        surfaceContainerLow      = target.surfaceContainerLow.anim(),
        surfaceContainerLowest   = target.surfaceContainerLowest.anim(),
        primaryFixed            = target.primaryFixed.anim(),
        primaryFixedDim         = target.primaryFixedDim.anim(),
        onPrimaryFixed          = target.onPrimaryFixed.anim(),
        onPrimaryFixedVariant   = target.onPrimaryFixedVariant.anim(),
        secondaryFixed          = target.secondaryFixed.anim(),
        secondaryFixedDim       = target.secondaryFixedDim.anim(),
        onSecondaryFixed        = target.onSecondaryFixed.anim(),
        onSecondaryFixedVariant = target.onSecondaryFixedVariant.anim(),
        tertiaryFixed           = target.tertiaryFixed.anim(),
        tertiaryFixedDim        = target.tertiaryFixedDim.anim(),
        onTertiaryFixed         = target.onTertiaryFixed.anim(),
        onTertiaryFixedVariant  = target.onTertiaryFixedVariant.anim(),
    )
}
