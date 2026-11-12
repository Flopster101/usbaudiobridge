package com.flopster101.usbaudiobridge

data class MainUiState(
    val isGadgetEnabled: Boolean = false,
    val isGadgetPending: Boolean = false,  // Gadget operation in progress?
    val isServiceRunning: Boolean = false, // Streaming active?
    val isCapturePending: Boolean = false, // Capture operation in progress?
    val runningDirections: Int = 0,        // Active directions reported by service
    val isAppBound: Boolean = false,       // Service bound?

    // Config
    val bufferSize: Float = 4800f,
    val bufferMode: Int = 0, // 0 = Simple (Presets), 1 = Advanced (Slider)
    val latencyPreset: Int = 2, // 2 = Normal
    val periodSizeOption: Int = 0, // 0 = Auto
    val engineTypeOption: Int = 0, // 0 = AAudio, 1 = OpenSL, 2 = AudioTrack
    val useOboeOption: Boolean = true, // Prefer Oboe (auto backend selection)
    val sampleRateOption: Int = 48000,
    val uacVersionOption: Int = 2, // 1 = UAC1, 2 = UAC2
    val keepAdbOption: Boolean = false,
    val keepAdbSupported: Boolean = true,
    val keepAdbReason: String? = null,
    val usbEnvironmentLabel: String? = null,
    val autoRestartOnOutputChange: Boolean = false,
    val activeDirectionsOption: Int = 1, // 1=Speaker, 2=Mic, 3=Both
    val micSourceOption: Int = 6, // 6=VoiceRec (Default/Auto)
    val notificationEnabled: Boolean = true,
    val showKernelNotice: Boolean = false,
    val showOldKernelNotice: Boolean = false,
    val showNoUacSupportError: Boolean = false,
    val showGadgetSetupError: Boolean = false,
    val showKeepAdbError: Boolean = false,
    val lastGadgetFailureWasKeepAdb: Boolean = false,
    val lastGadgetActionWasEnable: Boolean = false,
    val gadgetStatusError: String? = null,
    val keepScreenOnOption: Boolean = false,
    val screensaverEnabled: Boolean = false,
    val screensaverTimeout: Int = 15,
    val screensaverRepositionInterval: Int = 5,
    val screensaverDvdMode: Boolean = false,
    val screensaverDvdSpeed: Int = 140,
    val screensaverFpsLimit: Int = 30,
    val screensaverFullscreen: Boolean = true,
    val screensaverActive: Boolean = false,
    val speakerMuted: Boolean = false,
    val micMuted: Boolean = false,
    val muteOnMediaButton: Boolean = true,
    val recordingFormat: Int = 0,
    val recordingDirectory: String = "Music/UsbAudioBridge",
    val isRecordingActive: Boolean = false,
    val isRecordingPending: Boolean = false,
    val recordingFileName: String? = null,
    val recordingDuration: String = "0:00",
    val recordingError: String? = null,

    // Appearance
    val themeMode: Int = 0,             // 0 = Auto (system), 1 = Dark, 2 = Light
    val dynamicColorsEnabled: Boolean = true,

    // Status
    val serviceState: String = "Inactive",
    val serviceStateColor: Long = 0xFF888888, // ARGB Long
    val sampleRate: String = "--",
    val periodSize: String = "--",
    val currentBuffer: String = "--",

    // Gadget Status
    val udcController: String = "--",
    val activeFunctions: String = "--",

    // Logs
    val logText: String = "",
    val isLogsExpanded: Boolean = false,

    // Playback device
    val playbackDeviceType: PlaybackDeviceType = PlaybackDeviceType.UNKNOWN
)

fun MainUiState.getGadgetStatusLabel(): String {
    if (isGadgetPending) {
        return if (lastGadgetActionWasEnable) "Enabling..." else "Disabling..."
    }
    if (!isGadgetEnabled) return "Disabled"

    val active = activeFunctions.lowercase()
    return when {
        active.contains("uac1") -> "UAC1"
        active.contains("uac2") -> "UAC2"
        uacVersionOption == 1 -> "UAC1"
        else -> "UAC2"
    }
}

fun MainUiState.getGadgetStatusColor(): Long {
    return when {
        isGadgetPending -> 0xFFFFC107
        isGadgetEnabled -> 0xFF4CAF50
        else -> 0xFF888888
    }
}
