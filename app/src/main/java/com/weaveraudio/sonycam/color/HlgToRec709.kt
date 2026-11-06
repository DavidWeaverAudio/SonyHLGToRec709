package com.weaveraudio.sonycam.color

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow

/**
 * Built-in colour space transform from BT.2100 HLG (BT.2020 primaries) to Rec.709 SDR.
 *
 * Scene-referred conversion:
 *  1. HLG inverse OETF -> scene-linear BT.2020.
 *  2. Scale so an 18% grey card (HLG signal 38%, per ITU-R BT.2408) lands on linear 0.18.
 *  3. BT.2020 -> BT.709 primaries (3x3 matrix in linear light).
 *  4. Soft gamut compression so BT.2020-only colours roll into 709 instead of hard clipping.
 *  5. Per-channel highlight shoulder so HLG's ~2 stops of headroom above reference white
 *     roll off smoothly rather than clipping.
 *  6. BT.709 OETF.
 *
 * Landmarks at 0 EV: 18% grey -> 41% (standard 709 camera grey), HLG reference white (75%)
 * -> ~92%, HLG peak -> 100%.
 */
object HlgToRec709 {
    /** HLG signal level of an 18% grey card (ITU-R BT.2408). */
    const val HLG_GREY_SIGNAL = 0.38

    /** Linear level above which the highlight shoulder starts (709 signal ~77%). */
    const val KNEE = 0.6

    private val greyScale = 0.18 / Hlg.toLinear(HLG_GREY_SIGNAL)

    /** Linear-light BT.2020 -> BT.709 RGB (ITU-R BT.2087). Row-major. */
    val BT2020_TO_BT709 = doubleArrayOf(
        1.6604910, -0.5876411, -0.0728499,
        -0.1245505, 1.1328999, -0.0083494,
        -0.0181508, -0.1005789, 1.1187297,
    )

    // Gamut compression (same shape as the ACES reference gamut compressor), with limits
    // derived from how far outside 709 the BT.2020 gamut boundary reaches.
    private const val THRESHOLD = 0.8
    private const val POWER = 1.2
    private val limits: DoubleArray = computeLimits()
    private val scales: DoubleArray = DoubleArray(3) { i ->
        val lim = limits[i]
        (lim - THRESHOLD) /
            (((1.0 - THRESHOLD) / (lim - THRESHOLD)).pow(-POWER) - 1.0).pow(1.0 / POWER)
    }

    /**
     * @param r,g,b HLG-encoded BT.2020 RGB signal, 0..1.
     * @param linearGain exposure multiplier applied in scene-linear light (2^EV).
     * @param out receives Rec.709 signal RGB, clamped to 0..1.
     */
    fun transform(r: Double, g: Double, b: Double, linearGain: Double, out: DoubleArray) {
        val k = greyScale * linearGain
        val lr = Hlg.toLinear(r) * k
        val lg = Hlg.toLinear(g) * k
        val lb = Hlg.toLinear(b) * k
        val m = BT2020_TO_BT709
        out[0] = m[0] * lr + m[1] * lg + m[2] * lb
        out[1] = m[3] * lr + m[4] * lg + m[5] * lb
        out[2] = m[6] * lr + m[7] * lg + m[8] * lb
        gamutCompress(out)
        for (i in 0..2) {
            out[i] = Rec709.toSignal(shoulder(max(out[i], 0.0))).coerceIn(0.0, 1.0)
        }
    }

    fun shoulder(x: Double): Double =
        if (x <= KNEE) x else KNEE + (1.0 - KNEE) * (1.0 - exp(-(x - KNEE) / (1.0 - KNEE)))

    /** Compresses each channel's distance from the achromatic axis, in place. */
    fun gamutCompress(rgb: DoubleArray) {
        val ach = max(rgb[0], max(rgb[1], rgb[2]))
        if (ach <= 0.0) return
        for (i in 0..2) {
            val d = (ach - rgb[i]) / ach
            rgb[i] = ach - compressDistance(d, i) * ach
        }
    }

    private fun compressDistance(d: Double, channel: Int): Double {
        if (d < THRESHOLD) return d
        val scl = scales[channel]
        val nd = (d - THRESHOLD) / scl
        return THRESHOLD + scl * nd / (1.0 + nd.pow(POWER)).pow(1.0 / POWER)
    }

    /** Max per-channel distance reached by the BT.2020 gamut boundary once expressed in 709. */
    private fun computeLimits(): DoubleArray {
        val maxD = doubleArrayOf(1.0, 1.0, 1.0)
        val m = BT2020_TO_BT709
        val steps = 64
        for (ri in 0..steps) for (gi in 0..steps) for (bi in 0..steps) {
            val r = ri.toDouble() / steps
            val g = gi.toDouble() / steps
            val b = bi.toDouble() / steps
            // Only the most saturated shell matters: one channel at 0, one at 1.
            if (minOf(r, g, b) != 0.0 || maxOf(r, g, b) != 1.0) continue
            val o0 = m[0] * r + m[1] * g + m[2] * b
            val o1 = m[3] * r + m[4] * g + m[5] * b
            val o2 = m[6] * r + m[7] * g + m[8] * b
            val ach = maxOf(o0, o1, o2)
            if (ach <= 0.0) continue
            maxD[0] = max(maxD[0], (ach - o0) / ach)
            maxD[1] = max(maxD[1], (ach - o1) / ach)
            maxD[2] = max(maxD[2], (ach - o2) / ach)
        }
        return DoubleArray(3) { maxD[it] * 1.02 + 0.001 }
    }
}
