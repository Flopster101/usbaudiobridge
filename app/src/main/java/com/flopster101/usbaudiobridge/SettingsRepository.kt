package com.flopster101.usbaudiobridge

import android.content.Context

class SettingsRepository(context: Context) {
    companion object {
        const val RECORDING_FORMAT_WAV = 0
        const val RECORDING_FORMAT_FLAC = 1
    }

    private val prefs = context.getSharedPreferences("UsbAudioSettings", Context.MODE_PRIVATE)
    val nativeLibraryDir: String = context.applicationInfo.nativeLibraryDir

    fun saveBufferSize(size: Float) = prefs.edit().putFloat("buffer_size", size).apply()
    fun getBufferSize(): Float = prefs.getFloat("buffer_size", 4800f)

    fun saveBufferMode(mode: Int) = prefs.edit().putInt("buffer_mode", mode).apply()
    fun getBufferMode(): Int = prefs.getInt("buffer_mode", 0) // 0 = Simple, 1 = Advanced

    fun saveLatencyPreset(preset: Int) = prefs.edit().putInt("latency_preset", preset).apply()
    fun getLatencyPreset(): Int = prefs.getInt("latency_preset", 4) // 4 = Balanced

    fun savePeriodSize(size: Int) = prefs.edit().putInt("period_size", size).apply()
    fun getPeriodSize(): Int = prefs.getInt("period_size", 0)

    fun saveEngineType(type: Int) = prefs.edit().putInt("engine_type", type).apply()
    fun getEngineType(): Int = prefs.getInt("engine_type", 0)

    fun saveUseOboe(enabled: Boolean) = prefs.edit().putBoolean("use_oboe", enabled).apply()
    fun getUseOboe(): Boolean = prefs.getBoolean("use_oboe", true)

    fun saveSampleRate(rate: Int) = prefs.edit().putInt("sample_rate", rate).apply()
    fun getSampleRate(): Int = prefs.getInt("sample_rate", 48000)

    fun saveUacVersion(version: Int) = prefs.edit().putInt("uac_version", version).apply()
    fun getUacVersion(): Int = prefs.getInt("uac_version", 2) // 2 = UAC2 (default)

    fun saveKeepAdb(enabled: Boolean) = prefs.edit().putBoolean("keep_adb", enabled).apply()
    fun getKeepAdb(): Boolean = prefs.getBoolean("keep_adb", false)

    // If true: auto-restart stream on output change. If false: stop capture when output disconnects.
    fun saveAutoRestartOnOutputChange(enabled: Boolean) = prefs.edit().putBoolean("auto_restart_output", enabled).apply()
    fun getAutoRestartOnOutputChange(): Boolean = prefs.getBoolean("auto_restart_output", false)

    // If true: mute speaker bridge on media button press (Headset Play/Pause)
    fun saveMuteOnMediaButton(enabled: Boolean) = prefs.edit().putBoolean("mute_on_media_button", enabled).apply()
    fun getMuteOnMediaButton(): Boolean = prefs.getBoolean("mute_on_media_button", true)

    // 1 = Speaker (Host->Phone), 2 = Mic (Phone->Host), 3 = Both
    fun saveActiveDirections(mask: Int) = prefs.edit().putInt("active_directions", mask).apply()
    fun getActiveDirections(): Int = prefs.getInt("active_directions", 1)

    // Mic Source (Preset)
    // 0=Unspecified/Auto, 1=Generic, 5=Camcorder, 6=VoiceRec, 7=VoiceComm, 9=Unprocessed, 10=Performance
    fun saveMicSource(source: Int) = prefs.edit().putInt("mic_source", source).apply()
    fun getMicSource(): Int = prefs.getInt("mic_source", 6) // Default to Voice Recognition (6) for best results usually

    fun saveRecordingFormat(format: Int) = prefs.edit().putInt("recording_format", format).apply()
    fun getRecordingFormat(): Int = prefs.getInt("recording_format", RECORDING_FORMAT_WAV)

