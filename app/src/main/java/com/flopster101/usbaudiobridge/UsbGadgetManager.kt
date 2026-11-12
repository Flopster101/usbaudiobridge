package com.flopster101.usbaudiobridge

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataOutputStream

/**
 * Represents the current status of the USB gadget subsystem.
 */
data class GadgetStatus(
    val udcController: String,      // The UDC controller name (e.g., "13600000.dwc3") or "None"
    val activeFunctions: List<String>,  // List of active function names (e.g., ["uac2", "ffs.adb"])
    val isBound: Boolean            // Whether the gadget is bound to a controller
)

object UsbGadgetManager {
    private const val TAG = "UsbGadgetManager"
    private const val UAC_VERSION_1 = 1
    private const val UAC_VERSION_2 = 2

    private const val GADGET_ROOT = "/config/usb_gadget/g1"
    private const val CH_MASK = 3
    private const val SAMPLE_SIZE = 2

    // Explicit Identity to force Windows Re-enumeration
    private const val VENDOR_ID = "0x1d6b" // Linux Foundation
    private const val PRODUCT_ID = "0x0104" // Multifunction Composite Gadget

    private const val MANUFACTURER = "FloppyKernel Project"
    private const val PRODUCT = "USB Audio Bridge"

    // Mutex to prevent concurrent gadget operations
    private val gadgetMutex = Mutex()

    private fun normalizeUacVersion(uacVersion: Int): Int {
        return if (uacVersion == UAC_VERSION_1) UAC_VERSION_1 else UAC_VERSION_2
    }

    private fun getUacFunctionName(uacVersion: Int): String {
        return if (uacVersion == UAC_VERSION_1) "uac1.0" else "uac2.0"
    }

    private fun getUacDisplayName(uacVersion: Int): String {
        return if (uacVersion == UAC_VERSION_1) "UAC1" else "UAC2"
    }

