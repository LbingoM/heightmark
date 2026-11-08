package com.bizzarosn.heightmark

import android.content.Context
import android.util.Log
import androidx.annotation.WorkerThread
import org.json.JSONArray

/**
 * Which province, city and district a fix falls in, answered from boundaries
 * bundled in the APK.
 *
 * Naming the nearest administrative seat was the first attempt and it was wrong
 * in a way users notice: the seat table covered a third of China's townships, so
 * a fix in Pudong named a town ten kilometres away instead of the district it
 * was standing in. A boundary test has no such failure mode — it answers with
 * the region that actually contains the point, and its accuracy does not depend
 * on how large or how empty the region is.
 *
 * Everything stays on the device: no INTERNET permission, no lookup service.
 *
 * Data: district-level boundaries (province / prefecture / county), simplified
 * to about 1 km. Coordinates are hundredths of a degree, matching that
 * simplification, which keeps the table at 1.4 MB.
 */
class AdminRegions private constructor(
    private val names: Array<String>,
    /** Per region: minX, minY, maxX, maxY, in hundredths of a degree. */
    private val bounds: Array<FloatArray>,
    /** Per region: interleaved x,y vertices, in hundredths of a degree. */
    private val rings: Array<FloatArray>
) {

    /**
     * The region containing the point, as "省 市 区", or null outside China and
     * its neighbours.
     *
     * The bounding box rejects almost every region before any geometry runs, so
     * a lookup touches a handful of polygons rather than all 2,851.
     */
    fun find(latitude: Double, longitude: Double): String? {
        val x = (longitude * 100.0).toFloat()
        val y = (latitude * 100.0).toFloat()
        for (i in names.indices) {
            val box = bounds[i]
            if (x < box[0] || x > box[2] || y < box[1] || y > box[3]) continue
            if (contains(rings[i], x, y)) return names[i]
        }
        return null
    }

    /** Ray casting: an odd number of crossings means the point is inside. */
    private fun contains(ring: FloatArray, x: Float, y: Float): Boolean {
        var inside = false
        var j = ring.size - 2
        var i = 0
        while (i < ring.size) {
            val yi = ring[i + 1]
            val yj = ring[j + 1]
            if ((yi > y) != (yj > y)) {
                val xi = ring[i]
                val xj = ring[j]
                val crossing = (xj - xi) * (y - yi) / (yj - yi) + xi
                if (x < crossing) inside = !inside
            }
            j = i
            i += 2
        }
        return inside
    }

    companion object {
        private const val TAG = "AdminRegions"
        private const val ASSET = "china_districts.json"

        /**
         * Reads and indexes the boundaries. Call off the main thread: parsing
         * 1.4 MB of JSON takes a noticeable fraction of a second.
         */
        @WorkerThread
        fun load(context: Context): AdminRegions? {
            return try {
                val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
                val array = JSONArray(text)
                val count = array.length()
                val names = Array(count) { "" }
                val bounds = Array(count) { FloatArray(4) }
                val rings = Array(count) { FloatArray(0) }
                for (i in 0 until count) {
                    val entry = array.getJSONObject(i)
                    names[i] = entry.getString("n")
                    val box = entry.getJSONArray("b")
                    for (k in 0 until 4) {
                        bounds[i][k] = box.getDouble(k).toFloat()
                    }
                    val ring = entry.getJSONArray("p")
                    val flat = FloatArray(ring.length())
                    for (k in 0 until ring.length()) {
                        flat[k] = ring.getDouble(k).toFloat()
                    }
                    rings[i] = flat
                }
                AdminRegions(names, bounds, rings)
            } catch (e: Exception) {
                // An unreadable table must not take the panel down: the row just
                // disappears
                Log.w(TAG, "Region table unavailable", e)
                null
            }
        }
    }
}
