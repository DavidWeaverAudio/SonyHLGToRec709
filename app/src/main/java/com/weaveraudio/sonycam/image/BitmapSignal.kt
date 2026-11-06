package com.weaveraudio.sonycam.image

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.os.Build
import android.util.Half
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/**
 * Raw code values of a decoded bitmap, read straight from pixel memory so Android's colour
 * management never touches them. An HLG photo stays HLG-encoded; a camera JPEG shot in an HLG
 * picture profile (tagged sRGB) yields its HLG code values as-is.
 *
 * The source bitmap can be recycled once this is constructed.
 */
class BitmapSignal(bitmap: Bitmap) {
    val width: Int = bitmap.width
    val height: Int = bitmap.height

    private enum class Layout { RGBA8888, RGBA1010102, RGBA_F16 }

    private val layout: Layout
    private val linearF16: Boolean
    private val rowStride: Int // in pixels
    val buffer: ByteBuffer

    init {
        val is1010102 = Build.VERSION.SDK_INT >= 33 && bitmap.config == Bitmap.Config.RGBA_1010102
        val src = when {
            bitmap.config == Bitmap.Config.ARGB_8888 -> bitmap
            is1010102 -> bitmap
            bitmap.config == Bitmap.Config.RGBA_F16 -> bitmap
            else -> bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }
        layout = when {
            src.config == Bitmap.Config.RGBA_F16 -> Layout.RGBA_F16
            is1010102 -> Layout.RGBA1010102
            else -> Layout.RGBA8888
        }
        val bpp = if (layout == Layout.RGBA_F16) 8 else 4
        rowStride = src.rowBytes / bpp
        // F16 bitmaps may hold linear light; re-encode so we get back the file's code values.
        val cs = src.colorSpace
        linearF16 = layout == Layout.RGBA_F16 && cs != null &&
            (cs == ColorSpace.get(ColorSpace.Named.LINEAR_SRGB) ||
                cs == ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB))
        buffer = ByteBuffer.allocateDirect(src.rowBytes * height).order(ByteOrder.LITTLE_ENDIAN)
        src.copyPixelsToBuffer(buffer)
        buffer.rewind()
        if (src !== bitmap) src.recycle()
    }

    /** True when graded output can be written back into [buffer] row by row. */
    val canWriteInPlace: Boolean get() = layout != Layout.RGBA_F16 && rowStride == width

    /** Reads row [y] as interleaved RGB floats (0..1 code values) into [dst]. */
    fun readRow(y: Int, dst: FloatArray) {
        val base = y * rowStride
        when (layout) {
            Layout.RGBA8888 -> {
                val ints = buffer.asIntBuffer()
                for (x in 0 until width) {
                    val v = ints.get(base + x)
                    dst[x * 3] = (v and 0xFF) * INV255
                    dst[x * 3 + 1] = ((v ushr 8) and 0xFF) * INV255
                    dst[x * 3 + 2] = ((v ushr 16) and 0xFF) * INV255
                }
            }
            Layout.RGBA1010102 -> {
                val ints = buffer.asIntBuffer()
                for (x in 0 until width) {
                    val v = ints.get(base + x)
                    dst[x * 3] = (v and 0x3FF) * INV1023
                    dst[x * 3 + 1] = ((v ushr 10) and 0x3FF) * INV1023
                    dst[x * 3 + 2] = ((v ushr 20) and 0x3FF) * INV1023
                }
            }
            Layout.RGBA_F16 -> {
                val shorts = buffer.asShortBuffer()
                for (x in 0 until width) {
                    val p = (base + x) * 4
                    for (c in 0..2) {
                        var f = Half.toFloat(shorts.get(p + c))
                        if (linearF16) f = srgbEncode(f)
                        dst[x * 3 + c] = f.coerceIn(0f, 1f)
                    }
                }
            }
        }
    }

    companion object {
        private const val INV255 = 1f / 255f
        private const val INV1023 = 1f / 1023f

        private fun srgbEncode(l: Float): Float =
            if (l <= 0.0031308f) 12.92f * l else 1.055f * l.toDouble().pow(1 / 2.4).toFloat() - 0.055f

        /** Writes graded RGB floats as opaque RGBA8888 bytes into row [y] of [out]. */
        fun writeRow8888(out: ByteBuffer, width: Int, y: Int, src: FloatArray) {
            val ints = out.asIntBuffer()
            val base = y * width
            for (x in 0 until width) {
                val r = (src[x * 3] * 255f + 0.5f).toInt().coerceIn(0, 255)
                val g = (src[x * 3 + 1] * 255f + 0.5f).toInt().coerceIn(0, 255)
                val b = (src[x * 3 + 2] * 255f + 0.5f).toInt().coerceIn(0, 255)
                val v = r or (g shl 8) or (b shl 16) or (0xFF shl 24)
                ints.put(base + x, v)
            }
        }
    }
}
