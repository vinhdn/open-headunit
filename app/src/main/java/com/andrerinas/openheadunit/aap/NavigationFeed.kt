package com.andrerinas.openheadunit.aap

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The turn-by-turn guidance Android Auto is sending right now, for readers inside this app (the
 * taplo link). The same values go out in NavigationUpdateIntent; this is the in-process copy.
 * Null while no route is being guided.
 */
object NavigationFeed {

    data class Guidance(
        /** The road the next manoeuvre leads onto, or the current one when that is all there is. */
        val road: String,
        /** The manoeuvre in words, in the head unit's language ("Turn right"). */
        val action: String,
        /** Legacy NextTurnDetail.NextEvent value, always set: derived from the newer messages too. */
        val event: Int,
        /** NextTurnDetail.Side: 1 left, 2 right, 3 unspecified; null when not known. */
        val side: Int?,
        val roundaboutExit: Int?,
        val distanceMeters: Int?,
        val timeSeconds: Int?,
        val totalDistanceMeters: Int?,
        val totalTimeSeconds: Long?,
        /** As the phone formats it, e.g. "16:07". */
        val estimatedArrival: String?,
    )

    private val _current = MutableStateFlow<Guidance?>(null)
    val current: StateFlow<Guidance?> = _current

    fun publish(guidance: Guidance) {
        _current.value = guidance
    }

    fun clear() {
        _current.value = null
    }
}
