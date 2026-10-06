package com.andrerinas.openheadunit.utils

/**
 * Which Android display the projection should use.
 *
 * A display the user picked can be unplugged between sessions, so every answer falls back to the
 * built-in one rather than failing: a missing panel must never cost a connection.
 */
object DisplayTargetPolicy {

    /** `Display.DEFAULT_DISPLAY`, repeated so this object needs no Android import. */
    const val DEFAULT_DISPLAY_ID = 0

    /** The extra a BAIC/Qinggan launcher puts on the intent that embeds this app: PIP, TAPLO or MAIN. */
    const val EXTRA_EMBED_SURFACE = "com.qinggan.androidauto.extra.BAIC_SURFACE"

    /**
     * Whether an activity is running inside another app's view (an ActivityView or TaskView, the way
     * a launcher fills its PiP slots) rather than on a display of its own.
     *
     * The launcher says so with [EXTRA_EMBED_SURFACE]; without it, a non-default display that is not
     * a presentation display is one: HDMI and other real panels carry the presentation flag, the
     * virtual display behind an embedding view does not.
     */
    fun isEmbeddedHost(surfaceHint: String?, displayId: Int, isPresentation: Boolean): Boolean {
        when (surfaceHint?.trim()?.uppercase()) {
            "PIP", "TAPLO" -> return displayId != DEFAULT_DISPLAY_ID
            "MAIN" -> return false
        }
        return displayId != DEFAULT_DISPLAY_ID && !isPresentation
    }

    /** Stored as the second screen's display when it should take whichever presentation display is first. */
    const val AUX_DISPLAY_AUTO = -1

    /** Stored in [Settings.preferredDisplayMode] by ordinal, so the order is load-bearing. */
    enum class Mode {
        /** The built-in panel, which is what every unit did before this setting existed. */
        DEFAULT,

        /** The display the user picked, by id. */
        SECONDARY,

        /** Whichever external display is attached, without pinning an id. */
        AUTO;

        companion object {
            fun of(stored: Int): Mode = values().getOrElse(stored) { DEFAULT }
        }
    }

    /** One display as [DisplayTargets] reads it, flattened so the rules stay testable. */
    data class DisplayInfo(
        val displayId: Int,
        val name: String,
        val widthPx: Int,
        val heightPx: Int,
        val densityDpi: Int,
        val isPresentation: Boolean,
        val isUsable: Boolean,
    )

    /** The chosen display and the rung that answered, which is what the log line prints. */
    data class Choice(val displayId: Int, val reason: String) {
        val isDefault: Boolean get() = displayId == DEFAULT_DISPLAY_ID
    }

    /**
     * The displays that can host a projection: usable, and not the built-in panel.
     *
     * Presentation-flagged displays sort first because that flag is Android's own statement that a
     * display is a separate surface rather than a mirror of the main one.
     */
    fun candidates(displays: List<DisplayInfo>): List<DisplayInfo> =
        displays
            .filter { it.isUsable && it.displayId != DEFAULT_DISPLAY_ID }
            .sortedWith(compareByDescending<DisplayInfo> { it.isPresentation }.thenBy { it.displayId })

    /**
     * Which display to project on, never throwing and never leaving the caller without an answer.
     *
     * A display the launcher embeds this app on comes first, whatever the setting says: the person
     * put the app in that slot, and a projection opening anywhere else would leave it empty.
     */
    fun choose(
        mode: Mode,
        preferredDisplayId: Int,
        displays: List<DisplayInfo>,
        embeddedDisplayId: Int? = null,
    ): Choice {
        if (embeddedDisplayId != null) {
            displays.firstOrNull { it.displayId == embeddedDisplayId && it.isUsable }?.let {
                return Choice(it.displayId, "the launcher embeds the app on ${it.name}")
            }
        }
        if (mode == Mode.DEFAULT) return Choice(DEFAULT_DISPLAY_ID, "the built-in display was chosen")
        val candidates = candidates(displays)
        if (candidates.isEmpty()) {
            return Choice(DEFAULT_DISPLAY_ID, "no external display is attached")
        }
        if (mode == Mode.SECONDARY) {
            val pinned = candidates.firstOrNull { it.displayId == preferredDisplayId }
            if (pinned != null) return Choice(pinned.displayId, "the chosen display ${pinned.name} is attached")
            return Choice(
                candidates.first().displayId,
                "the chosen display $preferredDisplayId is gone, using ${candidates.first().name}",
            )
        }
        return Choice(candidates.first().displayId, "the attached display ${candidates.first().name}")
    }

    /** Whether a live session has to give the picture back to the built-in panel. */
    fun lostTargetDisplay(activeDisplayId: Int, displays: List<DisplayInfo>): Boolean =
        activeDisplayId != DEFAULT_DISPLAY_ID &&
            displays.none { it.displayId == activeDisplayId && it.isUsable }

    /**
     * Which display carries the second screen, or null when there is nowhere to put it.
     *
     * The saved display while it is attached; otherwise the first presentation display Android
     * reports, the way `DisplayManager.getDisplays(DISPLAY_CATEGORY_PRESENTATION)[0]` would answer.
     * Display ids are handed out again on every attach, so a saved id going stale after a restart is
     * the normal case, not a fault. Never the display the projection itself is on.
     */
    fun chooseAux(savedDisplayId: Int, projectionDisplayId: Int, displays: List<DisplayInfo>): Choice? {
        val usable = displays.filter { it.isUsable && it.displayId != projectionDisplayId }
        if (savedDisplayId != AUX_DISPLAY_AUTO) {
            usable.firstOrNull { it.displayId == savedDisplayId }?.let {
                return Choice(it.displayId, "the chosen display ${it.name} is attached")
            }
        }
        val first = usable.firstOrNull { it.isPresentation } ?: return null
        val why = if (savedDisplayId == AUX_DISPLAY_AUTO) "it is the first presentation display"
        else "the chosen display $savedDisplayId is gone, and it is the first presentation display"
        return Choice(first.displayId, "${first.name} because $why")
    }
}