    /**
     * Check if root access is granted.
     */
    fun isRootGranted(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    private var cachedStrategy: GadgetStrategy? = null

    // Cached per process lifetime.
    fun detectGadgetStrategy(): GadgetStrategy {
        cachedStrategy?.let { return it }

        val strategy = try {
            val vendorManufacturer = runRootCommandGetOutput("getprop ro.product.vendor.manufacturer")
                .ifBlank { runRootCommandGetOutput("getprop ro.product.manufacturer") }
            val board = runRootCommandGetOutput("getprop ro.board.platform")
            val hardware = runRootCommandGetOutput("getprop ro.hardware")
            val samsungHalStopped = vendorManufacturer.contains("samsung", ignoreCase = true) &&
                runRootCommandGetOutput("getprop init.svc.vendor.usb-gadget-hal") == "stopped"

            // Match both HAL families (gadget-service.* and usb@1.x-service)
            val halProcs = runRootCommandGetOutput(
                "ps -A 2>/dev/null | grep -iE 'usb.*gadget|gadget.*usb|usb@1\\.|usb@2\\.'"
            )
            val env = when {
                halProcs.contains("gadget-service.samsung", ignoreCase = true) || samsungHalStopped ->
                    GadgetEnvironment.SAMSUNG_LIBPIXELUSB
                halProcs.contains("gadget-service.qti", ignoreCase = true) ->
                    GadgetEnvironment.QUALCOMM_HAL
                halProcs.contains("usb@1.1-service", ignoreCase = true) ||
                    halProcs.contains("usb@1.2-service", ignoreCase = true) ||
                    halProcs.contains("usb@1.3-service", ignoreCase = true) ||
                    halProcs.contains("usb@2.0-service", ignoreCase = true) ->
                    if (vendorManufacturer.contains("samsung", ignoreCase = true)) {
                        GadgetEnvironment.SAMSUNG_STOCK_INITRC
                    } else {
                        GadgetEnvironment.AOSP_GENERIC
                    }
                halProcs.contains("usb-gadget-hal", ignoreCase = true) ||
                    halProcs.contains("gadget-service.example", ignoreCase = true) ->
                    GadgetEnvironment.AOSP_GENERIC
                hardware.contains("mt", ignoreCase = true) ||
                    board.contains("mt", ignoreCase = true) ||
                    hardware.contains("mediatek", ignoreCase = true) ->
                    GadgetEnvironment.MTK_CONFIGFS
                halProcs.isNotBlank() -> GadgetEnvironment.AOSP_GENERIC
                else -> GadgetEnvironment.UNKNOWN
            }
            GadgetStrategy.forEnvironment(env)
        } catch (e: Exception) {
            Log.e(TAG, "Gadget strategy detection failed: ${e.message}")
            GadgetStrategy.forEnvironment(GadgetEnvironment.UNKNOWN)
        }

        cachedStrategy = strategy
        return strategy
    }

    private fun pauseGadgetHalMonitor(
        strategy: GadgetStrategy,
        settingsRepo: SettingsRepository?,
        logCallback: (String) -> Unit
    ): Boolean {
        if (strategy.environment != GadgetEnvironment.SAMSUNG_LIBPIXELUSB) return true
        settingsRepo?.getPausedHalMonitor()?.let {
            if (runRootCommand("test -d /proc/${it.third}", {})) return true
            settingsRepo.clearPausedHalMonitor()
        }

        val pid = runRootCommandGetOutput("pidof android.hardware.usb.gadget-service.samsung")
            .substringBefore(' ').toIntOrNull()
        if (pid == null) {
            logCallback("[Gadget] Could not find Samsung gadget HAL process.")
            return false
        }
        val monitorPaths = runRootCommandGetOutput(
            "grep -l '^do_epoll_wait$' /proc/$pid/task/*/wchan"
        ).lineSequence().filter { it.isNotBlank() }.toList()
        val tid = monitorPaths.singleOrNull()?.split('/')?.getOrNull(4)?.toIntOrNull()
        if (tid == null) {
            logCallback("[Gadget] Could not identify the HAL FFS monitor thread.")
            return false
        }

        val helper = "${settingsRepo?.nativeLibraryDir}/libgadget_monitor_pause.so"
        logCallback("[Gadget] Pausing HAL FFS monitor thread $tid (PID $pid).")
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "$helper pause $pid $tid"))
        val tracerPid = process.inputStream.bufferedReader().readLine()?.trim()?.toIntOrNull()
        if (tracerPid == null || !runRootCommand("test -d /proc/$tracerPid", {})) {
            logCallback("[Gadget] Could not pause the HAL FFS monitor.")
            return false
        }
        settingsRepo?.savePausedHalMonitor(pid, tid, tracerPid)
        return true
    }

    fun resumeGadgetHalMonitor(settingsRepo: SettingsRepository?, logCallback: (String) -> Unit): Boolean {
        val paused = settingsRepo?.getPausedHalMonitor() ?: return true
        val (_, tid, tracerPid) = paused
        val tracer = runRootCommandGetOutput("cat /proc/$tracerPid/cmdline")
        if (!tracer.contains("libgadget_monitor_pause.so")) {
            settingsRepo.clearPausedHalMonitor()
            return true
        }

        logCallback("[Gadget] Resuming HAL FFS monitor thread $tid (tracer $tracerPid).")
        val helper = "${settingsRepo?.nativeLibraryDir}/libgadget_monitor_pause.so"
        if (!runRootCommand("$helper resume $tracerPid", logCallback)) return false
        for (i in 1..20) {
            if (!runRootCommand("test -d /proc/$tracerPid", {})) {
                settingsRepo.clearPausedHalMonitor()
                return true
            }
            Thread.sleep(50)
        }
        logCallback("[Gadget] Could not detach from the HAL FFS monitor.")
        return false
    }

    private fun getRootSolution(): String {
        try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "--version"))
            val output = p.inputStream.bufferedReader().readText().trim()
            return when {
                output.contains("APatch", ignoreCase = true) -> "APatch"
                output.contains("MAGISK", ignoreCase = true) -> "Magisk"
                output.contains("KernelSU", ignoreCase = true) || output.contains("ksu", ignoreCase = true) -> "KernelSU"
                else -> "Unknown"
            }
        } catch (e: Exception) {
            return "Unknown"
        }
    }

    /**
     * `sys.usb.config` is the fast path but not authoritative: some HALs (QTI,
     * after `svc usb setFunctions ''`) leave it "none" while adb is bound. Fall
     * back to configfs ground truth (ffs.adb link + bound UDC).
     */
    private fun isAdbCurrentlyActive(): Boolean {
        return try {
            val p = Runtime.getRuntime().exec("getprop sys.usb.config")
            val config = p.inputStream.bufferedReader().readText().trim()
            if (config.contains("adb")) {
                true
            } else {
                val links = runRootCommandGetOutput(
                    "ls -l $GADGET_ROOT/configs/b.1/ 2>/dev/null | grep ffs.adb"
                )
                val udc = getUdcContent()
                links.contains("ffs.adb") && udc.isNotEmpty() && udc != "none"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking ADB status: ${e.message}")
            false
        }
    }

    private fun runRootCommandGetOutput(command: String): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            p.inputStream.bufferedReader().readText().trim()
        } catch (e: Exception) {
            ""
        }
    }

    private fun configureMtkMode(udcName: String, enable: Boolean, logCallback: (String) -> Unit) {
        // Only run this on MediaTek devices
        val hardware = runRootCommandGetOutput("getprop ro.hardware")
        val platform = runRootCommandGetOutput("getprop ro.board.platform")

        val isMtk = hardware.contains("mt", ignoreCase = true) ||
                    hardware.contains("mediatek", ignoreCase = true) ||
                    platform.contains("mt", ignoreCase = true) ||
                    platform.contains("mediatek", ignoreCase = true)

        if (!isMtk) return

        val value = if (enable) "1" else "0"
        val paths = listOf(
            "/sys/class/udc/$udcName/device/mode",
            "/sys/class/udc/$udcName/device/cmode"
        )

        for (path in paths) {
            if (runRootCommand("test -f $path", {})) {
                logCallback("[Gadget] Setting MTK specific mode: $path -> $value")
                runRootCommand("echo '$value' > $path", {})
            }
        }
    }

    /**
     * Check if the ffs.adb function exists in configfs.
     */
    private fun isFfsAdbFunctionAvailable(): Boolean {
        return runRootCommand("test -d $GADGET_ROOT/functions/ffs.adb", {})
    }

    /**
     * Restore USB config to system control.
     * This triggers the system (HAL or init.rc) to reconfigure USB.
     */
    private fun restoreUsbConfig(settingsRepo: SettingsRepository?, logCallback: (String) -> Unit) {
        val (savedSys, savedVendor) = settingsRepo?.getOriginalUsbConfig() ?: Pair(null, null)

        val commands = mutableListOf<String>()
        if (!savedVendor.isNullOrBlank()) {
            commands.add("setprop vendor.usb.config $savedVendor")
        }
        if (!savedSys.isNullOrBlank()) {
            commands.add("setprop sys.usb.config $savedSys")
        }

        if (commands.isEmpty()) {
            // Fall back to the framework's persisted default (e.g. adb)
            val persistDefault = runRootCommandGetOutput("getprop persist.sys.usb.config")
            if (!persistDefault.isNullOrBlank()) {
                commands.add("setprop sys.usb.config $persistDefault")
            }
        }

        // Clear sys.usb.state so the framework reapplies its own config
        commands.add("setprop sys.usb.state \"\"")

        runRootCommands(commands, logCallback)
    }

    /**
     * Extract a single framework-settable USB function from a stored config string
     * (e.g. "adb" -> null, "mtp,adb" -> "mtp", "mtp" -> "mtp"). Non-settable
     * functions such as adb are stripped so we only hand the framework values it
     * accepts via `svc usb setFunctions`.
     */
    private fun getSettableFunctionFromConfig(config: String?): String? {
        if (config.isNullOrBlank()) return null
        val settable = listOf("mtp", "ptp", "rndis", "midi", "ncm")
        return config.split(",")
            .map { it.trim().lowercase() }
            .firstOrNull { it in settable }
    }

    /**
     * Soft unbind - only uses direct UDC writes, preserves ffs.adb state.
     * Returns true if unbind succeeded without needing system property changes.
     *
     * This is the only safe way to unbind if we want to preserve ADB, because
     * setting sys.usb.config=none triggers init.rc to stop adbd.
     */
    private suspend fun softUnbind(logCallback: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val initialUdc = getUdcContent()
        logCallback("[Gadget] Soft unbind starting (current UDC='$initialUdc')...")

        if (initialUdc.isEmpty() || initialUdc == "none" || initialUdc.isBlank()) {
            logCallback("[Gadget] UDC already unbound.")
            return@withContext true
        }

        // IMPORTANT: On some devices (Samsung), UDC file needs chmod 666 before we can write to it
        Runtime.getRuntime().exec(arrayOf("su", "-c", "chmod 666 $GADGET_ROOT/UDC")).waitFor()

        // Try to unbind by writing empty to UDC
        Runtime.getRuntime().exec(arrayOf("su", "-c", "echo '' > $GADGET_ROOT/UDC")).waitFor()

        // Check if unbind succeeded
        for (i in 1..8) {
            Thread.sleep(400)
            val currentUdc = getUdcContent()
            if (currentUdc.isEmpty() || currentUdc == "none" || currentUdc.isBlank()) {
                logCallback("[Gadget] Soft unbind successful.")
                return@withContext true
            }
        }

        // Try writing "none" explicitly
        Runtime.getRuntime().exec(arrayOf("su", "-c", "echo 'none' > $GADGET_ROOT/UDC")).waitFor()

        for (i in 1..4) {
            Thread.sleep(400)
            val currentUdc = getUdcContent()
            if (currentUdc.isEmpty() || currentUdc == "none" || currentUdc.isBlank()) {
                logCallback("[Gadget] Soft unbind successful.")
                return@withContext true
            }
        }

        val finalUdc = getUdcContent()
        logCallback("[Gadget] Soft unbind failed - UDC='$finalUdc' (system is rebinding immediately)")
        return@withContext false
    }

    /**
     * Hard unbind - uses system properties which trigger HAL/init reconfiguration.
     * This WILL disrupt ADB because init.rc stops adbd when sys.usb.config=none.
     */
    private suspend fun hardUnbind(logCallback: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        logCallback("[Gadget] Hard unbind (sys.usb.config=none)...")
        runRootCommands(listOf("setprop sys.usb.config none")) {}

        for (i in 1..10) {
             Thread.sleep(500)
             if (checkUdcReleased()) {
                 logCallback("[Gadget] Hard unbind successful.")
                 return@withContext true
             }
        }

        val udc = getUdcContent()
        logCallback("[Gadget] Hard unbind failed. UDC='$udc'")
        return@withContext false
    }

    private fun checkUdcReleased(): Boolean {
        return try {
            val udc = getUdcContent()
            udc.isEmpty() || udc == "none" || udc.isBlank()
        } catch(e: Exception) { false }
    }

    private fun getUdcContent(): String {
         val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $GADGET_ROOT/UDC"))
         return p.inputStream.bufferedReader().readText().trim()
    }

    private fun getPidForRate(rate: Int): String {
        return when (rate) {
            48000 -> "0x0104"
            44100 -> "0x0105"
            32000 -> "0x0106"
            22050 -> "0x0107"
            88200 -> "0x0108"
            96000 -> "0x0109"
            192000 -> "0x010A"
            else -> "0x010B"
        }
    }

    private fun getBcdDeviceForRate(rate: Int): String {
        return when (rate) {
            44100 -> "0x0244"
            48000 -> "0x0248"
            88200 -> "0x0288"
            96000 -> "0x0296"
            192000 -> "0x0292"
            32000 -> "0x0232"
            22050 -> "0x0222"
            else -> "0x0200"
        }
    }

    private fun getSerialNumberForRate(rate: Int, deviceSerial: String): String {
        return "UAM-SR$rate-$deviceSerial"
    }

    suspend fun enableGadget(
        logCallback: (String) -> Unit,
        sampleRate: Int = 48000,
        settingsRepo: SettingsRepository? = null,
        keepAdb: Boolean = false,
        uacVersion: Int = UAC_VERSION_2
    ): Boolean = withContext(Dispatchers.IO) {
        gadgetMutex.withLock {
            enableGadgetInternal(logCallback, sampleRate, settingsRepo, keepAdb, uacVersion)
        }
    }

    private suspend fun enableGadgetInternal(
        logCallback: (String) -> Unit,
        sampleRate: Int = 48000,
        settingsRepo: SettingsRepository? = null,
        keepAdb: Boolean = false,
        uacVersion: Int = UAC_VERSION_2
    ): Boolean {
        val normalizedUacVersion = normalizeUacVersion(uacVersion)
        val uacFunctionName = getUacFunctionName(normalizedUacVersion)
        val uacDisplayName = getUacDisplayName(normalizedUacVersion)
        val uacFunctionPath = "$GADGET_ROOT/functions/$uacFunctionName"
        val uacKernelConfigOption = if (normalizedUacVersion == UAC_VERSION_1) {
            "CONFIG_USB_CONFIGFS_F_UAC1"
        } else {
            "CONFIG_USB_CONFIGFS_F_UAC2"
        }
        val sysUsbState = if (normalizedUacVersion == UAC_VERSION_1) "uac1" else "uac2"

        logCallback("[Gadget] Configuring $uacDisplayName gadget ($sampleRate Hz)...")

        // Backup original USB config (sys/vendor) if not already stored
        if (settingsRepo != null) {
            val (savedSys, savedVendor) = settingsRepo.getOriginalUsbConfig()
            if (savedSys == null && savedVendor == null) {
                val sysConfig = runRootCommandGetOutput("getprop sys.usb.config")
                    .ifBlank { runRootCommandGetOutput("getprop persist.sys.usb.config") }
                val vendorConfig = runRootCommandGetOutput("getprop vendor.usb.config")
                    .ifBlank { runRootCommandGetOutput("getprop persist.vendor.usb.config") }

                if (sysConfig.isNotBlank() || vendorConfig.isNotBlank()) {
                    settingsRepo.saveOriginalUsbConfig(
                        sysConfig.ifBlank { null },
                        vendorConfig.ifBlank { null }
                    )
                }
            }
        }

        // Check ADB status if user wants to keep it
        val strategy = detectGadgetStrategy()
        logCallback("[Gadget] USB environment: ${strategy.label}")
        var keepAdbEffective = keepAdb
        if (keepAdb && !strategy.supportsKeepAdb) {
            logCallback("[Gadget] Keep ADB not supported on this device (${strategy.label}).")
            logCallback("[Gadget] ${strategy.keepAdbReason}")
            keepAdbEffective = false
        }

        var adbWasActive = false
        var ffsAdbExists = false
        if (keepAdbEffective) {
            adbWasActive = isAdbCurrentlyActive()
            ffsAdbExists = isFfsAdbFunctionAvailable()
            if (adbWasActive && ffsAdbExists) {
                logCallback("[Gadget] ADB is active, will attempt to preserve it.")
            } else if (adbWasActive && !ffsAdbExists) {
                logCallback("[Gadget] ADB active but ffs.adb function not found.")
                adbWasActive = false
            } else {
                logCallback("[Gadget] ADB not currently active, ignoring keepAdb option.")
            }
        }

        var deviceSerial = "UNKNOWN"

        // Backup original strings if not already done
        if (settingsRepo != null) {
            val (savedMan, savedProd, savedSerial) = settingsRepo.getOriginalIdentity()
            if (savedMan == null || savedProd == null || savedSerial == null) {
                try {
                    val pProd = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $GADGET_ROOT/strings/0x409/product"))
                    val currProd = pProd.inputStream.bufferedReader().readText().trim()

                    val pMan = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $GADGET_ROOT/strings/0x409/manufacturer"))
                    val currMan = pMan.inputStream.bufferedReader().readText().trim()

                    val pSerial = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $GADGET_ROOT/strings/0x409/serialnumber"))
                    val currSerial = pSerial.inputStream.bufferedReader().readText().trim()

                    if (currProd.isNotEmpty() && !currProd.contains("Audio Bridge") && !currMan.contains("FloppyKernel")) {
                        settingsRepo.saveOriginalIdentity(currMan, currProd, currSerial)
                        deviceSerial = currSerial
                        logCallback("[Gadget] Backed up original identity: $currMan - $currProd ($currSerial)")
                    } else {
                        val pModel = Runtime.getRuntime().exec("getprop ro.product.model")
                        val fallbackProd = pModel.inputStream.bufferedReader().readText().trim()
                        val pBrand = Runtime.getRuntime().exec("getprop ro.product.manufacturer")
                        val fallbackMan = pBrand.inputStream.bufferedReader().readText().trim()

                        // Try getting serial via su since app user likely can't read it
                        val pSer = Runtime.getRuntime().exec(arrayOf("su", "-c", "getprop ro.serialno"))
                        val fallbackSerial = pSer.inputStream.bufferedReader().readText().trim()

                        settingsRepo.saveOriginalIdentity(fallbackMan, fallbackProd, fallbackSerial)
                        deviceSerial = fallbackSerial
                        logCallback("[Gadget] Backed up identity from props: $fallbackMan - $fallbackProd ($fallbackSerial)")
                    }
                } catch (e: Exception) {
                    logCallback("[Gadget] Failed to backup strings: ${e.message}")
                }
            } else {
                deviceSerial = savedSerial
            }
        }

        val bcdDevice = getBcdDeviceForRate(sampleRate)
        val pid = getPidForRate(sampleRate)
        val serial = getSerialNumberForRate(sampleRate, deviceSerial)

        val needPreserveAdb = keepAdbEffective && adbWasActive && ffsAdbExists

        val links = runRootCommandGetOutput(
            "ls -l $GADGET_ROOT/configs/b.1/ 2>/dev/null | sed -n 's/.* \\([^ /]*\\) -> \\(.*\\)/\\1=\\2/p'"
        )
        val ids = runRootCommandGetOutput(
            "echo v=$(cat $GADGET_ROOT/idVendor 2>/dev/null); " +
            "echo p=$(cat $GADGET_ROOT/idProduct 2>/dev/null); " +
            "echo bcd=$(cat $GADGET_ROOT/bcdDevice 2>/dev/null); " +
            "echo bcdusb=$(cat $GADGET_ROOT/bcdUSB 2>/dev/null)"
        )
        val osDesc = if (runRootCommand("test -f $GADGET_ROOT/os_desc/use", {})) {
            runRootCommandGetOutput(
                "echo use=$(cat $GADGET_ROOT/os_desc/use 2>/dev/null); " +
                "echo code=$(cat $GADGET_ROOT/os_desc/b_vendor_code 2>/dev/null); " +
                "echo qw=$(cat $GADGET_ROOT/os_desc/qw_sign 2>/dev/null)"
            )
        } else {
            ""
        }
        val configStr = runRootCommandGetOutput(
            "cat $GADGET_ROOT/configs/b.1/strings/0x409/configuration 2>/dev/null"
        )
        val udc = getUdcContent()
        val previous = settingsRepo?.getOriginalGadgetState()
        val hasUsablePrevious = !previous?.links.isNullOrBlank()
        if (links.isNotBlank()) {
            settingsRepo?.saveOriginalGadgetState(links, ids, osDesc, configStr, udc)
            logCallback("[Gadget] Captured original gadget state.")
        } else if (hasUsablePrevious) {
            logCallback("[Gadget] USB gadget already empty; keeping previously captured original state.")
        } else {
            logCallback("[Gadget] WARNING: No original gadget state to restore. USB may be left dead on disable.")
        }

        // Stop libpixelusb's stale FFS monitor before it can rebind the custom gadget.
        if (!pauseGadgetHalMonitor(strategy, settingsRepo, logCallback)) return false

        // Step 1: Try soft unbind first
        val softUnbindSucceeded = softUnbind(logCallback)

        if (!softUnbindSucceeded) {
            if (needPreserveAdb) {
                // Soft unbind failed and user wants ADB - we cannot proceed
                logCallback("[Gadget] ERROR: Cannot keep ADB enabled on this device.")
                logCallback("[Gadget] The system immediately rebinds the gadget, preventing ADB preservation.")
                logCallback("[Gadget] Please disable 'Keep ADB' option and try again.")
                return false
            }

            // Soft unbind failed but ADB preservation not needed - use hard unbind
            logCallback("[Gadget] Using hard unbind (ADB will be temporarily disabled)...")
            if (!hardUnbind(logCallback)) {
                logCallback("[Gadget] Aborting: Could not release USB hardware.")
                return false
            }
        }

        // Step 2: Ensure we're fully unbound
        if (!softUnbind(logCallback)) {
            logCallback("[Gadget] UDC already released.")
        }

        // Step 3: Confirm ffs.adb is still usable after unbind
        var adbAvailable = false
        if (softUnbindSucceeded && needPreserveAdb) {
            adbAvailable = isFfsAdbFunctionAvailable()

            if (adbAvailable) {
                logCallback("[Gadget] ffs.adb is available for composite gadget.")
            } else {
                logCallback("[Gadget] ffs.adb became unavailable, proceeding without ADB.")
            }
        }

        applySeLinuxPolicy(logCallback)

        // Step 4: Setup configfs structure
        val configCommands = mutableListOf(
            // Clear existing function links
            "rm -f $GADGET_ROOT/configs/b.1/f* || true",

            // Remove old UAC functions if they exist
            "rmdir $GADGET_ROOT/functions/uac1.0 2>/dev/null || true",
            "rmdir $GADGET_ROOT/functions/uac2.0 2>/dev/null || true",

            // Set Device Identity
            "echo \"$VENDOR_ID\" > $GADGET_ROOT/idVendor",
            "echo \"$pid\" > $GADGET_ROOT/idProduct",
            "echo \"$bcdDevice\" > $GADGET_ROOT/bcdDevice",
            "echo \"0x0200\" > $GADGET_ROOT/bcdUSB",

            // Windows OS Descriptor
            "echo \"1\" > $GADGET_ROOT/os_desc/use",
            "echo \"0x1\" > $GADGET_ROOT/os_desc/b_vendor_code",
            "echo \"MSFT100\" > $GADGET_ROOT/os_desc/qw_sign",

            // Create and configure selected UAC function
            "mkdir -p $uacFunctionPath",
            "echo $sampleRate > $uacFunctionPath/p_srate",
            "echo $CH_MASK > $uacFunctionPath/p_chmask",
            "echo $SAMPLE_SIZE > $uacFunctionPath/p_ssize",
            "echo $sampleRate > $uacFunctionPath/c_srate",
            "echo $CH_MASK > $uacFunctionPath/c_chmask",
            "echo $SAMPLE_SIZE > $uacFunctionPath/c_ssize",
            "echo 2 > $uacFunctionPath/req_number 2>/dev/null || true",

            // Set device strings
            "mkdir -p $GADGET_ROOT/strings/0x409",
            "echo \"$MANUFACTURER\" > $GADGET_ROOT/strings/0x409/manufacturer",
            "echo \"$PRODUCT\" > $GADGET_ROOT/strings/0x409/product",
            "echo \"$serial\" > $GADGET_ROOT/strings/0x409/serialnumber",

            // Set Configuration String
            "mkdir -p $GADGET_ROOT/configs/b.1/strings/0x409",
            "test -e $GADGET_ROOT/os_desc/b.1 || ln -sfn $GADGET_ROOT/configs/b.1 $GADGET_ROOT/os_desc/b.1"
        )

        // Link functions
        if (adbAvailable) {
            configCommands.add("echo \"USB Audio + ADB\" > $GADGET_ROOT/configs/b.1/strings/0x409/configuration")
            configCommands.add("ln -s $uacFunctionPath $GADGET_ROOT/configs/b.1/f1")
            configCommands.add("ln -s $GADGET_ROOT/functions/ffs.adb $GADGET_ROOT/configs/b.1/f2")
        } else {
            configCommands.add("echo \"USB Audio\" > $GADGET_ROOT/configs/b.1/strings/0x409/configuration")
            configCommands.add("ln -s $uacFunctionPath $GADGET_ROOT/configs/b.1/f1")
        }

        if (!runRootCommands(configCommands, logCallback) ||
            !runRootCommand("test -d $uacFunctionPath", {})) {
             logCallback("[Gadget] Error: Your kernel does not support $uacDisplayName.")
             logCallback("[Gadget] This feature requires $uacKernelConfigOption enabled in kernel.")
             return false
        }

        // Not preserving ADB: stop adbd so its bind attempts can't churn the composite. Restarted during disable.
        if (!needPreserveAdb) {
            logCallback("[Gadget] Stopping adbd for UAC2-only session...")
            runRootCommands(listOf("setprop ctl.stop adbd"), logCallback)
        }

        Thread.sleep(500)

        // Step 5: Bind the gadget
        if (bindGadgetWithRetry(logCallback)) {
            runRootCommands(listOf("setprop sys.usb.state $sysUsbState")) {}

            if (adbAvailable) {
                logCallback("[Gadget] Composite gadget active: $uacDisplayName + ADB")
            } else {
                logCallback("[Gadget] $uacDisplayName gadget active")
                if (keepAdbEffective && adbWasActive && !adbAvailable) {
                    logCallback("[Gadget] Note: ADB will reconnect when you disable the gadget.")
                }
            }

            return true
        } else {
            logCallback("[Gadget] Failed to bind UDC after retries.")
            return false
        }
    }
    private fun getAvailableUdcControllers(): List<String> {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "ls /sys/class/udc"))
            val output = p.inputStream.bufferedReader().readText().trim()
            if (output.isEmpty()) emptyList() else output.lines().filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getPreferredUdcController(): String? {
        val controllers = getAvailableUdcControllers()
        if (controllers.isEmpty()) return null

        // Prefer non-dummy controllers (e.g. musb-hdrc, dwc3, etc.)
        val realController = controllers.firstOrNull { !it.contains("dummy", ignoreCase = true) }
        return realController ?: controllers.first()
    }

    private fun bindGadgetWithRetry(logCallback: (String) -> Unit): Boolean {
        // MTK Specific: Force device mode
        val udcName = getPreferredUdcController()
        if (udcName != null) {
            configureMtkMode(udcName, true, logCallback)
        }
        for (i in 1..5) {
             try {
                 val effectiveUdc = getPreferredUdcController() ?: udcName
                 if (effectiveUdc == null) {
                     logCallback("[Gadget] Error: No UDC controller found.")
                     return false
                 }

                 logCallback("[Gadget] Binding to $effectiveUdc (Attempt $i)...")
                 if (bindWithUdcName(effectiveUdc, logCallback)) {
                     return true
                 }
                 Thread.sleep(800)

             } catch (e: Exception) {
                 logCallback("[Gadget] Exception during bind: ${e.message}")
             }
        }
        return false
    }

    /**
     * Bind the gadget UDC using the sequence proven to work during enable:
     * chmod, clear UDC, write, wait, verify readback. Captures and logs the
     * kernel's stderr so a refused write is visible instead of silent.
     */
    private fun bindWithUdcName(udcName: String, logCallback: (String) -> Unit): Boolean {
        try {
            // Ensure UDC is writable and clear
            Runtime.getRuntime().exec(arrayOf("su", "-c", "chmod 666 $GADGET_ROOT/UDC")).waitFor()
            Runtime.getRuntime().exec(arrayOf("su", "-c", "echo '' > $GADGET_ROOT/UDC")).waitFor()
            Thread.sleep(200)

            val writeOutput = runRootCommandGetOutput("echo \"$udcName\" > $GADGET_ROOT/UDC 2>&1; cat $GADGET_ROOT/UDC")
            logCallback("[Gadget] UDC write result: '$writeOutput'")
            val currentUdc = writeOutput.lines().lastOrNull()?.trim() ?: ""
            if (currentUdc == udcName) {
                Thread.sleep(300)
                if (getUdcContent() == udcName) {
                    return true
                }
            }
            logCallback("[Gadget] Bind to $udcName did not stick. UDC='$currentUdc'")
            Thread.sleep(300)
        } catch (e: Exception) {
            logCallback("[Gadget] Exception during bind: ${e.message}")
        }
        return false
    }

    suspend fun disableGadget(logCallback: (String) -> Unit, settingsRepo: SettingsRepository? = null) = withContext(Dispatchers.IO) {
        gadgetMutex.withLock {
            disableGadgetInternal(logCallback, settingsRepo)
        }
    }

    private suspend fun disableGadgetInternal(logCallback: (String) -> Unit, settingsRepo: SettingsRepository? = null) {
        logCallback("[Gadget] Disabling USB gadget...")
        if (!resumeGadgetHalMonitor(settingsRepo, logCallback)) return

        // Restore strings if available
        var restored = false
        if (settingsRepo != null) {
            val (origMan, origProd, origSerial) = settingsRepo.getOriginalIdentity()
            if (origMan != null && origProd != null && origSerial != null) {
                runRootCommands(listOf(
                    "echo \"$origMan\" > $GADGET_ROOT/strings/0x409/manufacturer",
                    "echo \"$origProd\" > $GADGET_ROOT/strings/0x409/product",
                    "echo \"$origSerial\" > $GADGET_ROOT/strings/0x409/serialnumber"
                )) { }
                logCallback("[Gadget] Restored original identity.")
                settingsRepo.clearOriginalIdentity()
                restored = true
            }
        }

        if (!restored) {
            runRootCommands(listOf(
                "echo \"\" > $GADGET_ROOT/strings/0x409/manufacturer",
                "echo \"\" > $GADGET_ROOT/strings/0x409/product",
                "echo \"\" > $GADGET_ROOT/strings/0x409/serialnumber"
            )) {}
        }

        // Remove OUR function links BEFORE the framework rebuild (left attached,
        // they left a broken composite that killed USB).
        runRootCommands(listOf(
            "rm -f $GADGET_ROOT/configs/b.1/f1 || true",
            "rm -f $GADGET_ROOT/configs/b.1/f2 || true",
            "rmdir $GADGET_ROOT/functions/uac1.0 2>/dev/null || true",
            "rmdir $GADGET_ROOT/functions/uac2.0 2>/dev/null || true",
            "mkdir -p $GADGET_ROOT/os_desc",
            "test -e $GADGET_ROOT/os_desc/b.1 || ln -sfn $GADGET_ROOT/configs/b.1 $GADGET_ROOT/os_desc/b.1"
        ), logCallback)

        // MTK Specific: Reset device mode
        try {
            val udcName = getPreferredUdcController()
            if (udcName != null) {
                configureMtkMode(udcName, false, logCallback)
            }
        } catch (e: Exception) {
            // Ignore
        }

        // Framework still owns the gadget: push a different settable function to force a
        // teardown/rebuild, then return to the original function set.
        val (savedSys, _) = settingsRepo?.getOriginalUsbConfig() ?: Pair(null, null)
        val originalFn = getSettableFunctionFromConfig(savedSys)
        val placeholder = if (originalFn == "mtp") "ptp" else (originalFn ?: "mtp")
        val finalFn = originalFn ?: ""

        // Restart adbd BEFORE the rebuild so its ffs monitor can rebind ffs.adb
        // (Samsung HAL re-adds Adb on every function change).
        runRootCommands(listOf("setprop ctl.start adbd"), logCallback)
        logCallback("[Gadget] Restoring original USB configuration...")
        runRootCommands(listOf("svc usb setFunctions $placeholder"), logCallback)
        if (!waitForUdcBound(8000)) {
            logCallback("[Gadget] WARNING: USB did not rebind during restore.")
        }
        val resetCommand = if (finalFn.isEmpty()) "svc usb setFunctions ''" else "svc usb setFunctions $finalFn"
        runRootCommands(listOf(resetCommand), logCallback)

        // Re-sync sys.usb.config: on QTI the '' reset pulls up adb but leaves the
        // property "none", which would make isAdbCurrentlyActive() false next time.
        val syncConfig = if (savedSys.isNullOrBlank()) {
            runRootCommandGetOutput("getprop persist.sys.usb.config").ifBlank { "adb" }
        } else {
            savedSys
        }
        runRootCommands(listOf("setprop sys.usb.config $syncConfig"), logCallback)

        // Restore attrs the framework won't reset (bcd, os_desc, config string).
        val savedGadget = settingsRepo?.getOriginalGadgetState()
        val attrCommands = mutableListOf<String>()
        savedGadget?.ids?.lineSequence()?.forEach { line ->
            when {
                line.startsWith("bcd=") -> line.removePrefix("bcd=").trim().let {
                    if (it.isNotEmpty()) attrCommands.add("echo $it > $GADGET_ROOT/bcdDevice")
                }
                line.startsWith("bcdusb=") -> line.removePrefix("bcdusb=").trim().let {
                    if (it.isNotEmpty()) attrCommands.add("echo $it > $GADGET_ROOT/bcdUSB")
                }
            }
        }
        savedGadget?.osDesc?.lineSequence()?.forEach { line ->
            when {
                line.startsWith("use=") -> line.removePrefix("use=").trim().let {
                    if (it.isNotEmpty()) attrCommands.add("echo $it > $GADGET_ROOT/os_desc/use")
                }
                line.startsWith("code=") -> line.removePrefix("code=").trim().let {
                    if (it.isNotEmpty()) attrCommands.add("echo $it > $GADGET_ROOT/os_desc/b_vendor_code")
                }
                line.startsWith("qw=") -> line.removePrefix("qw=").trim().let {
                    if (it.isNotEmpty()) attrCommands.add("echo $it > $GADGET_ROOT/os_desc/qw_sign")
                }
            }
        }
        if (!savedGadget?.configStr.isNullOrBlank()) {
            attrCommands.add("mkdir -p $GADGET_ROOT/configs/b.1/strings/0x409")
            attrCommands.add("echo \"${savedGadget?.configStr}\" > $GADGET_ROOT/configs/b.1/strings/0x409/configuration")
        }
        if (attrCommands.isNotEmpty()) {
            runRootCommands(attrCommands, logCallback)
        }

        // Verify restore: UDC bound and our UAC function gone.
        var verified = false
        var restoredLinks = ""
        var restoredUdc = ""
        for (i in 1..20) {
            Thread.sleep(300)
            restoredLinks = runRootCommandGetOutput(
                "ls -l $GADGET_ROOT/configs/b.1/ 2>/dev/null | sed -n 's/.* \\([^ /]*\\) -> \\(.*\\)/\\1=\\2/p'"
            )
            restoredUdc = getUdcContent()
            if (restoredUdc.isNotEmpty() && restoredUdc != "none" &&
                restoredLinks.isNotBlank() && !restoredLinks.contains("uac")) {
                verified = true
                break
            }
        }

        if (verified) {
            logCallback("[Gadget] Verified restore: links present, UDC bound to $restoredUdc.")
            settingsRepo?.clearOriginalGadgetState()
            settingsRepo?.clearOriginalUsbConfig()
        } else {
            logCallback("[Gadget] WARNING: could not restore USB configuration.")
            logCallback("[Gadget] Fallback: asking system to restore USB config.")
            restoreUsbConfig(settingsRepo, logCallback)
            for (i in 1..12) {
                Thread.sleep(500)
                val cur = getUdcContent()
                if (cur.isNotEmpty() && cur != "none") {
                    verified = true
                    logCallback("[Gadget] System rebind OK: UDC='$cur'.")
                    break
                }
            }
        }

        if (!verified) {
            logCallback("[Gadget] WARNING: could not restore USB gadget.")
        }
        logCallback("[Gadget] Gadget disabled. USB restored to system control.")
    }

    private fun waitForUdcBound(timeoutMs: Int): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(250)
            val udc = getUdcContent()
            if (udc.isNotEmpty() && udc != "none") return true
        }
        return false
    }

    suspend fun applySeLinuxPolicy(logCallback: (String) -> Unit) {
        val appDomain = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            "untrusted_app_all"
        } else {
            "untrusted_app"
        }
        val rules = listOf(
            "typeattribute audio_device mlstrustedobject",
            "allow $appDomain audio_device chr_file { read write open ioctl getattr map }",
            "allow $appDomain audio_device dir { search getattr read open }",
            "allow $appDomain cgroup dir { search getattr read open }"
        )

        val tmpPath = "/data/local/tmp/uac2_policy.te"
        val policyBlob = rules.joinToString("\n")

        val rootSolution = getRootSolution()
        logCallback("[Gadget] Detected Root Solution: $rootSolution")

        var policyBin: String? = null
        var useKsud = false

        when (rootSolution) {
            "KernelSU" -> {
                // KernelSU usually puts ksud in /data/adb/ksu/bin/ksud
                // It might also be in PATH, but full path is safer
                val ksuPath = "/data/adb/ksu/bin/ksud"
                if (runRootCommand("test -f $ksuPath", {})) {
                    policyBin = ksuPath
                    useKsud = true
                } else {
                    // Try just "ksud" if full path missing
                    policyBin = "ksud"
                    useKsud = true
                }
            }
            "APatch" -> {
                val apPath = "/data/adb/ap/bin/magiskpolicy"
                if (runRootCommand("test -f $apPath", {})) {
                    policyBin = apPath
                }
            }
            "Magisk", "Unknown" -> {
                // Search for magiskpolicy
                val magiskPolicyPaths = listOf(
                    "/data/adb/magisk/magiskpolicy",
                    "/data/adb/magisk/supolicy",
                    "/sbin/magiskpolicy",
                    "/system/bin/magiskpolicy",
                    "/sbin/supolicy"
                )

                for (path in magiskPolicyPaths) {
                    if (runRootCommand("test -f $path", {})) {
                        policyBin = path
                        break
                    }
                }

                // Fallback: check Magisk tmpfs
                if (policyBin == null) {
                    val magiskTmpResult = StringBuilder()
                    if (runRootCommand("magisk --path 2>/dev/null", { msg -> magiskTmpResult.append(msg) })) {
                        val magiskTmp = magiskTmpResult.toString().trim()
                        if (magiskTmp.isNotEmpty()) {
                            val tmpfsPolicyPath = "$magiskTmp/magiskpolicy"
                            if (runRootCommand("test -f $tmpfsPolicyPath", {})) {
                                policyBin = tmpfsPolicyPath
                            }
                        }
                    }
                }
            }
        }

        // Try applying policy if we found a tool
        if (policyBin != null) {
            try {
                // Write rules to temp file
                val writeCmd = "echo '$policyBlob' > $tmpPath"
                if (runRootCommand(writeCmd, {})) {
                    val applyCmd = if (useKsud) {
                        "$policyBin sepolicy apply $tmpPath"
                    } else {
                        "$policyBin --live --apply $tmpPath"
                    }

                    if (runRootCommand(applyCmd, { msg ->
                        if (msg.contains("error", ignoreCase = true)) logCallback("[Policy] $msg")
                    })) {
                        logCallback("[Gadget] SELinux rules applied using $policyBin")
                        runRootCommand("rm -f $tmpPath", {})
                        return
                    } else {
                        logCallback("[Gadget] Policy apply failed with $policyBin")
                    }
                }
            } catch (e: Exception) {
                logCallback("[Gadget] Policy apply exception: ${e.message}")
            }
        }

        // 3. Fallback: Try generic supolicy command (older SuperSU or simple PATH access)
        if (runRootCommand("which supolicy >/dev/null 2>&1", {})) {
            logCallback("[Gadget] Trying legacy supolicy...")
            var success = true
            for (rule in rules) {
                if (!runRootCommand("supolicy --live '$rule'", { msg ->
                    if (msg.contains("error", ignoreCase = true)) logCallback(msg)
                })) {
                    success = false
                }
            }
            if (success) {
                logCallback("[Gadget] SELinux rules applied via supolicy (SuperSU)")
                runRootCommand("rm -f $tmpPath", {})
                return
            }
        }

        // Cleanup temp file if it exists
        runRootCommand("rm -f $tmpPath", {})
        logCallback("[Gadget] Warning: No SELinux policy tool succeeded.")
        logCallback("[Gadget] Audio device access may fail without proper SELinux rules")
    }

    suspend fun findAndPrepareCard(logCallback: (String) -> Unit): Int = withContext(Dispatchers.IO) {
        logCallback("[Gadget] Scanning for USB audio gadget card...")

        var cardIndex = -1

        try {
            val checkCmd = "cat /proc/asound/cards | grep -iE 'UAC[12][[:space:]_-]*Gadget|UAC[12]Gadget' | head -n1 | awk '{print \$1}'"
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", checkCmd))
            val output = process.inputStream.bufferedReader().readText().trim()

            if (output.isNotEmpty()) {
                cardIndex = output.filter { it.isDigit() }.toIntOrNull() ?: -1
            }
        } catch (e: Exception) {
            logCallback("[Gadget] Error scanning cards: ${e.message}")
        }

        if (cardIndex != -1) {
            val devPath = "/dev/snd/pcmC${cardIndex}D0c"
            if (runRootCommand("test -e $devPath", {})) {
                 // Retry chmod in case of race conditions
                 for (i in 1..3) {
                     runRootCommand("chmod 666 /dev/snd/pcmC${cardIndex}D0c", {})
                     runRootCommand("chmod 666 /dev/snd/pcmC${cardIndex}D0p", {})
                     Thread.sleep(100)
                 }
                 logCallback("[Gadget] USB audio gadget driver found at card $cardIndex")
                 return@withContext cardIndex
            } else {
                logCallback("[Gadget] Card $cardIndex found, but pcmC${cardIndex}D0c is missing.")
            }
        } else {
             logCallback("[Gadget] USB audio gadget card not found. Is USB connected?")
        }

        return@withContext -1
    }

    fun runRootCommand(cmd: String, logCallback: (String) -> Unit): Boolean {
        return runRootCommands(listOf(cmd), logCallback)
    }

    fun isGadgetActive(): Boolean {
        return try {
            val cmd = "test -L $GADGET_ROOT/configs/b.1/f1 && readlink $GADGET_ROOT/configs/b.1/f1 | grep -Eq 'uac1|uac2' && udc=\$(cat $GADGET_ROOT/UDC 2>/dev/null) && [ -n \"\$udc\" ] && [ \"\$udc\" != \"none\" ]"
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            p.waitFor() == 0
        } catch (e: Exception) { false }
    }

    fun checkStatus(): String {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $GADGET_ROOT/UDC"))
        return process.inputStream.bufferedReader().readText().trim()
    }

    /**
     * Get comprehensive gadget status including UDC controller and active functions.
     * This should be called from a background thread.
     */
    fun getGadgetStatus(): GadgetStatus {
        return try {
            // Get UDC controller
            val udcProcess = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $GADGET_ROOT/UDC"))
            val udcController = udcProcess.inputStream.bufferedReader().readText().trim()
            val isBound = udcController.isNotEmpty() && udcController != "none"

            // Get active functions by reading symlink targets in configs/b.1/
            val functionsProcess = Runtime.getRuntime().exec(arrayOf(
                "su", "-c",
                "for f in $GADGET_ROOT/configs/b.1/f*; do [ -L \"\$f\" ] && basename \$(readlink \"\$f\"); done 2>/dev/null"
            ))
            val functionsOutput = functionsProcess.inputStream.bufferedReader().readText().trim()
            val activeFunctions = if (functionsOutput.isNotEmpty()) {
                functionsOutput.lines()
                    .filter { it.isNotBlank() }
                    .map { it.trim() }
                    .map {
                        // Clean up function names (e.g., "uac2.0" -> "uac2", "ffs.adb" -> "adb")
                        when {
                            it.startsWith("uac1") -> "uac1"
                            it.startsWith("uac2") -> "uac2"
                            it.startsWith("ffs.") -> it.removePrefix("ffs.")
                            else -> it
                        }
                    }
            } else {
                emptyList()
            }

            GadgetStatus(
                udcController = if (isBound) udcController else "--",
                activeFunctions = activeFunctions,
                isBound = isBound
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting gadget status: ${e.message}")
            GadgetStatus(
                udcController = "--",
                activeFunctions = emptyList(),
                isBound = false
            )
        }
    }

    fun runRootCommands(commands: List<String>, logCallback: (String) -> Unit): Boolean {
        var success = true
        try {
            val p = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(p.outputStream)

            for (cmd in commands) {
                os.writeBytes("$cmd\n")
                Log.d(TAG, "Executing: ${cmd.take(100)}...")
            }
            os.writeBytes("exit\n")
            os.flush()

            val errReader = Thread {
                try {
                    val reader = p.errorStream.bufferedReader()
                    var errLine: String?
                    while (reader.readLine().also { errLine = it } != null) {
                        val errorLine = errLine ?: continue
                        val isBenign = errorLine.contains("No such file") ||
                                       errorLine.contains("Read-only") ||
                                       errorLine.contains("File exists") ||
                                       errorLine.contains("Directory not empty")
                        if (!isBenign) {
                            logCallback("[Shell Err] $errorLine")
                        }
                    }
                } catch (e: Exception) {
                    // Ignore read errors
                }
            }
            errReader.start()

            val exitCode = p.waitFor()
            errReader.join(1000)

            if (exitCode != 0) {
                 Log.w(TAG, "Command batch returned non-zero: $exitCode")
                 success = false
            }

        } catch (e: Exception) {
            logCallback("[Gadget] Command failed: ${e.message}")
            success = false
        }
        return success
    }
}
