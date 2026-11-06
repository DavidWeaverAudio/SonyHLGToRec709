package com.weaveraudio.sonycam.color

import java.io.BufferedReader
import java.io.Reader
import kotlin.math.floor

/**
 * A parsed Adobe/Resolve `.cube` LUT. Supports a 3D LUT, a 1D LUT, or a 1D shaper followed by
 * a 3D LUT (Resolve's combined format). Data order is red-fastest, as the format specifies.
 */
class CubeLut private constructor(
    val title: String,
    val size3d: Int,
    private val data3d: FloatArray?,
    private val domainMin: DoubleArray,
    private val domainMax: DoubleArray,
    val size1d: Int,
    private val data1d: FloatArray?,
    private val shaperMin: DoubleArray,
    private val shaperMax: DoubleArray,
) {
    /** Applies the LUT to (r, g, b) and writes the result into [out]. */
    fun apply(r: Double, g: Double, b: Double, out: DoubleArray) {
        var x = r
        var y = g
        var z = b
        if (data1d != null) {
            x = sample1d(x, 0)
            y = sample1d(y, 1)
            z = sample1d(z, 2)
        }
        if (data3d != null) {
            sample3d(x, y, z, out)
        } else {
            out[0] = x; out[1] = y; out[2] = z
        }
    }

    private fun sample1d(v: Double, channel: Int): Double {
        val lut = data1d!!
        val n = size1d
        val t = ((v - shaperMin[channel]) / (shaperMax[channel] - shaperMin[channel]))
            .coerceIn(0.0, 1.0) * (n - 1)
        val i0 = floor(t).toInt().coerceAtMost(n - 2)
        val f = t - i0
        val a = lut[i0 * 3 + channel]
        val c = lut[(i0 + 1) * 3 + channel]
        return a + (c - a) * f
    }

    private fun sample3d(r: Double, g: Double, b: Double, out: DoubleArray) {
        val lut = data3d!!
        val n = size3d
        val fr = ((r - domainMin[0]) / (domainMax[0] - domainMin[0])).coerceIn(0.0, 1.0) * (n - 1)
        val fg = ((g - domainMin[1]) / (domainMax[1] - domainMin[1])).coerceIn(0.0, 1.0) * (n - 1)
        val fb = ((b - domainMin[2]) / (domainMax[2] - domainMin[2])).coerceIn(0.0, 1.0) * (n - 1)
        val r0 = floor(fr).toInt().coerceAtMost(n - 2)
        val g0 = floor(fg).toInt().coerceAtMost(n - 2)
        val b0 = floor(fb).toInt().coerceAtMost(n - 2)
        val dr = fr - r0
        val dg = fg - g0
        val db = fb - b0
        for (c in 0..2) {
            fun at(ri: Int, gi: Int, bi: Int) = lut[((bi * n + gi) * n + ri) * 3 + c].toDouble()
            val c00 = at(r0, g0, b0) + (at(r0 + 1, g0, b0) - at(r0, g0, b0)) * dr
            val c10 = at(r0, g0 + 1, b0) + (at(r0 + 1, g0 + 1, b0) - at(r0, g0 + 1, b0)) * dr
            val c01 = at(r0, g0, b0 + 1) + (at(r0 + 1, g0, b0 + 1) - at(r0, g0, b0 + 1)) * dr
            val c11 = at(r0, g0 + 1, b0 + 1) +
                (at(r0 + 1, g0 + 1, b0 + 1) - at(r0, g0 + 1, b0 + 1)) * dr
            val c0 = c00 + (c10 - c00) * dg
            val c1 = c01 + (c11 - c01) * dg
            out[c] = c0 + (c1 - c0) * db
        }
    }

    class ParseException(message: String) : Exception(message)

    companion object {
        fun parse(reader: Reader): CubeLut {
            var title = ""
            var size3d = 0
            var size1d = 0
            val domainMin = doubleArrayOf(0.0, 0.0, 0.0)
            val domainMax = doubleArrayOf(1.0, 1.0, 1.0)
            var shaperMin = doubleArrayOf(0.0, 0.0, 0.0)
            var shaperMax = doubleArrayOf(1.0, 1.0, 1.0)
            var range3dSet = false
            val values = ArrayList<Float>(35937 * 3)

            BufferedReader(reader).useLines { lines ->
                for (raw in lines) {
                    val line = raw.substringBefore('#').trim()
                    if (line.isEmpty()) continue
                    val first = line[0]
                    if (first.isDigit() || first == '-' || first == '+' || first == '.') {
                        val parts = line.split(Regex("\\s+"))
                        if (parts.size < 3) throw ParseException("Bad data line: $raw")
                        for (k in 0..2) {
                            values += parts[k].toFloatOrNull()
                                ?: throw ParseException("Bad number in: $raw")
                        }
                        continue
                    }
                    val key = line.substringBefore(' ').uppercase()
                    val rest = line.substringAfter(' ', "").trim()
                    val nums = { rest.split(Regex("\\s+")).mapNotNull { it.toDoubleOrNull() } }
                    when (key) {
                        "TITLE" -> title = rest.trim('"')
                        "LUT_3D_SIZE" -> size3d = rest.toIntOrNull()
                            ?: throw ParseException("Bad LUT_3D_SIZE")
                        "LUT_1D_SIZE" -> size1d = rest.toIntOrNull()
                            ?: throw ParseException("Bad LUT_1D_SIZE")
                        "DOMAIN_MIN" -> nums().takeIf { it.size >= 3 }?.let {
                            for (i in 0..2) domainMin[i] = it[i]
                            shaperMin = domainMin.copyOf()
                        }
                        "DOMAIN_MAX" -> nums().takeIf { it.size >= 3 }?.let {
                            for (i in 0..2) domainMax[i] = it[i]
                            shaperMax = domainMax.copyOf()
                        }
                        "LUT_3D_INPUT_RANGE" -> nums().takeIf { it.size >= 2 }?.let {
                            for (i in 0..2) { domainMin[i] = it[0]; domainMax[i] = it[1] }
                            range3dSet = true
                        }
                        "LUT_1D_INPUT_RANGE" -> nums().takeIf { it.size >= 2 }?.let {
                            shaperMin = DoubleArray(3) { _ -> it[0] }
                            shaperMax = DoubleArray(3) { _ -> it[1] }
                        }
                        else -> Unit // Unknown keywords are ignored, as the spec allows.
                    }
                }
            }

            if (size3d == 0 && size1d == 0) throw ParseException("No LUT_3D_SIZE or LUT_1D_SIZE")
            if (size3d != 0 && size3d !in 2..256) throw ParseException("Unsupported 3D size $size3d")
            if (size1d != 0 && size1d !in 2..65536) throw ParseException("Unsupported 1D size $size1d")
            val count1d = size1d * 3
            val count3d = size3d * size3d * size3d * 3
            if (values.size != count1d + count3d) {
                throw ParseException(
                    "Expected ${(count1d + count3d) / 3} entries but found ${values.size / 3}"
                )
            }
            val all = values.toFloatArray()
            val data1d = if (size1d > 0) all.copyOfRange(0, count1d) else null
            val data3d = if (size3d > 0) all.copyOfRange(count1d, count1d + count3d) else null
            // In a combined shaper+3D file without an explicit 3D range, the 3D LUT consumes the
            // shaper's 0..1 output.
            if (data1d != null && data3d != null && !range3dSet) {
                for (i in 0..2) { domainMin[i] = 0.0; domainMax[i] = 1.0 }
            }
            return CubeLut(
                title, size3d, data3d, domainMin, domainMax, size1d, data1d, shaperMin, shaperMax,
            )
        }
    }
}
