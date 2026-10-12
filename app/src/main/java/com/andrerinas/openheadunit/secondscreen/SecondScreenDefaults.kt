package com.andrerinas.openheadunit.secondscreen

import com.andrerinas.openheadunit.BuildConfig

/**
 * What the second screen settings are before anyone changes them, set per flavor: off for the
 * ordinary builds, ready for the cluster on x7. A stored setting always wins over these.
 */
object SecondScreenDefaults {
    val enabled: Boolean get() = BuildConfig.AUX_DEFAULT_ENABLED
    val output: String get() = BuildConfig.AUX_DEFAULT_OUTPUT
    val picture: String get() = BuildConfig.AUX_DEFAULT_PICTURE
    val densityDpi: Int get() = BuildConfig.AUX_DEFAULT_DENSITY_DPI
    val rightPercent: Int get() = BuildConfig.AUX_DEFAULT_RIGHT_PERCENT

    /** The flavor's cover, or null to go by the output (see Settings.auxInsetTopPercent). */
    val insetTopPercent: Int? get() = BuildConfig.AUX_DEFAULT_INSET_TOP_PERCENT.takeIf { it >= 0 }
    val insetBottomPercent: Int? get() = BuildConfig.AUX_DEFAULT_INSET_BOTTOM_PERCENT.takeIf { it >= 0 }
}
