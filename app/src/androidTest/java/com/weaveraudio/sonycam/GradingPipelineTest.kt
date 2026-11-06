package com.weaveraudio.sonycam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.weaveraudio.sonycam.color.ColorPipeline
import com.weaveraudio.sonycam.color.GradeSettings
import com.weaveraudio.sonycam.color.VideoCodec
import com.weaveraudio.sonycam.image.ImageGrader
import com.weaveraudio.sonycam.video.AudioTrackExporter
import com.weaveraudio.sonycam.video.VideoFrameGrabber
import com.weaveraudio.sonycam.video.VideoGrader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * End-to-end colour checks on a synthetic HLG clip/still with flat patches of known code values
 * (built by tools/make_test_media.py). Compares what the real pipeline produces with the exact
 * maths from ColorPipeline.
 */
@RunWith(AndroidJUnit4::class)
class GradingPipelineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx = instrumentation.targetContext
    private val settings = GradeSettings(exposureEv = -1f)
    private val lut = ColorPipeline.bake(settings, null, ColorPipeline.EXPORT_SIZE)

    private fun asset(name: String): File {
        val f = File(ctx.cacheDir, name)
        instrumentation.context.assets.open(name).use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    private fun expectedGraded(i: Int): IntArray {
        val p = PATCHES[i]
        val out = DoubleArray(3)
        ColorPipeline.gradePixel(settings, null, p[0], p[1], p[2], out)
        return IntArray(3) { (out[it] * 255).roundToInt() }
    }

    private fun expectedRaw(i: Int) = IntArray(3) { (PATCHES[i][it] * 255).roundToInt() }

    /** Mean of a 9x9 block at the centre of patch [i]; [bitmap] may be scaled from 640x360. */
    private fun patch(bitmap: Bitmap, i: Int): IntArray {
        val sx = bitmap.width / 640.0
        val sy = bitmap.height / 360.0
        val cx = ((i % COLS) * 160 + 80) * sx
        val cy = ((i / COLS) * 120 + 60) * sy
        val sum = IntArray(3)
        var n = 0
        for (dy in -4..4) for (dx in -4..4) {
            val c = bitmap.getPixel((cx + dx).toInt(), (cy + dy).toInt())
            sum[0] += (c shr 16) and 0xFF; sum[1] += (c shr 8) and 0xFF; sum[2] += c and 0xFF
            n++
        }
        return IntArray(3) { (sum[it].toDouble() / n).roundToInt() }
    }

    /**
     * Greys must always match. Saturated patches are only held to [colourTol] on the exact
     * (EXT_YUV_target) path; on the fallback the GPU driver picks the YUV matrix (the emulator's
     * always uses BT.601), so they are reported but not asserted.
     */
    private fun compare(
        label: String,
        bitmap: Bitmap,
        expected: (Int) -> IntArray,
        greyTol: Int,
        colourTol: Int,
        exactYuv: Boolean = true,
    ) {
        val report = StringBuilder("$label\n")
        var worstGrey = 0
        var worstColour = 0
        for (i in PATCHES.indices) {
            val got = patch(bitmap, i)
            val want = expected(i)
            val err = (0..2).maxOf { abs(got[it] - want[it]) }
            if (i < 8) worstGrey = maxOf(worstGrey, err) else worstColour = maxOf(worstColour, err)
            report.append("  patch $i in=${PATCHES[i].toList()} want=${want.toList()} got=${got.toList()} err=$err\n")
        }
        report.append("  exactYuv=$exactYuv worstGrey=$worstGrey worstColour=$worstColour\n")
        Log.i(TAG, report.toString())
        val greyLimit = if (exactYuv) greyTol else greyTol + 2
        assertTrue("$report\nworst grey error $worstGrey > $greyLimit", worstGrey <= greyLimit)
        if (exactYuv) {
            assertTrue("$report\nworst colour error $worstColour > $colourTol", worstColour <= colourTol)
        }
    }

    private fun hasDecoder(clip: File): Boolean {
        val ex = MediaExtractor().apply { setDataSource(clip.absolutePath) }
        val format = (0 until ex.trackCount).map { ex.getTrackFormat(it) }
            .first { it.getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
        ex.release()
        return runCatching { VideoGrader.findDecoder(format) }.isSuccess
    }

    private fun clip(name: String): File = asset(name).also {
        assumeTrue("no decoder for $name on this device", hasDecoder(it))
    }

    @Test fun rawSignal10bit() = rawSignal("hlg_patches.mov")
    @Test fun rawSignal8bit() = rawSignal("hlg_patches_8bit.mov")
    @Test fun gpuGrade10bit() = gpuGrade("hlg_patches.mov")
    @Test fun gpuGrade8bit() = gpuGrade("hlg_patches_8bit.mov")
    @Test fun export10bit() = videoExport("hlg_patches.mov", VideoCodec.HEVC)
    @Test fun export8bitHevc() = videoExport("hlg_patches_8bit.mov", VideoCodec.HEVC)
    @Test fun export8bitAvc() = videoExport("hlg_patches_8bit.mov", VideoCodec.AVC)

    private fun rawSignal(name: String) {
        val frame = VideoFrameGrabber.grab(ctx, Uri.fromFile(clip(name)), 500_000, maxDim = null)
        compare("raw video signal $name", frame.bitmap, ::expectedRaw, 3, 6, frame.rawYuv)
    }

    private fun gpuGrade(name: String) {
        val frame = VideoFrameGrabber.grab(ctx, Uri.fromFile(clip(name)), 500_000, maxDim = null, lut = lut)
        compare("GPU-graded frame $name", frame.bitmap, ::expectedGraded, 3, 6, frame.rawYuv)
    }

    private fun videoExport(name: String, codec: VideoCodec) {
        val clip = clip(name)
        val audio = File(ctx.cacheDir, "test_audio.mp4").apply { delete() }
        val out = File(ctx.cacheDir, "test_out.mp4").apply { delete() }
        val audioError = runBlocking { AudioTrackExporter.exportAac(ctx, Uri.fromFile(clip), audio, 2) }
        assertNull("audio conversion failed: $audioError", audioError)

        ParcelFileDescriptor.open(
            out,
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE,
        ).use { pfd ->
            VideoGrader.transcode(
                ctx, Uri.fromFile(clip), pfd.fileDescriptor, lut, codec, audio,
                isCancelled = { false }, onProgress = {},
            )
        }

        val ex = MediaExtractor().apply { setDataSource(out.absolutePath) }
        val formats = (0 until ex.trackCount).map { ex.getTrackFormat(it) }
        ex.release()
        Log.i(TAG, "output tracks for $name/$codec: $formats")
        val video = formats.first { it.getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
        val audioTrack = formats.firstOrNull { it.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC }
        assertTrue("no AAC audio in output: $formats", audioTrack != null)
        assertEquals(640, video.getInteger(MediaFormat.KEY_WIDTH))
        val durUs = video.getLong(MediaFormat.KEY_DURATION)
        assertTrue("video duration $durUs", durUs in 1_800_000..2_100_000)
        val audioDur = audioTrack!!.getLong(MediaFormat.KEY_DURATION)
        assertTrue("audio duration $audioDur", abs(audioDur - 2_000_000) < 150_000)

        val frame = VideoFrameGrabber.grab(ctx, Uri.fromFile(out), 500_000, maxDim = null)
        compare("exported video $name/$codec", frame.bitmap, ::expectedGraded, 5, 9, frame.rawYuv)
    }

    @Test
    fun imageExportEndToEnd() {
        val still = asset("hlg_patches.png")
        val out = File(ctx.cacheDir, "test_out.jpg").apply { delete() }
        runBlocking { ImageGrader.exportToFile(ctx, Uri.fromFile(still), lut, out) }
        val bitmap = BitmapFactory.decodeFile(out.absolutePath)
        compare("exported still", bitmap, ::expectedGraded, greyTol = 3, colourTol = 6)
    }

    companion object {
        private const val TAG = "GradingPipelineTest"
        private const val COLS = 4

        /** Must match tools/make_test_media.py. */
        val PATCHES = listOf(
            doubleArrayOf(0.10, 0.10, 0.10), doubleArrayOf(0.25, 0.25, 0.25),
            doubleArrayOf(0.38, 0.38, 0.38), doubleArrayOf(0.50, 0.50, 0.50),
            doubleArrayOf(0.62, 0.62, 0.62), doubleArrayOf(0.75, 0.75, 0.75),
            doubleArrayOf(0.90, 0.90, 0.90), doubleArrayOf(1.00, 1.00, 1.00),
            doubleArrayOf(0.60, 0.30, 0.20), doubleArrayOf(0.30, 0.50, 0.25),
            doubleArrayOf(0.25, 0.30, 0.60), doubleArrayOf(0.70, 0.70, 0.20),
        )
    }
}
