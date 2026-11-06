package com.weaveraudio.sonycam.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.weaveraudio.sonycam.MediaSaver
import com.weaveraudio.sonycam.color.Lut3D
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.roundToInt

object ImageGrader {

    /**
     * Decodes [uri] with the software allocator and without any colour conversion we can avoid.
     * EXIF orientation is applied by ImageDecoder. [maxDim] downsamples for previews.
     */
    fun decode(context: Context, uri: Uri, maxDim: Int? = null): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            if (maxDim != null) {
                val w = info.size.width
                val h = info.size.height
                val scale = max(w, h).toFloat() / maxDim
                if (scale > 1f) {
                    decoder.setTargetSize(
                        (w / scale).roundToInt().coerceAtLeast(1),
                        (h / scale).roundToInt().coerceAtLeast(1),
                    )
                }
            }
        }
    }

    /** All code values of [signal] as one interleaved RGB array (preview-sized images only). */
    fun toFloats(signal: BitmapSignal): FloatArray {
        val w = signal.width
        val all = FloatArray(w * signal.height * 3)
        val row = FloatArray(w * 3)
        for (y in 0 until signal.height) {
            signal.readRow(y, row)
            System.arraycopy(row, 0, all, y * w * 3, w * 3)
        }
        return all
    }

    /** Packs interleaved RGB floats (0..1) into an sRGB-tagged ARGB_8888 bitmap. */
    fun fromFloats(width: Int, height: Int, rgb: FloatArray): Bitmap {
        val buf = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN)
        val row = FloatArray(width * 3)
        for (y in 0 until height) {
            System.arraycopy(rgb, y * width * 3, row, 0, width * 3)
            BitmapSignal.writeRow8888(buf, width, y, row)
        }
        buf.rewind()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            copyPixelsFromBuffer(buf)
        }
    }

    /** Grades every pixel of [signal] with [lut], in parallel by row bands. */
    suspend fun grade(signal: BitmapSignal, lut: Lut3D, onProgress: (Float) -> Unit = {}): Bitmap {
        val w = signal.width
        val h = signal.height
        val out: ByteBuffer = if (signal.canWriteInPlace) {
            signal.buffer
        } else {
            ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.LITTLE_ENDIAN)
        }
        val bands = (Runtime.getRuntime().availableProcessors()).coerceIn(1, 8) * 4
        val rowsPerBand = (h + bands - 1) / bands
        var done = 0
        coroutineScope {
            (0 until bands).map { band ->
                async(Dispatchers.Default) {
                    val row = FloatArray(w * 3)
                    val y0 = band * rowsPerBand
                    val y1 = minOf(h, y0 + rowsPerBand)
                    for (y in y0 until y1) {
                        if ((y and 63) == 0) ensureActive()
                        signal.readRow(y, row)
                        lut.applyInPlace(row, w)
                        BitmapSignal.writeRow8888(out, w, y, row)
                    }
                    synchronized(this@ImageGrader) {
                        done++
                        onProgress(done.toFloat() / bands)
                    }
                }
            }.awaitAll()
        }
        out.rewind()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(out)
        return bitmap
    }

    /**
     * Full-resolution export: decode, grade, write a JPEG (with the camera's EXIF carried over)
     * to Pictures/HLG709. Returns the gallery Uri.
     */
    suspend fun export(
        context: Context,
        uri: Uri,
        lut: Lut3D,
        onProgress: (Float) -> Unit,
    ): Uri = withContext(Dispatchers.IO) {
        val name = MediaSaver.outputNameFor(context, uri, isVideo = false)
        val temp = File(context.cacheDir, "export_${System.nanoTime()}.jpg")
        try {
            exportToFile(context, uri, lut, temp, onProgress)
            val target = MediaSaver.createPending(context, name, "image/jpeg", isVideo = false)
            try {
                context.contentResolver.openOutputStream(target)!!.use { os ->
                    temp.inputStream().use { it.copyTo(os) }
                }
                MediaSaver.publish(context, target)
            } catch (t: Throwable) {
                MediaSaver.discard(context, target)
                throw t
            }
            target
        } finally {
            temp.delete()
        }
    }

    /** Decode, grade and write [uri] as a JPEG file at [dest]. Exposed for tests. */
    suspend fun exportToFile(
        context: Context,
        uri: Uri,
        lut: Lut3D,
        dest: File,
        onProgress: (Float) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        val decoded = decode(context, uri)
        onProgress(0.1f)
        val signal = BitmapSignal(decoded)
        decoded.recycle()
        val graded = grade(signal, lut) { onProgress(0.1f + 0.75f * it) }
        try {
            dest.outputStream().buffered().use { os ->
                check(graded.compress(Bitmap.CompressFormat.JPEG, 95, os)) { "JPEG encode failed" }
            }
        } finally {
            graded.recycle()
        }
        copyExif(context, uri, dest, signal.width, signal.height)
        onProgress(1f)
    }

    private val EXIF_TAGS = listOf(
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_LENS_SPECIFICATION,
        ExifInterface.TAG_BODY_SERIAL_NUMBER,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_SHUTTER_SPEED_VALUE,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_EXPOSURE_MODE,
        ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_SCENE_CAPTURE_TYPE,
        ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_IMG_DIRECTION,
        ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        ExifInterface.TAG_GPS_SPEED,
        ExifInterface.TAG_GPS_SPEED_REF,
        ExifInterface.TAG_GPS_MAP_DATUM,
    )

    private fun copyExif(context: Context, source: Uri, dest: File, width: Int, height: Int) {
        val input = runCatching {
            context.contentResolver.openInputStream(source)?.use { ExifInterface(it) }
        }.getOrNull()
        val out = ExifInterface(dest)
        if (input != null) {
            for (tag in EXIF_TAGS) {
                input.getAttribute(tag)?.let { out.setAttribute(tag, it) }
            }
        }
        // Pixels are already upright and now Rec.709 / sRGB primaries.
        out.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        out.setAttribute(ExifInterface.TAG_COLOR_SPACE, ExifInterface.COLOR_SPACE_S_RGB.toString())
        out.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
        out.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
        out.setAttribute(ExifInterface.TAG_SOFTWARE, "HLG 709")
        out.saveAttributes()
    }
}
