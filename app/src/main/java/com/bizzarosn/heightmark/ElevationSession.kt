package com.bizzarosn.heightmark

import android.location.Location
import javax.inject.Inject

/**
 * The tracking session's domain policy: which GNSS fixes are worth averaging,
 * and what the number on screen is currently worth.
 *
 * Owns the [ElevationService] window together with the flags that describe it
 * — first fix seen, duty-cycle idleness, the post-flush wait for a fresh fix,
 * the last value shown and the [ElevationDatum] it was measured against — so a
 * flush and the state explaining it can never drift apart.
 *
 * The number on screen is built from three parts:
 *  - The window: the current GPS session's fixes, from radio-on to radio-off.
 *  - The [pool]: every earlier session folded into one [HeightEstimate] by
 *    [SessionPool]. A phone's GNSS height carries a bias of about 5 m that
 *    lasts minutes, so each session settles somewhere new. Pooling sessions
 *    instead of replacing one with the next is what stops a still phone's
 *    reading from jumping by that bias at every wake.
 *  - The [odometer]: height carried up or down since tracking began, from
 *    the barometer, with weather left out. Window and pool hold heights
 *    minus [appliedMotionMeters] at the time, so a fix taken two floors down
 *    still measures the same thing as one taken here, and the screen follows
 *    a climb at once instead of waiting for GNSS to notice. On a device
 *    without a barometer the odometer stays at zero. The odometer's changes
 *    count only while [crossCheck] trusts it: in a pressurized cabin the
 *    barometer reads the cabin, not the aircraft, and the reading holds
 *    until GNSS returns.
 *
 * A session is folded into the pool when it ends (the radio restarts after a
 * wake, a long background gap or a block) and every [FOLD_INTERVAL_NANOS]
 * while it runs, since windows that far apart carry nearly independent errors.
 *
 * Following [DetailsPanelPresenter], the clock is an input rather than a
 * [android.os.SystemClock] call, so the whole policy stays assertable in a JVM
 * test. Nothing here touches a Context or a view: [ElevationTracker] keeps the
 * listener registration and the coroutines, and [ElevationFragment] the
 * rendering.
 *
 * Confined to the main thread — GNSS callbacks, barometer samples and the
 * geoid-conversion coroutine all land there — so none of the state is
 * synchronized.
 */
