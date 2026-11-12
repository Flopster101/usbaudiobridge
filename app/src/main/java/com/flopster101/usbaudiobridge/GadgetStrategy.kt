package com.flopster101.usbaudiobridge

enum class GadgetEnvironment(val label: String) {
    SAMSUNG_LIBPIXELUSB("LineageOS/AOSP gadget HAL (libpixelusb)"),
    SAMSUNG_STOCK_INITRC("Samsung stock USB HAL (legacy UsbService + init.rc)"),
    QUALCOMM_HAL("Qualcomm gadget HAL"),
    MTK_CONFIGFS("MediaTek configfs"),
    AOSP_GENERIC("AOSP generic gadget HAL"),
    UNKNOWN("Unknown")
}

// Declared capabilities + rationale for a detected environment
data class GadgetStrategy(
    val environment: GadgetEnvironment,
    val supportsKeepAdb: Boolean,
    val keepAdbReason: String
) {
    val label: String get() = environment.label

    companion object {
        fun forEnvironment(env: GadgetEnvironment): GadgetStrategy = when (env) {
            GadgetEnvironment.SAMSUNG_LIBPIXELUSB -> GadgetStrategy(
                environment = env,
                supportsKeepAdb = true,
                keepAdbReason = "The libpixelusb FFS monitor is paused while the custom gadget is active, " +
                    "allowing ADB to remain linked alongside USB audio."
            )
            GadgetEnvironment.SAMSUNG_STOCK_INITRC -> GadgetStrategy(
                environment = env,
                supportsKeepAdb = false,
                keepAdbReason = "The stock Samsung framework re-provisions the full function set (adb included) " +
                    "through sys.usb.config on every USB change, so a keep-adb composite does not survive. " +
                    "ADB cannot be preserved here."
            )
            GadgetEnvironment.QUALCOMM_HAL -> GadgetStrategy(
                environment = env,
                supportsKeepAdb = true,
                keepAdbReason = "Qualcomm's gadget HAL only mounts ADB when requested, so it can be preserved."
            )
            GadgetEnvironment.MTK_CONFIGFS -> GadgetStrategy(
                environment = env,
                supportsKeepAdb = false,
                keepAdbReason = "MediaTek configfs behavior is unverified; keeping ADB is not guaranteed."
            )
            GadgetEnvironment.AOSP_GENERIC -> GadgetStrategy(
                environment = env,
                supportsKeepAdb = true,
                keepAdbReason = "AOSP-style HAL only mounts ADB when requested, so it can be preserved."
            )
            GadgetEnvironment.UNKNOWN -> GadgetStrategy(
                environment = env,
                supportsKeepAdb = false,
                keepAdbReason = "USB environment could not be identified; keeping ADB is not guaranteed."
            )
        }
    }
}
