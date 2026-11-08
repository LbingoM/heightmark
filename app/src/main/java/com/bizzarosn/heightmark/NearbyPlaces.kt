package com.bizzarosn.heightmark

import android.content.Context
import android.util.Log
import androidx.annotation.WorkerThread
import org.json.JSONArray
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The nearest Chinese administrative seat to a fix, answered entirely from a
 * table bundled in the APK.
 *
 * Reverse geocoding normally means a network call, which this app cannot make:
 * it declares no INTERNET permission and says so in its privacy notice. A table
 * of seats keeps that promise — the coordinates never leave the device — at the
 * cost of precision: it names a county or township, never a street, and western
 * counties are large enough that the distance shown can be tens of kilometres.
 *
 * Data: GeoNames (CC BY 4.0), feature classes PPLC/PPLA/PPLA2/PPLA3/PPLA4,
 * with the Chinese name taken from the alternate names where one exists.
 * Coverage is not complete — roughly three quarters of Chinese counties and a
 * third of townships — so a miss is normal and the row is simply dropped.
 */
class NearbyPlaces private constructor(
    private val latitudes: DoubleArray,
    private val longitudes: DoubleArray,
    private val names: Array<String>
) {

    /** A named seat and how far away it is. */
    data class Place(val name: String, val meters: Double)

    /**
     * The closest seat to [latitude], [longitude]. A planar comparison picks the
     * candidate — at these distances the error is far below the table's own
     * granularity — and the geodesic distance is computed only for the winner.
     */
    fun nearest(latitude: Double, longitude: Double): Place? {
        if (names.isEmpty()) return null
        // Longitude degrees shrink with latitude; the query's cosine scales them
        // so the squared comparison stays proportional to real distance
        val lonScale = cos(Math.toRadians(latitude))
        var bestIndex = -1
        var bestSquared = Double.MAX_VALUE
        for (i in names.indices) {
            val dLat = latitude - latitudes[i]
            val dLon = (longitude - longitudes[i]) * lonScale
            val squared = dLat * dLat + dLon * dLon
            if (squared < bestSquared) {
                bestSquared = squared
                bestIndex = i
            }
        }
        if (bestIndex < 0) return null
        return Place(names[bestIndex], metersBetween(latitude, longitude, latitudes[bestIndex], longitudes[bestIndex]))
    }

    companion object {
        private const val TAG = "NearbyPlaces"
        private const val ASSET = "nearby_places.json"

        /** Great-circle distance in metres. */
        private fun metersBetween(
            fromLat: Double,
            fromLon: Double,
            toLat: Double,
            toLon: Double
        ): Double {
            val earthRadius = 6_371_000.0
            val dLat = Math.toRadians(toLat - fromLat)
            val dLon = Math.toRadians(toLon - fromLon)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(fromLat)) * cos(Math.toRadians(toLat)) *
                sin(dLon / 2) * sin(dLon / 2)
            return 2 * earthRadius * asin(min(1.0, sqrt(a)))
        }

        /**
         * Reads and indexes the table. Call off the main thread: parsing the
         * bundled JSON takes a noticeable fraction of a second.
         */
        @WorkerThread
        fun load(context: Context): NearbyPlaces? {
            return try {
                val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
                val array = JSONArray(text)
                val count = array.length()
                val latitudes = DoubleArray(count)
                val longitudes = DoubleArray(count)
                val names = ArrayList<String>(count)
                for (i in 0 until count) {
                    val entry = array.getJSONArray(i)
                    latitudes[i] = entry.getDouble(0)
                    longitudes[i] = entry.getDouble(1)
                    names += entry.getString(2)
                }
                NearbyPlaces(latitudes, longitudes, names.toTypedArray())
            } catch (e: Exception) {
                // A missing or unreadable table must not take the panel down with
                // it: the row is optional, so it just disappears
                Log.w(TAG, "Place table unavailable", e)
                null
            }
        }
    }
}