    fun saveRecordingDirectory(uri: String?) {
        val editor = prefs.edit()
        if (uri == null) editor.remove("recording_directory") else editor.putString("recording_directory", uri)
        editor.apply()
    }
    fun getRecordingDirectory(): String? = prefs.getString("recording_directory", null)

    // Kernel compatibility notice
    fun shouldShowKernelNotice(): Boolean = !prefs.getBoolean("kernel_notice_dismissed", false)
    fun setKernelNoticeDismissed() = prefs.edit().putBoolean("kernel_notice_dismissed", true).apply()

    // Old kernel warning (Windows audio device disabled)
    fun shouldShowOldKernelNotice(): Boolean = !prefs.getBoolean("old_kernel_notice_dismissed", false)
    fun setOldKernelNoticeDismissed() = prefs.edit().putBoolean("old_kernel_notice_dismissed", true).apply()

    // Notification enabled
    fun saveNotificationEnabled(enabled: Boolean) = prefs.edit().putBoolean("notification_enabled", enabled).apply()
    fun getNotificationEnabled(): Boolean = prefs.getBoolean("notification_enabled", true)

    // Keep screen on
    fun saveKeepScreenOn(enabled: Boolean) = prefs.edit().putBoolean("keep_screen_on", enabled).apply()
    fun getKeepScreenOn(): Boolean = prefs.getBoolean("keep_screen_on", false)

    // Screensaver
    fun saveScreensaverEnabled(enabled: Boolean) = prefs.edit().putBoolean("screensaver_enabled", enabled).apply()
    fun getScreensaverEnabled(): Boolean = prefs.getBoolean("screensaver_enabled", false)

    fun saveScreensaverTimeout(seconds: Int) = prefs.edit().putInt("screensaver_timeout", seconds).apply()
    fun getScreensaverTimeout(): Int = prefs.getInt("screensaver_timeout", 15) // Default 15 seconds

    fun saveScreensaverRepositionInterval(seconds: Int) = prefs.edit().putInt("screensaver_reposition_interval", seconds).apply()
    fun getScreensaverRepositionInterval(): Int = prefs.getInt("screensaver_reposition_interval", 5) // Default 5 seconds

    fun saveScreensaverDvdMode(enabled: Boolean) = prefs.edit().putBoolean("screensaver_dvd_mode", enabled).apply()
    fun getScreensaverDvdMode(): Boolean = prefs.getBoolean("screensaver_dvd_mode", false) // Default disabled

    fun saveScreensaverDvdSpeed(speedPxPerSec: Int) = prefs.edit().putInt("screensaver_dvd_speed", speedPxPerSec).apply()
    fun getScreensaverDvdSpeed(): Int = prefs.getInt("screensaver_dvd_speed", 140) // Default medium speed

    fun saveScreensaverFpsLimit(limit: Int) = prefs.edit().putInt("screensaver_fps_limit", limit).apply()
    fun getScreensaverFpsLimit(): Int = prefs.getInt("screensaver_fps_limit", 30) // Default 30 FPS

    fun saveScreensaverFullscreen(enabled: Boolean) = prefs.edit().putBoolean("screensaver_fullscreen", enabled).apply()
    fun getScreensaverFullscreen(): Boolean = prefs.getBoolean("screensaver_fullscreen", true) // Default true

    fun savePausedHalMonitor(pid: Int, tid: Int, tracerPid: Int) = prefs.edit()
        .putInt("paused_hal_pid", pid)
        .putInt("paused_hal_tid", tid)
        .putInt("paused_hal_tracer_pid", tracerPid)
        .apply()

    fun getPausedHalMonitor(): Triple<Int, Int, Int>? {
        val pid = prefs.getInt("paused_hal_pid", -1)
        val tid = prefs.getInt("paused_hal_tid", -1)
        val tracerPid = prefs.getInt("paused_hal_tracer_pid", -1)
        return if (pid > 0 && tid > 0 && tracerPid > 0) Triple(pid, tid, tracerPid) else null
    }