class ElevationSession @Inject constructor(
    private val window: ElevationService
) {

    /**
     * A fix admitted for geoid conversion. [epoch] pins it to the averaging
     * window it was admitted against, so [commit] can drop it if that window
     * was flushed while the conversion was in flight. A null
     * [verticalAccuracyMeters] means the fix reported none. [atNanos] is the
     * fix's elapsed-realtime timestamp.
     */
    data class PendingFix(val epoch: Int, val verticalAccuracyMeters: Float?, val atNanos: Long)

    /** True once any fix has been committed this session. */
    var hasFix: Boolean = false
        private set

    /**
     * True while the GPS radio is off for the stationary duty cycle. This is
     * the only record of idleness: [ElevationTracker] arms [IdleWakeMonitor]
     * exactly while it holds, and anything that would turn the radio on must
     * check it first.
     */
    var isIdle: Boolean = false
        private set

    /**
     * True once [ElevationTracker]'s fix-age watchdog has gone off: tracking
     * is still active but no fix has committed recently enough to trust the
     * displayed reading. Distinct from [awaitingFreshFix] — a watchdog expiry
     * does not discard the averaging window, since the outage is usually
     * brief (elevator lobby, parking garage) and the same reading is still
     * likely right once fixes resume.
     */
    var signalStale: Boolean = false
        private set

    /**
     * The elevation on screen and the datum it was measured against. It stays
     * on screen across a flush — dimmed by [ReadingState.Dormant] — until
     * fresh fixes land. Null until the first fix.
     */
    var displayedElevation: Elevation? = null
        private set

    /** Readings in the current session's window; the details panel shows it. */
    val readingCount: Int
        get() = currentSnapshot().readingCount

    /**
     * Cached summary of [window]. One repaint reads it through [readingCount],
     * [readingState], [verdict] and [frameHeight]; each read is an O(n) weighted
     * pass that allocates. The window only changes in [commit] and when it is
     * folded or flushed, and both drop this, so the cache never outlives them.
     */
    private var cachedSnapshot: ElevationService.Snapshot? = null

    private fun currentSnapshot(): ElevationService.Snapshot =
        cachedSnapshot ?: window.snapshot().also { cachedSnapshot = it }

    private val odometer = BarometricOdometer()
    private val crossCheck = BarometerCrossCheck()

    /**
     * The part of the odometer's height change that counts: the changes it
     * reported while [crossCheck] trusted it. Window and pool hold heights
     * minus this, and the screen adds it back.
     */
    private var appliedMotionMeters = 0.0

    /** True when the barometer is working and measures the device's height. */
    private val barometerUsable: Boolean
        get() = odometer.isTracking && crossCheck.isTrusted

    /** Earlier sessions at this height, pooled; null until the first one is folded. */
    private var pool: HeightEstimate? = null

    /** The datum the window and [pool] are measured on; null before the first fix. */
    private var datum: ElevationDatum? = null

    /** When the window's first fix was taken; null while it is empty. */
    private var windowStartNanos: Long? = null

    private var awaitingFreshFix = false
    private var epoch = 0

    /** True once a fix has been converted to Mean Sea Level this session. */
    private var hasSeaLevelFix = false

    /** Null until the first pause, so a pause timestamp of 0 is still a pause. */
    private var pausedAtElapsedMs: Long? = null

    /**
     * Admits a fix for conversion, or returns null if it must not reach the
     * average.
     */
    fun offer(location: Location): PendingFix? {
        // A fix without altitude would read as 0.0 and poison the average
        if (!location.hasAltitude()) return null
        // Skip fixes whose vertical error would drag the average around. A fix
        // that reports no vertical accuracy is kept: unknown is not the same as bad.
        val reported = location.verticalAccuracyOrNull()
        if (reported != null && reported > MAX_VERTICAL_ACCURACY_M) return null
        return PendingFix(epoch, reported, location.elapsedRealtimeNanos)
    }

    /**
     * Adds a converted fix to the average, unless its window was flushed while
     * it was converting or its datum does not belong there. Returns true when
     * the reading was applied and the screen needs a repaint.
     *
     * [accuracyMeters] weighs the reading in the average and feeds the settle
     * threshold; it defaults to the fix's pre-conversion vertical accuracy but
     * callers that resolved a tighter bound afterward — the MSL altitude
     * accuracy the geoid conversion can populate — should pass that instead,
     * since it is what actually bounds the committed elevation.
     */
    fun commit(
        pending: PendingFix,
        elevation: Elevation,
        accuracyMeters: Float? = pending.verticalAccuracyMeters
    ): Boolean {
        if (pending.epoch != epoch) return false
        if (!admits(elevation.datum)) return false
        // Geoid data that only becomes available mid-session shifts every
        // reading after it by the local separation. Averaging or pooling across
        // that boundary would blend two datums, and letting the jump detector
        // re-anchor on it would present the change of surface as a climb.
        if (datum != null && elevation.datum != datum) {
            pool = null
            flush()
            crossCheck.clearFixes()
        }
        datum = elevation.datum
        crossCheck.onFix(
            atNanos = pending.atNanos,
            gnssMeters = elevation.meters,
            sigmaMeters = (accuracyMeters ?: MAX_VERTICAL_ACCURACY_M).toDouble(),
            barometerMeters = odometer.motionMeters,
            standardAltitudeMeters = odometer.standardAltitudeMeters
        )
        val start = windowStartNanos
        if (start != null && pending.atNanos - start >= FOLD_INTERVAL_NANOS) fold()
        if (windowStartNanos == null) windowStartNanos = pending.atNanos
        window.addElevationReading(elevation.meters - appliedMotionMeters, accuracyMeters)
        cachedSnapshot = null
        hasFix = true
        if (elevation.datum == ElevationDatum.MEAN_SEA_LEVEL) hasSeaLevelFix = true
        awaitingFreshFix = false
        signalStale = false
        refreshDisplay()
        return true
    }

    /**
     * Feeds one barometer sample of [pressureHpa], taken at [atNanos] on the
     * elapsed-realtime clock. Returns true when the screen needs a repaint.
     *
     * The weather variance the odometer accrues while moving belongs to the
     * pool: it measures how far the pool's frame may have slipped, so the
     * next session is weighed against it more heavily. While [crossCheck]
     * distrusts the barometer, neither its height nor its variance counts.
     */
    fun onPressure(pressureHpa: Float, atNanos: Long): Boolean {
        val motionBefore = odometer.motionMeters
        val varianceBefore = odometer.varianceMeters2
        val moved = odometer.feed(pressureHpa, atNanos)
        if (!crossCheck.isTrusted) return false
        val added = odometer.varianceMeters2 - varianceBefore
        if (added > 0) pool = pool?.let { it.copy(variance = it.variance + added) }
        if (!moved) return false
        appliedMotionMeters += odometer.motionMeters - motionBefore
        val before = displayedElevation
        refreshDisplay()
        return displayedElevation != before
    }

    /**
     * Whether a reading on [datum] may reach the average.
     *
     * An ellipsoid height is what [AltitudeResolver] returns when a conversion
     * fails. Once sea level has been measured this session, such a reading is a
     * datum shift of tens of meters rather than a change in elevation, so it is
     * dropped: GNSS delivers a fix a second, and a dropped one costs a second
     * of freshness against a hero number silently off by the geoid separation.
     *
     * A device that cannot load geoid data at all never measures sea level, and
     * there a consistent ellipsoid window — named as one on screen — is the
     * best available answer.
     */
    private fun admits(datum: ElevationDatum): Boolean =
        datum == ElevationDatum.MEAN_SEA_LEVEL || !hasSeaLevelFix

    /** The GPS radio went off for the stationary duty cycle. */
    fun enterIdle() {
        isIdle = true
    }

    /**
     * The fix-age watchdog expired: tracking is active but no fix has
     * committed recently enough to trust the displayed reading. A no-op
     * before the first fix, where [ReadingState.Acquiring] already covers it,
     * and while idle, where the radio is off on purpose and a fix drought is
     * the expected condition rather than a lost signal.
     */
    fun onFixWatchdogExpired() {
        if (hasFix && !isIdle) signalStale = true
    }

    /**
     * [IdleWakeMonitor] fired with [trigger]. The session before it is folded
     * into the pool and a new one starts, so a wake moves the number only as
     * far as the new session's weight allows.
     *
     * With a usable barometer, the odometer has already followed any change
     * in height. Without one, a wake is the only sign the device may have
     * moved, so the pool's variance grows by [UNTRACKED_WAKE_VARIANCE_M2]; a
     * real move further than that is left for [SessionPool]'s gate to refute.
     * A [WakeTrigger.PRESSURE_CHANGE] without a usable barometer means the
     * height changed with nothing to measure by how much, so the pool goes.
     */
    fun wake(trigger: WakeTrigger) {
        isIdle = false
        if (trigger == WakeTrigger.PRESSURE_CHANGE && !barometerUsable) {
            pool = null
            flush()
            return
        }
        startNewSession()
    }

    /**
     * Tracking was blocked (location services off, permission lost). An idle
     * session leaves idle here: nothing watches for motion during the outage,
     * so the frozen reading may be wrong once fixes return, and "resting"
     * would be the wrong thing to say about it. The pool is kept, as for a
     * wake.
     */
    fun onBlocked() {
        if (!isIdle) return
        isIdle = false
        startNewSession()
    }

    /** Screen backgrounded at [nowElapsedRealtimeMs]; the duty cycle ends with it. */
    fun onPaused(nowElapsedRealtimeMs: Long) {
        pausedAtElapsedMs = nowElapsedRealtimeMs
        isIdle = false
    }

    /**
     * Screen foregrounded at [nowElapsedRealtimeMs]. A long gap away from the
     * app can mean a whole new elevation, so the radio's restart begins a new
     * session, weighed against the pool like any other. The odometer measures
     * the gap from the pressure change across it. The same gap re-opens the
     * datum question: [hasSeaLevelFix] only clears here, not in [wake]'s
     * frequent duty-cycle restarts, so a permanently broken geoid conversion
     * can still recover into the labeled ellipsoid mode instead of dropping
     * fixes forever.
     */
    fun onResumed(nowElapsedRealtimeMs: Long) {
        val pausedAt = pausedAtElapsedMs ?: return
        if (nowElapsedRealtimeMs - pausedAt > RESET_AFTER_GAP_MS) {
            startNewSession()
            hasSeaLevelFix = false
        }
    }

    /** What the current reading is worth. */
    fun readingState(): ReadingState = ReadingState.derive(
        hasFixEver = hasFix,
        isIdle = isIdle,
        awaitingFreshFix = awaitingFreshFix,
        signalStale = signalStale,
        pooled = pool != null && verdict() !is SessionPool.Verdict.Refuted,
        snapshot = currentSnapshot()
    )

    /**
     * Ends the current session: its window is folded into the pool, fixes
     * converted before this point are dropped, and the reading is dormant
     * until the next one.
     */
    private fun startNewSession() {
        fold()
        if (!barometerUsable) {
            pool = pool?.let { it.copy(variance = it.variance + UNTRACKED_WAKE_VARIANCE_M2) }
        }
        epoch++
        if (hasFix) awaitingFreshFix = true
    }

    /** Folds the window into [pool] as one measurement, then empties it. */
    private fun fold() {
        val folded = currentSnapshot()
        if (folded.readingCount > 0) {
            pool = when (val verdict = verdict()) {
                is SessionPool.Verdict.Pooled -> verdict.estimate
                SessionPool.Verdict.Refuted, null -> SessionPool.measurement(folded)
                SessionPool.Verdict.Undecided -> pool
            }
        }
        window.reset()
        cachedSnapshot = null
        windowStartNanos = null
    }

    /** What the current window says about [pool]; null when either is missing. */
    private fun verdict(): SessionPool.Verdict? {
        val pool = pool ?: return null
        val current = currentSnapshot()
        if (current.readingCount == 0) return null
        return SessionPool.weigh(pool, SessionPool.measurement(current), current.readingCount)
    }

    /** The height of the odometer's origin that the screen should show, if any. */
    private fun frameHeight(): Double? {
        val current = currentSnapshot()
        val pool = pool
        if (current.readingCount == 0) return pool?.meters
        if (pool == null) return current.averageMeters
        return when (val verdict = verdict()) {
            is SessionPool.Verdict.Pooled -> verdict.estimate.meters
            SessionPool.Verdict.Refuted -> current.averageMeters
            SessionPool.Verdict.Undecided, null -> pool.meters
        }
    }

    private fun refreshDisplay() {
        val frame = frameHeight() ?: return
        val datum = datum ?: return
        displayedElevation = Elevation(frame + appliedMotionMeters, datum)
    }

    /** Discards the averaging window; the cached number stays available, dimmed. */
    private fun flush() {
        window.reset()
        cachedSnapshot = null
        windowStartNanos = null
        epoch++
        if (hasFix) {
            awaitingFreshFix = true
        }
    }

    companion object {
        /** Fixes reporting worse vertical error than this would drag the average around. */
        const val MAX_VERTICAL_ACCURACY_M = 50f

        /**
         * GNSS height errors decorrelate over about 240 s, so windows this far
         * apart in one long session count as separate measurements.
         */
        const val FOLD_INTERVAL_NANOS = 240_000_000_000L

        /**
         * A wake on a device without a barometer may have carried it a floor
         * up or down (about 3 m) with nothing to measure it, so the pool's
         * variance grows by a floor's height squared.
         */
        const val UNTRACKED_WAKE_VARIANCE_M2 = 9.0

        /** A background gap longer than this can mean a whole new elevation. */
        const val RESET_AFTER_GAP_MS = 30_000L
    }
}
