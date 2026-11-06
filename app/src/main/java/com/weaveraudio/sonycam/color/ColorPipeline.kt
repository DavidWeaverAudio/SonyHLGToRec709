package com.weaveraudio.sonycam.color

import kotlin.math.floor
import kotlin.math.pow

enum class TransformMode { BUILT_IN, LUT }

/** Where the exposure change happens relative to the transform. */
enum class ExposureStage { BEFORE, AFTER }

/** The encoding a user LUT expects as input; needed to apply exposure before the LUT. */
enum class LutInput(val curve: TransferCurve, val label: String) {
    HLG(Hlg, "HLG"),
    SLOG3(SLog3, "S-Log3"),
}

enum class VideoCodec(val label: String) { HEVC("HEVC"), AVC("H.264") }

data class GradeSettings(
    val mode: TransformMode = TransformMode.BUILT_IN,
    val exposureEv: Float = 0f,
    val exposureStage: ExposureStage = ExposureStage.BEFORE,
    val lutInput: LutInput = LutInput.HLG,
    val lutName: String? = null,
    val videoCodec: VideoCodec = VideoCodec.HEVC,
)

/**
 * The whole grade (exposure + transform) baked into one 3D LUT over the camera signal.
 * Photos are graded on the CPU and videos on the GPU from the same table, so both match.
 */
class Lut3D(val size: Int, val data: FloatArray) {
    init {
        require(data.size == size * size * size * 3)
    }

    /**
     * Grades [count] interleaved RGB pixels in place. Values are camera signal 0..1 in, graded
     * 0..1 out. Trilinear, matching GPU 3D-texture filtering.
     */
    fun applyInPlace(rgb: FloatArray, count: Int, startPixel: Int = 0) {
        val n = size
        val nm1 = (n - 1).toFloat()
        val lut = data
        val strideG = n * 3
        val strideB = n * n * 3
        var p = startPixel * 3
        val end = (startPixel + count) * 3
        while (p < end) {
            val fr = rgb[p].coerceIn(0f, 1f) * nm1
            val fg = rgb[p + 1].coerceIn(0f, 1f) * nm1
            val fb = rgb[p + 2].coerceIn(0f, 1f) * nm1
            val r0 = floor(fr).toInt().coerceAtMost(n - 2)
            val g0 = floor(fg).toInt().coerceAtMost(n - 2)
            val b0 = floor(fb).toInt().coerceAtMost(n - 2)
            val dr = fr - r0
            val dg = fg - g0
            val db = fb - b0
            val base = b0 * strideB + g0 * strideG + r0 * 3
            for (c in 0..2) {
                val i000 = base + c
                val v000 = lut[i000]
                val v100 = lut[i000 + 3]
                val v010 = lut[i000 + strideG]
                val v110 = lut[i000 + strideG + 3]
                val v001 = lut[i000 + strideB]
                val v101 = lut[i000 + strideB + 3]
                val v011 = lut[i000 + strideB + strideG]
                val v111 = lut[i000 + strideB + strideG + 3]
                val c00 = v000 + (v100 - v000) * dr
                val c10 = v010 + (v110 - v010) * dr
                val c01 = v001 + (v101 - v001) * dr
                val c11 = v011 + (v111 - v011) * dr
                val c0 = c00 + (c10 - c00) * dg
                val c1 = c01 + (c11 - c01) * dg
                rgb[p + c] = c0 + (c1 - c0) * db
            }
            p += 3
        }
    }
}

object ColorPipeline {
    const val PREVIEW_SIZE = 33
    const val EXPORT_SIZE = 65

    /** Exact (un-tabulated) grade of one signal triple. Used for baking and in tests. */
    fun gradePixel(
        settings: GradeSettings,
        cube: CubeLut?,
        r: Double,
        g: Double,
        b: Double,
        out: DoubleArray,
    ) {
        val gain = 2.0.pow(settings.exposureEv.toDouble())
        val pre = if (settings.exposureStage == ExposureStage.BEFORE) gain else 1.0
        val post = if (settings.exposureStage == ExposureStage.AFTER) gain else 1.0

        when (settings.mode) {
            TransformMode.BUILT_IN -> HlgToRec709.transform(r, g, b, pre, out)
            TransformMode.LUT -> {
                var x = r
                var y = g
                var z = b
                if (pre != 1.0) {
                    val curve = settings.lutInput.curve
                    x = curve.toSignal(curve.toLinear(x) * pre).coerceIn(0.0, 1.0)
                    y = curve.toSignal(curve.toLinear(y) * pre).coerceIn(0.0, 1.0)
                    z = curve.toSignal(curve.toLinear(z) * pre).coerceIn(0.0, 1.0)
                }
                if (cube != null) {
                    cube.apply(x, y, z, out)
                } else {
                    out[0] = x; out[1] = y; out[2] = z
                }
            }
        }
        for (i in 0..2) {
            var v = out[i].coerceIn(0.0, 1.0)
            if (post != 1.0) v = Rec709.toSignal(Rec709.toLinear(v) * post)
            out[i] = v.coerceIn(0.0, 1.0)
        }
    }

    fun bake(settings: GradeSettings, cube: CubeLut?, size: Int): Lut3D {
        val data = FloatArray(size * size * size * 3)
        val out = DoubleArray(3)
        val step = 1.0 / (size - 1)
        var i = 0
        for (b in 0 until size) {
            for (g in 0 until size) {
                for (r in 0 until size) {
                    gradePixel(settings, cube, r * step, g * step, b * step, out)
                    data[i] = out[0].toFloat()
                    data[i + 1] = out[1].toFloat()
                    data[i + 2] = out[2].toFloat()
                    i += 3
                }
            }
        }
        return Lut3D(size, data)
    }
}