    fun clearPausedHalMonitor() {
        prefs.edit().remove("paused_hal_pid").remove("paused_hal_tid").remove("paused_hal_tracer_pid").apply()
    }

    fun saveOriginalIdentity(manufacturer: String, product: String, serial: String) {
        prefs.edit()
            .putString("orig_man", manufacturer)
            .putString("orig_prod", product)
            .putString("orig_serial", serial)
            .apply()
    }

    fun getOriginalIdentity(): Triple<String?, String?, String?> {
        val m = prefs.getString("orig_man", null)
        val p = prefs.getString("orig_prod", null)
        val s = prefs.getString("orig_serial", null)
        return Triple(m, p, s)
    }

    fun clearOriginalIdentity() {
        prefs.edit()
            .remove("orig_man")
            .remove("orig_prod")
            .remove("orig_serial")
            .apply()
    }

    fun saveOriginalUsbConfig(sysConfig: String?, vendorConfig: String?) {
        val editor = prefs.edit()
        if (sysConfig != null) editor.putString("orig_sys_usb_config", sysConfig)
        if (vendorConfig != null) editor.putString("orig_vendor_usb_config", vendorConfig)
        editor.apply()
    }

    fun getOriginalUsbConfig(): Pair<String?, String?> {
        val sys = prefs.getString("orig_sys_usb_config", null)
        val vendor = prefs.getString("orig_vendor_usb_config", null)
        return Pair(sys, vendor)
    }

    fun clearOriginalUsbConfig() {
        prefs.edit()
            .remove("orig_sys_usb_config")
            .remove("orig_vendor_usb_config")
            .apply()
    }

    // True if original USB state was persisted (possibly stale after a crash)
    fun hasSavedRestoreState(): Boolean {
        return prefs.getString("orig_gadget_links", null) != null ||
            prefs.getString("orig_sys_usb_config", null) != null ||
            prefs.getString("orig_vendor_usb_config", null) != null ||
            prefs.getString("orig_man", null) != null
    }

    fun saveOriginalGadgetState(links: String, ids: String, osDesc: String, configStr: String, udc: String) {
        prefs.edit()
            .putString("orig_gadget_links", links)
            .putString("orig_gadget_ids", ids)
            .putString("orig_gadget_osdesc", osDesc)
            .putString("orig_gadget_config", configStr)
            .putString("orig_gadget_udc", udc)
            .apply()
    }

    fun getOriginalGadgetState(): State {
        return State(
            links = prefs.getString("orig_gadget_links", null),
            ids = prefs.getString("orig_gadget_ids", null),
            osDesc = prefs.getString("orig_gadget_osdesc", null),
            configStr = prefs.getString("orig_gadget_config", null),
            udc = prefs.getString("orig_gadget_udc", null)
        )
    }

    data class State(
        val links: String?,
        val ids: String?,
        val osDesc: String?,
        val configStr: String?,
        val udc: String?
    )

    fun clearOriginalGadgetState() {
        prefs.edit()
            .remove("orig_gadget_links")
            .remove("orig_gadget_ids")
            .remove("orig_gadget_osdesc")
            .remove("orig_gadget_config")
            .remove("orig_gadget_udc")
            .apply()
    }

    // Appearance
    fun saveThemeMode(mode: Int) = prefs.edit().putInt("theme_mode", mode).apply()
    fun getThemeMode(): Int = prefs.getInt("theme_mode", 0) // 0 = Auto

    fun saveDynamicColors(enabled: Boolean) = prefs.edit().putBoolean("dynamic_colors", enabled).apply()
    fun getDynamicColors(): Boolean = prefs.getBoolean("dynamic_colors", true)

    fun resetDefaults() {
        prefs.edit().clear().apply()
    }
}
