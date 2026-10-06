package com.andrerinas.openheadunit.utils

/**
 * Tells Android Auto day or night, as chosen in Settings. Nothing changes it but that choice,
 * so there is nothing to watch: the service asks for a resend when the setting is saved and
 * when a new session starts.
 */
class NightModeManager(
    private val settings: Settings,
    private val onUpdate: (Boolean) -> Unit
) {

    private var lastEmittedValue: Boolean? = null

    fun start() {
        lastEmittedValue = null
        update()
    }

    fun stop() {}

    /** Sends the current state even if it has not changed, e.g. to a new connection. */
    fun resendCurrentState() {
        lastEmittedValue = null
        update()
    }

    fun update() {
        val isNight = settings.nightMode == Settings.NightMode.NIGHT
        if (lastEmittedValue != isNight) {
            lastEmittedValue = isNight
            onUpdate(isNight)
        }
    }
}
