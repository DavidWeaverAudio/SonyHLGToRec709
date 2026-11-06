package com.weaveraudio.sonycam.video

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.weaveraudio.sonycam.MediaSaver
import com.weaveraudio.sonycam.color.Lut3D
import com.weaveraudio.sonycam.color.VideoCodec
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class UnsupportedVideoException(message: String) : IOException(message)

data class VideoExportResult(val uri: Uri, val note: String?)

object VideoGrader {
    private const val TAG = "VideoGrader"
    private const val TIMEOUT_US = 10_000L
    private const val STALL_MS = 15_000L

    /** Grades [input] and saves it as an MP4 in Movies/HLG709. */
    suspend fun export(
        context: Context,
        input: Uri,
        lut: Lut3D,
        codec: VideoCodec,
        onProgress: (Float) -> Unit,
    ): VideoExportResult {
        val name = MediaSaver.outputNameFor(context, input, isVideo = true)
        val audioChannels = probeAudioChannels(context, input)
        var note: String? = null
        var audioFile: File? = null
        try {
            if (audioChannels > 0) {
                val file = File(context.cacheDir, "audio_${System.nanoTime()}.mp4")
                val error = AudioTrackExporter.exportAac(context, input, file, audioChannels)
                if (error == null && file.length() > 0) {
                    audioFile = file
                } else {
                    file.delete()
                    note = "Audio could not be converted and was left out ($error)"
                    Log.w(TAG, "Audio export failed: $error")
                }
            }
            onProgress(0.05f)

            val target = MediaSaver.createPending(context, name, "video/mp4", isVideo = true)
            try {
                context.contentResolver.openFileDescriptor(target, "rw")!!.use { pfd ->
                    val job = currentCoroutineContext()[Job]
                    val usedMime = Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { gl ->
                        withContext(gl) {
                            transcode(
                                context, input, pfd.fileDescriptor, lut, codec, audioFile,
                                isCancelled = { job?.isActive == false },
                                onProgress = { onProgress(0.05f + 0.95f * it) },
                            )
                        }
                    }
                    val wanted = if (codec == VideoCodec.HEVC) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
                    if (usedMime != wanted) {
                        val used = if (usedMime == MediaFormat.MIMETYPE_VIDEO_HEVC) "HEVC" else "H.264"
                        note = listOfNotNull(note, "saved as $used: no ${codec.label} encoder for this size").joinToString("; ")
                    }
                }
                MediaSaver.publish(context, target)
                return VideoExportResult(target, note)
            } catch (t: Throwable) {
                MediaSaver.discard(context, target)
                throw t
            }
        } finally {
            audioFile?.delete()
        }
    }

