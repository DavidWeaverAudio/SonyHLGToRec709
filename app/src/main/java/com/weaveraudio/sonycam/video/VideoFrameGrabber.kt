package com.weaveraudio.sonycam.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.opengl.GLES30
import android.os.SystemClock
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeoutException
import kotlin.math.max

/**
 * Pulls one frame out of a video as its raw camera signal (no grade), through the same decoder
 * and YUV->RGB path the export uses, so previews match exported files.
 */
object VideoFrameGrabber {

    /** [rawYuv]: true when the GPU sampled the decoder's Y'CbCr directly (exact path). */
    class Frame(val bitmap: Bitmap, val durationUs: Long, val rawYuv: Boolean)

    /**
     * Blocking. Returns the frame at or just after [timeUs], scaled so its longest side is at
     * most [maxDim] (or full size when null) and rotated upright. Pass a [lut] to grade it on
     * the GPU instead of returning the raw signal.
     */
    fun grab(
        context: Context,
        uri: Uri,
        timeUs: Long,
        maxDim: Int?,
        lut: com.weaveraudio.sonycam.color.Lut3D? = null,
    ): Frame {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var egl: EglCore? = null
        var renderer: FrameRenderer? = null
        var pbuffer: android.opengl.EGLSurface? = null
        var fbo = 0
        var fboTex = 0
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: throw IOException("No video track found")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            val decoderName = VideoGrader.findDecoder(format)

            val core = EglCore(recordable = false).also { egl = it }
            val surface = core.createPbufferSurface(1, 1).also { pbuffer = it }
            core.makeCurrent(surface)
            val frames = FrameRenderer(YuvInfo.from(format)).also { renderer = it }
            frames.setLut(lut)

            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            fboTex = ids[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTex)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
            )
            GLES30.glGenFramebuffers(1, ids, 0)
            fbo = ids[0]
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, fboTex, 0,
            )
            check(
                GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE
            ) { "Framebuffer incomplete" }

            val dec = MediaCodec.createByCodecName(decoderName).also { decoder = it }
            dec.configure(format, frames.surface, null, 0)
            dec.start()

            val target = if (durationUs > 0) {
                timeUs.coerceIn(0L, max(0L, durationUs - 100_000L))
            } else max(0L, timeUs)
            extractor.seekTo(target, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            val deadline = SystemClock.elapsedRealtime() + 20_000
            while (true) {
                if (SystemClock.elapsedRealtime() > deadline) throw TimeoutException("Frame decode timed out")
                if (!inputDone) {
                    val idx = dec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val buf = dec.getInputBuffer(idx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            dec.queueInputBuffer(idx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(idx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val d = dec.dequeueOutputBuffer(info, 10_000)
                if (d == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    frames.setYuvInfo(YuvInfo.from(dec.outputFormat, format))
                    continue
                }
                if (d < 0) continue
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                val hasFrame = !eos || info.size > 0
                if (hasFrame && (info.presentationTimeUs >= target || eos)) {
                    dec.releaseOutputBuffer(d, true)
                    frames.awaitNewImage()
                    break
                }
                dec.releaseOutputBuffer(d, false)
                if (eos) throw IOException("Reached the end of the video before the requested frame")
            }

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
            frames.draw(width, height, flipY = true)
            val pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
            FrameRenderer.checkGl("readPixels")
            pixels.rewind()
            val raw = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            raw.copyPixelsFromBuffer(pixels)

            val scale = if (maxDim != null && max(width, height) > maxDim) {
                maxDim.toFloat() / max(width, height)
            } else 1f
            if (scale == 1f && rotation == 0) return Frame(raw, durationUs, frames.samplesRawYuv)
            val m = Matrix().apply {
                postScale(scale, scale)
                postRotate(rotation.toFloat())
            }
            val out = Bitmap.createBitmap(raw, 0, 0, width, height, m, true)
            if (out !== raw) raw.recycle()
            return Frame(out, durationUs, frames.samplesRawYuv)
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            if (fbo != 0) runCatching {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
                GLES30.glDeleteTextures(1, intArrayOf(fboTex), 0)
            }
            runCatching { renderer?.close() }
            pbuffer?.let { runCatching { egl?.releaseSurface(it) } }
            runCatching { egl?.release() }
            extractor.release()
        }
    }
}
