package com.bizzarosn.heightmark

import android.location.Location
import androidx.annotation.StringRes
import java.util.Locale

/**
 * Builds the diagnostic panel's lines from a plain snapshot of screen state.
 * Pure JVM, following [LengthFormatter]: it returns resource IDs and their
 * arguments, and the caller resolves them against its Context.
 *
 * The clock is an input rather than a [android.os.SystemClock] call so the
 * whole panel stays assertable in a JVM test.
 */
object DetailsPanelPresenter {

    /** Shown for an accuracy the fix does not report. */
    const val UNKNOWN_VALUE = "?"

    data class Input(
        val isIdle: Boolean,
        val isBlocked: Boolean,
        val signalStale: Boolean,
        val location: Location?,
        val nowElapsedRealtimeNanos: Long,
        val satellitesUsed: Int,
        val satellitesVisible: Int,
        val pressureHpa: Float?,
        val readingCount: Int,
        val datum: ElevationDatum?,
        val useMetric: Boolean,
        val locale: Locale,
        /** Province/city/district of the fix, already formatted; null hides the row. */
        val region: String? = null
    )

    /**
     * One line of the panel. [args] hold plain values (String, Int, Long) or a
     * [LengthFormatter.Detail], which the caller resolves to a string first.
     */
    data class Row(@param:StringRes val templateRes: Int, val args: List<Any> = emptyList())

    fun rows(input: Input): List<Row> {
        val rows = mutableListOf<Row>()
        rows += Row(
            when {
                // A block outranks idle: the duty cycle's own idle flag is
                // meaningless once tracking has stopped for another reason;
                // idle in turn outranks a stale signal, whose silence is the
                // duty cycle's own doing
                input.isBlocked -> R.string.detail_state_blocked
                input.isIdle -> R.string.detail_state_idle
                input.signalStale -> R.string.detail_state_no_signal
                else -> R.string.detail_state_tracking
            }
        )

        val location = input.location
        if (location == null) {
            rows += Row(R.string.detail_no_fix)
        } else {
            // Both MSL rows are gated on the same check: without geoid data
            // there is no sea-level figure and no offset to report
            if (location.hasMslAltitude()) {
                rows += Row(
                    R.string.detail_msl,
                    listOf(input.length(location.mslAltitudeMeters))
                )
            }
            rows += Row(R.string.detail_ellipsoid, listOf(input.length(location.altitude)))
            if (location.hasMslAltitude()) {
                rows += Row(
                    R.string.detail_geoid_offset,
                    listOf(input.length(location.altitude - location.mslAltitudeMeters))
                )
            }
            rows += Row(
                R.string.detail_accuracy,
                listOf(
                    // MSL accuracy bounds the number actually on screen (geoid
                    // model error included); the raw ellipsoidal figure is a
                    // fallback for when the platform or a fix doesn't have it.
                    input.lengthOrUnknown(
                        location.mslAltitudeAccuracyOrNull() ?: location.verticalAccuracyOrNull()
                    ),
                    input.lengthOrUnknown(location.horizontalAccuracyOrNull())
                )
            )
            rows += Row(
                R.string.detail_position,
                listOf(
                    location.latitude.fmt(input.locale, POSITION_DECIMALS),
                    location.longitude.fmt(input.locale, POSITION_DECIMALS)
                )
            )
            // Directly under the coordinates, which it explains
            input.region?.let { region ->
                rows += Row(R.string.detail_region, listOf(region))
            }
            rows += Row(
                R.string.detail_fix_age,
                listOf(
                    fixAgeSeconds(
                        (input.nowElapsedRealtimeNanos - location.elapsedRealtimeNanos) /
                            NANOS_PER_SECOND
                    )
                )
            )
        }

        rows += Row(
            R.string.detail_satellites,
            listOf(input.satellitesUsed, input.satellitesVisible)
        )
        input.pressureHpa?.let { pressure ->
            rows += Row(
                R.string.detail_pressure,
                listOf(pressure.toDouble().fmt(input.locale, PRESSURE_DECIMALS))
            )
        }
        // Both rows describe the average rather than the latest fix, so they
        // sit together at the foot of the panel
        input.datum?.let { rows += Row(it.rowRes()) }
        rows += Row(R.string.detail_readings, listOf(input.readingCount))

        return rows
    }

    @StringRes
    private fun ElevationDatum.rowRes(): Int = when (this) {
        ElevationDatum.MEAN_SEA_LEVEL -> R.string.detail_datum_msl
        ElevationDatum.ELLIPSOID -> R.string.detail_datum_ellipsoid
    }

    private fun Input.length(meters: Double): LengthFormatter.Detail =
        LengthFormatter.detail(meters, useMetric, locale)

    private fun Input.lengthOrUnknown(meters: Float?): Any =
        meters?.let { length(it.toDouble()) } ?: UNKNOWN_VALUE

    private fun Double.fmt(locale: Locale, decimals: Int): String =
        String.format(locale, "%.${decimals}f", this)

    /**
     * Below [FIX_AGE_COARSEN_THRESHOLD_S] the exact second is reported; beyond
     * it the value snaps to [FIX_AGE_STEP_S]-second steps. A 1 Hz ticker keeps
     * this row's fix age moving, so an unthrottled value would rewrite the
     * row's accessibility node every second, permanently interrupting anyone
     * reading it with a screen reader.
     */
    private fun fixAgeSeconds(exactSeconds: Long): Long =
        if (exactSeconds < FIX_AGE_COARSEN_THRESHOLD_S) {
            exactSeconds
        } else {
            (exactSeconds / FIX_AGE_STEP_S) * FIX_AGE_STEP_S
        }

    private const val NANOS_PER_SECOND = 1_000_000_000
    private const val POSITION_DECIMALS = 5
    private const val PRESSURE_DECIMALS = 1
    private const val FIX_AGE_COARSEN_THRESHOLD_S = 10L
    private const val FIX_AGE_STEP_S = 10L
}
