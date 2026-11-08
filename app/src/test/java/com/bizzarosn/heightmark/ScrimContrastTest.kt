package com.bizzarosn.heightmark

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * WCAG 2.1 contrast regression test for everything drawn over the background
 * illustration.
 *
 * The runtime accessibility checks (ATF via the test runner) measure contrast
 * on emulator screenshots, but nothing there pins the *worst case*. This test
 * does it with pure math, so "lighten the scrim a touch" or "dim the text a
 * bit more" fails fast on the JVM with the computed ratio in the message.
 *
 * Color values are parsed from the resource files and alpha constants are
 * referenced from production code.
 */
class ScrimContrastTest {

    /**
     * Pure white: the brightest backdrop any artwork can produce beneath the
     * scrim. Both illustrations really do reach it (the day mist band and the
     * night horizon glow), and using the absolute bound makes these
     * guarantees hold for BOTH themes and for any future artwork swap —
     * nothing needs re-measuring when the images change.
     */
    private val worstCaseBackdrop = Rgb(1.0, 1.0, 1.0)

    /** Backdrop every on-image element sits on: the scrim floor over the artwork. */
    private val scrimmedBackdrop = resourceColor("hm_scrim_floor").over(worstCaseBackdrop)

    private val white = resourceColor("hm_on_scrim").over(scrimmedBackdrop)

    @Test
    fun `primary on-scrim text meets 4_5 to 1`() {
        assertAtLeast(4.5, contrast(white, scrimmedBackdrop), "hm_on_scrim text")
    }

    /**
     * The bottom of the scrim is no longer pure black but deep pine ink, and the
     * reading sits on it. Same requirement, measured against that stop.
     */
    @Test
    fun `reading over the deep scrim meets 4_5 to 1`() {
        val deep = resourceColor("hm_scrim_deep").over(worstCaseBackdrop)
        assertAtLeast(4.5, contrast(Rgb(1.0, 1.0, 1.0), deep), "white over hm_scrim_deep")
    }

    @Test
    fun `secondary on-scrim text meets 4_5 to 1`() {
        val secondary = resourceColor("hm_on_scrim_secondary").over(scrimmedBackdrop)
        assertAtLeast(4.5, contrast(secondary, scrimmedBackdrop), "hm_on_scrim_secondary text")
    }

    @Test
    fun `unit chip outline meets non-text 3 to 1`() {
        val outline = resourceColor("hm_on_scrim_outline").over(scrimmedBackdrop)
        assertAtLeast(3.0, contrast(outline, scrimmedBackdrop), "hm_on_scrim_outline stroke")
    }

    @Test
    fun `dormant-dimmed hero number meets large-text 3 to 1`() {
        val dimmed = whiteAtAlpha(ReadingState.DIMMED_TEXT_ALPHA)
        assertAtLeast(3.0, contrast(dimmed, scrimmedBackdrop), "hero at DIMMED_TEXT_ALPHA")
    }

    @Test
    fun `stability line strokes meet non-text 3 to 1`() {
        val wave = whiteAtAlpha(StabilityLineView.WAVE_ALPHA)
        val core = whiteAtAlpha(StabilityLineView.CORE_ALPHA)
        val dormant = whiteAtAlpha(StabilityLineView.DORMANT_ALPHA)
        assertAtLeast(3.0, contrast(wave, scrimmedBackdrop), "wave at WAVE_ALPHA")
        assertAtLeast(3.0, contrast(core, scrimmedBackdrop), "core at CORE_ALPHA")
        assertAtLeast(3.0, contrast(dormant, scrimmedBackdrop), "dormant line at DORMANT_ALPHA")
    }

    // ---- WCAG math ----------------------------------------------------------

    private data class Rgb(val r: Double, val g: Double, val b: Double)

    private data class Argb(val a: Double, val rgb: Rgb) {
        /** Source-over composite onto an opaque backdrop. */
        fun over(dst: Rgb) = Rgb(
            rgb.r * a + dst.r * (1 - a),
            rgb.g * a + dst.g * (1 - a),
            rgb.b * a + dst.b * (1 - a)
        )
    }

    private fun whiteAtAlpha(alpha: Float) =
        Argb(alpha.toDouble(), Rgb(1.0, 1.0, 1.0)).over(scrimmedBackdrop)

    private fun relativeLuminance(c: Rgb): Double {
        fun linear(v: Double) = if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        return 0.2126 * linear(c.r) + 0.7152 * linear(c.g) + 0.0722 * linear(c.b)
    }

    private fun contrast(a: Rgb, b: Rgb): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun assertAtLeast(threshold: Double, ratio: Double, subject: String) {
        assertTrue(
            "$subject contrast is %.2f:1 over the worst-case (pure white) backdrop; WCAG requires $threshold:1"
                .format(ratio),
            ratio >= threshold
        )
    }

    // ---- Resource parsing ---------------------------------------------------

    private fun resourceColor(name: String): Argb {
        val hex = colorHexByName[name]
            ?: error("Color resource '$name' not found in colors.xml")
        val argb = hex.padStart(8, 'F').toLong(16)
        return Argb(
            a = ((argb shr 24) and 0xFF) / 255.0,
            rgb = Rgb(
                ((argb shr 16) and 0xFF) / 255.0,
                ((argb shr 8) and 0xFF) / 255.0,
                (argb and 0xFF) / 255.0
            )
        )
    }

    private companion object {
        /**
         * colors.xml parsed once for the whole class rather than re-read and
         * re-scanned per lookup. Unit tests run with the module directory as
         * the working directory.
         */
        val colorHexByName: Map<String, String> by lazy {
            val xml = File("src/main/res/values/colors.xml").readText()
            Regex("""<color name="([^"]+)">#([0-9A-Fa-f]{6,8})</color>""")
                .findAll(xml)
                .associate { it.groupValues[1] to it.groupValues[2] }
        }
    }
}
