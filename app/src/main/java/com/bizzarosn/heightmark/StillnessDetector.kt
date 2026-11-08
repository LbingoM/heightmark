package com.bizzarosn.heightmark

import android.location.Location

/**
 * Decides from a stream of GNSS fixes whether the device has been stationary
 * long enough to switch the GPS radio off.
 *
 * Stationary means: every fix in the last [windowMs] reports a speed below
 * [maxSpeedMps] and stays within [maxDriftMeters] of the window's first fix.
 * GNSS jitter makes exact-position checks useless, hence the drift allowance.
 */
class StillnessDetector(
    private val windowMs: Long = 30_000L,
    private val maxSpeedMps: Float = 0.5f,
    private val maxDriftMeters: Float = 15f
) {
    private val window = ArrayDeque<Location>()

    /**
     * The largest distance from the window's anchor seen so far, held
     * incrementally. The answer only has to be exact once the window is old
     * enough to decide, and re-walking the window on every fix cost up to 60
     * geodesic solves a second for one boolean.
     */
    private var maxDriftFromAnchor = 0f

    /**
     * Feeds the next fix. Returns true once the device has been still for the
     * full window; the caller is expected to stop feeding after that.
     */
    fun feed(location: Location): Boolean {
        if (location.hasSpeed() && location.speed > maxSpeedMps) {
            clear()
            return false
        }
        window.addLast(location)

        // Bound memory if the caller keeps feeding while already stationary
        var anchorChanged = false
        while (ageMs(window.first(), location) > 2 * windowMs) {
            window.removeFirst()
            anchorChanged = true
        }
        if (anchorChanged) {
            // Every recorded distance was measured from the old anchor
            recomputeMaxDrift()
        } else {
            val drift = window.first().distanceTo(location)
            if (drift > maxDriftFromAnchor) maxDriftFromAnchor = drift
        }

        val anchor = window.first()
        if (ageMs(anchor, location) < windowMs) return false

        if (maxDriftFromAnchor > maxDriftMeters) {
            // Slow drift (e.g. walking pace below the speed threshold): restart
            clear()
            return false
        }
        return true
    }

    fun reset() {
        clear()
    }

    private fun clear() {
        window.clear()
        maxDriftFromAnchor = 0f
    }

    private fun recomputeMaxDrift() {
        val anchor = window.firstOrNull()
        maxDriftFromAnchor = if (anchor == null) 0f else window.maxOf { anchor.distanceTo(it) }
    }

    private fun ageMs(older: Location, newer: Location): Long {
        return (newer.elapsedRealtimeNanos - older.elapsedRealtimeNanos) / 1_000_000
    }
}
