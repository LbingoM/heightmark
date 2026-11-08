package com.bizzarosn.heightmark

import android.content.Context
import android.location.Location
import android.location.altitude.AltitudeConverter
import android.os.Build
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import java.io.IOException

/**
 * Resolves the elevation of a GNSS fix, tagged with the datum it landed on.
 *
 * Raw [Location.getAltitude] is height above the WGS84 reference ellipsoid, which
 * differs from sea-level elevation by roughly -100 m to +85 m depending on where on
 * Earth the fix is. The platform [AltitudeConverter] corrects this using on-device
 * geoid data, entirely offline.
 *
 * Conversion can fail — no geoid data, a location the converter rejects — and the
 * ellipsoid height is still worth returning, but only if callers can tell the two
 * apart: [ElevationSession] keeps them out of one another's averages, and the
 * screen names the datum it is showing. Hence [Elevation] rather than a bare
 * Double, which is what let a fallback pass for a sea-level reading.
 *
 * Keep a single instance: the converter caches geoid data between calls, so the
 * first conversion in a region may take seconds while later ones are cheap.
 */
class AltitudeResolver(
    private val context: Context,
    private val converter: AltitudeConverter = AltitudeConverter()
) {

    /**
     * Returns the location's elevation above Mean Sea Level, falling back to its
     * raw ellipsoid altitude — tagged [ElevationDatum.ELLIPSOID] — whenever the
     * conversion cannot be made.
     *
     * The location must have an altitude ([Location.hasAltitude]).
     */
    @WorkerThread
    @Synchronized
    fun resolve(location: Location): Elevation {
        return try {
            converter.addMslAltitudeToLocation(context, location)
            if (location.hasMslAltitude()) {
                failureLogged = false
                Elevation(location.mslAltitudeMeters, ElevationDatum.MEAN_SEA_LEVEL)
            } else {
                logFailureOnce("Converter left no MSL altitude, using ellipsoid altitude")
                location.ellipsoidHeight()
            }
        } catch (e: IOException) {
            logFailureOnce("Geoid data unavailable, using ellipsoid altitude", e)
            location.ellipsoidHeight()
        } catch (e: IllegalArgumentException) {
            logFailureOnce("Location rejected by AltitudeConverter, using ellipsoid altitude", e)
            location.ellipsoidHeight()
        }
    }

    /**
     * The same conversion attempted on the calling thread. API 35 added
     * [AltitudeConverter.tryAddMslAltitudeToLocation], which never loads raw
     * assets, so once the geoid data is loaded every fix converts in place
     * instead of paying a hop to a worker thread and back. Null means the data
     * is not loaded yet: the caller then falls back to [resolve] off the main
     * thread, which loads it for the next one.
     */
    @MainThread
    @Synchronized
    fun tryResolve(location: Location): Elevation? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return null
        val added = try {
            converter.tryAddMslAltitudeToLocation(location)
        } catch (e: IllegalArgumentException) {
            logFailureOnce("Location rejected by AltitudeConverter, using ellipsoid altitude", e)
            return null
        }
        if (!added) return null
        failureLogged = false
        return Elevation(location.mslAltitudeMeters, ElevationDatum.MEAN_SEA_LEVEL)
    }

    /**
     * True once the current run of failures has been logged. Conversion runs at
     * 1 Hz, so an unbroken outage would otherwise format a stack trace every
     * second for the rest of the session.
     */
    private var failureLogged = false

    private fun logFailureOnce(message: String, cause: Throwable? = null) {
        if (failureLogged) return
        failureLogged = true
        if (cause == null) Log.w(TAG, message) else Log.w(TAG, message, cause)
    }

    private fun Location.ellipsoidHeight() = Elevation(altitude, ElevationDatum.ELLIPSOID)

    companion object {
        private const val TAG = "AltitudeResolver"
    }
}