    private fun probeAudioChannels(context: Context, uri: Uri): Int {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?.let { if (it.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) it.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2 }
                ?: 0
        } catch (e: Exception) {
            0
        } finally {
            extractor.release()
        }
    }

    /**
     * Blocking decode -> GPU grade -> encode -> mux. Must run on a thread with no other EGL
     * context. Pixels keep their stored orientation; rotation is carried as a container hint.
     * Returns the video MIME type actually encoded (HEVC may fall back to H.264).
     */
    fun transcode(
        context: Context,
        input: Uri,
        output: FileDescriptor,
        lut: Lut3D,
        codecPref: VideoCodec,
        audioFile: File?,
        isCancelled: () -> Boolean,
        onProgress: (Float) -> Unit,
    ): String {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var egl: EglCore? = null
        var renderer: FrameRenderer? = null
        var muxer: MediaMuxer? = null
        var eglSurface: android.opengl.EGLSurface? = null
        var muxerStarted = false
        val audio = audioFile?.let { AudioSampleSource(it) }
        try {
            extractor.setDataSource(context, input, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: throw IOException("No video track found")
            extractor.selectTrack(track)
            val inFormat = extractor.getTrackFormat(track)
            val width = inFormat.getInteger(MediaFormat.KEY_WIDTH)
            val height = inFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val rotation = if (inFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                inFormat.getInteger(MediaFormat.KEY_ROTATION)
            } else 0
            val durationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) {
                inFormat.getLong(MediaFormat.KEY_DURATION)
            } else 0L
            val fps = frameRate(inFormat)
            val decoderName = findDecoder(inFormat)

            val (enc, outMime) = createEncoder(codecPref, width, height, fps)
            encoder = enc
            Log.i(TAG, "Transcode ${width}x$height@$fps rot=$rotation -> $outMime via ${enc.name}, decoder $decoderName")
            val encoderSurface = enc.createInputSurface()
            enc.start()

            val core = EglCore(recordable = true).also { egl = it }
            val surface = core.createWindowSurface(encoderSurface).also { eglSurface = it }
            core.makeCurrent(surface)
            val frames = FrameRenderer(YuvInfo.from(inFormat)).also { renderer = it }
            frames.setLut(lut)

            val dec = MediaCodec.createByCodecName(decoderName).also { decoder = it }
            dec.configure(inFormat, frames.surface, null, 0)
            dec.start()

            val mux = MediaMuxer(output, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { muxer = it }
            if (rotation in setOf(0, 90, 180, 270)) mux.setOrientationHint(rotation)
            readLocation(context, input)?.let { (lat, lon) -> runCatching { mux.setLocation(lat, lon) } }

            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var decoderDone = false
            var encoderDone = false
            var videoTrack = -1
            var audioTrack = -1
            var lastActivity = SystemClock.elapsedRealtime()

            while (!encoderDone) {
                if (isCancelled()) throw CancellationException("Export cancelled")
                if (SystemClock.elapsedRealtime() - lastActivity > STALL_MS) {
                    throw TimeoutException("Video pipeline stalled")
                }

                if (!inputDone) {
                    val idx = dec.dequeueInputBuffer(TIMEOUT_US)
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

                var encoderOutputAvailable = true
                var decoderOutputAvailable = !decoderDone
                while (encoderOutputAvailable || decoderOutputAvailable) {
                    val e = enc.dequeueOutputBuffer(encInfo, 0)
                    when {
                        e == MediaCodec.INFO_TRY_AGAIN_LATER -> encoderOutputAvailable = false
                        e == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted) { "Encoder format changed twice" }
                            videoTrack = mux.addTrack(enc.outputFormat)
                            if (audio != null) audioTrack = mux.addTrack(audio.format)
                            mux.start()
                            muxerStarted = true
                        }
                        e >= 0 -> {
                            lastActivity = SystemClock.elapsedRealtime()
                            val data = enc.getOutputBuffer(e)!!
                            if (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) encInfo.size = 0
                            if (encInfo.size > 0) {
                                check(muxerStarted) { "Encoder produced data before its format" }
                                data.position(encInfo.offset)
                                data.limit(encInfo.offset + encInfo.size)
                                mux.writeSampleData(videoTrack, data, encInfo)
                                audio?.writeUntil(mux, audioTrack, encInfo.presentationTimeUs)
                                if (durationUs > 0) {
                                    onProgress((encInfo.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                                }
                            }
                            enc.releaseOutputBuffer(e, false)
                            if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                encoderDone = true
                            }
                        }
                    }
                    if (encoderDone) break
                    if (e != MediaCodec.INFO_TRY_AGAIN_LATER) continue

                    if (!decoderDone) {
                        val d = dec.dequeueOutputBuffer(decInfo, TIMEOUT_US)
                        when {
                            d == MediaCodec.INFO_TRY_AGAIN_LATER -> decoderOutputAvailable = false
                            d == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                frames.setYuvInfo(YuvInfo.from(dec.outputFormat, inFormat))
                            }
                            d >= 0 -> {
                                lastActivity = SystemClock.elapsedRealtime()
                                val eos = decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                val render = !eos || decInfo.size > 0
                                dec.releaseOutputBuffer(d, render)
                                if (render) {
                                    frames.awaitNewImage()
                                    frames.draw(width, height, flipY = false)
                                    core.setPresentationTime(surface, decInfo.presentationTimeUs * 1000)
                                    core.swapBuffers(surface)
                                }
                                if (eos) {
                                    enc.signalEndOfInputStream()
                                    decoderDone = true
                                    decoderOutputAvailable = false
                                }
                            }
                        }
                    } else {
                        decoderOutputAvailable = false
                    }
                }
            }

            check(muxerStarted) { "No video frames were produced" }
            audio?.writeAll(mux, audioTrack)
            mux.stop()
            muxerStarted = false
            onProgress(1f)
            return outMime
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { renderer?.close() }
            eglSurface?.let { runCatching { egl?.releaseSurface(it) } }
            runCatching { egl?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { audio?.close() }
            extractor.release()
        }
    }

    fun frameRate(format: MediaFormat): Float =
        if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            runCatching { format.getNumber(MediaFormat.KEY_FRAME_RATE)!!.toFloat() }.getOrDefault(30f)
                .takeIf { it in 1f..480f } ?: 30f
        } else 30f

    /** Finds a decoder for the stream or explains why the phone can't play it. */
    fun findDecoder(format: MediaFormat): String {
        val lookup = MediaFormat(format).apply { removeKey(MediaFormat.KEY_FRAME_RATE) }
        MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(lookup)?.let { return it }
        val mime = format.getString(MediaFormat.KEY_MIME)
        val profile = if (format.containsKey(MediaFormat.KEY_PROFILE)) format.getInteger(MediaFormat.KEY_PROFILE) else -1
        val detail = when {
            mime == MediaFormat.MIMETYPE_VIDEO_AVC &&
                profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh422 ->
                "H.264 4:2:2 10-bit (XAVC S 4:2:2). Phones can't decode this; record XAVC HS " +
                    "4:2:0 10-bit (HEVC) for phone grading."
            mime == MediaFormat.MIMETYPE_VIDEO_AVC &&
                profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10 ->
                "H.264 10-bit. Most phones can't decode this; record XAVC HS 4:2:0 10-bit (HEVC)."
            mime == MediaFormat.MIMETYPE_VIDEO_HEVC &&
                profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ->
                "this HEVC 10-bit stream (likely 4:2:2 or above the phone's size/level limits). " +
                    "Use XAVC HS 4:2:0 10-bit."
            else -> "$mime (profile $profile)"
        }
        throw UnsupportedVideoException("This phone has no decoder for $detail")
    }

    private fun createEncoder(pref: VideoCodec, width: Int, height: Int, fps: Float): Pair<MediaCodec, String> {
        val mimes = when (pref) {
            VideoCodec.HEVC -> listOf(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC)
            VideoCodec.AVC -> listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC)
        }
        val rates = listOf(fps, min(fps, 60f), 30f).distinct()
        val errors = mutableListOf<String>()
        val all = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        for (mime in mimes) {
            val candidates = all.filter { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }.sortedByDescending { it.isHardwareAccelerated }
            for (info in candidates) {
                val caps = info.getCapabilitiesForType(mime)
                val video = caps.videoCapabilities ?: continue
                if (!video.isSizeSupported(width, height)) continue
                for (rate in rates) {
                    val format = MediaFormat.createVideoFormat(mime, width, height).apply {
                        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                        val bpp = if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) 0.12 else 0.2
                        val target = (width.toDouble() * height * rate * bpp).toInt()
                            .coerceIn(8_000_000, 120_000_000)
                        setInteger(MediaFormat.KEY_BIT_RATE, video.bitrateRange.clamp(target))
                        setInteger(MediaFormat.KEY_FRAME_RATE, max(1, rate.roundToInt()))
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                        setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                        setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                        setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                        if (caps.encoderCapabilities.isBitrateModeSupported(
                                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                            )
                        ) {
                            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                        }
                    }
                    val codec = MediaCodec.createByCodecName(info.name)
                    try {
                        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                        return codec to mime
                    } catch (e: Exception) {
                        codec.release()
                        errors += "${info.name}@${rate.roundToInt()}fps: ${e.message}"
                    }
                }
            }
        }
        throw UnsupportedVideoException(
            "No video encoder on this phone accepts ${width}x$height. ${errors.joinToString("; ")}"
        )
    }

    private fun readLocation(context: Context, uri: Uri): Pair<Float, Float>? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val loc = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION) ?: return null
            val m = Regex("([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)").find(loc) ?: return null
            m.groupValues[1].toFloat() to m.groupValues[2].toFloat()
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
