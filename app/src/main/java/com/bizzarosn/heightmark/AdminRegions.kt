package com.bizzarosn.heightmark

import android.content.Context
import android.util.Log
import androidx.annotation.WorkerThread
import java.io.BufferedInputStream
import java.io.DataInputStream

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
 * simplification, and are packed as fixed-width shorts rather than text.
 *
 * The packing is not premature: the 117,450 coordinates used to be JSON, and
 * `org.json` turns every number into a boxed Double, so loading the table threw
 * away well over a hundred thousand short-lived objects. That garbage was
 * collected while the reading was on screen, which is exactly where a pause is
 * noticed. Reading shorts allocates nothing but the arrays below, and the file
 * is 600 KB instead of 1.4 MB.
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
        private const val ASSET = "china_districts.bin"

        /** Bumped whenever the byte layout changes; the reader rejects others. */
        private const val FORMAT_VERSION = 1

        /** Leading four bytes, so a truncated or foreign file fails loudly. */
        private const val MAGIC = "HMRG"

        private const val BUFFER_BYTES = 64 * 1024

        /**
         * Parsed once per process. The table is immutable, so a rotation should
         * reuse it rather than re-read 600 KB and rebuild 2,851 rings.
         */
        @Volatile
        private var cached: AdminRegions? = null

        /**
         * Reads and indexes the boundaries. Call off the main thread; the first
         * call in a process does the work, later calls return the same table.
         */
        @WorkerThread
        fun load(context: Context): AdminRegions? {
            cached?.let { return it }
            val parsed = parse(context) ?: return null
            cached = parsed
            return parsed
        }

        /**
         * Byte layout, big-endian to match [DataInputStream]: magic, format
         * version, region count, then per region a UTF-8 name, a four-short
         * bounding box and a ring, all coordinates in hundredths of a degree.
         */
        private fun parse(context: Context): AdminRegions? {
            return try {
                DataInputStream(
                    BufferedInputStream(context.assets.open(ASSET), BUFFER_BYTES)
                ).use { input ->
                    val magic = ByteArray(4)
                    input.readFully(magic)
                    check(String(magic, Charsets.US_ASCII) == MAGIC) { "not a region table" }
                    check(input.readUnsignedShort() == FORMAT_VERSION) { "unknown table version" }
                    val count = input.readInt()
                    val names = Array(count) { "" }
                    val bounds = Array(count) { FloatArray(4) }
                    val rings = Array(count) { FloatArray(0) }
                    for (i in 0 until count) {
                        val nameBytes = ByteArray(input.readUnsignedShort())
                        input.readFully(nameBytes)
                        names[i] = String(nameBytes, Charsets.UTF_8)
                        for (k in 0 until 4) {
                            bounds[i][k] = input.readUnsignedShort().toFloat()
                        }
                        val ring = FloatArray(input.readInt() * 2)
                        for (k in ring.indices) {
                            ring[k] = input.readUnsignedShort().toFloat()
                        }
                        rings[i] = ring
                    }
                    AdminRegions(names, bounds, rings)
                }
            } catch (e: Exception) {
                // An unreadable table must not take the panel down: the row just
                // disappears
                Log.w(TAG, "Region table unavailable", e)
                null
            }
        }
    }
}
