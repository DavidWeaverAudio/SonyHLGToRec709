package com.weaveraudio.sonycam.video

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.resume

/**
 * Converts a clip's audio to AAC with Media3. Sony bodies record big-endian 16/24-bit LPCM,
 * which MP4 muxers on Android can't take; Media3 normalises and encodes it reliably.
 */
object AudioTrackExporter {

    /** @return null on success, otherwise a short reason the audio could not be converted. */
    @OptIn(UnstableApi::class)
    suspend fun exportAac(context: Context, input: Uri, output: File, channelCount: Int): String? =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val processors = if (channelCount > 2) {
                    // Keep channels 1+2 (e.g. the XLR inputs on 4-channel bodies) as stereo.
                    val coefficients = FloatArray(channelCount * 2)
                    coefficients[0 * 2 + 0] = 1f
                    coefficients[1 * 2 + 1] = 1f
                    listOf(
                        ChannelMixingAudioProcessor().apply {
                            putChannelMixingMatrix(
                                ChannelMixingMatrix(channelCount, 2, coefficients)
                            )
                        }
                    )
                } else {
                    emptyList()
                }
                val item = EditedMediaItem.Builder(MediaItem.fromUri(input))
                    .setRemoveVideo(true)
                    .setEffects(Effects(processors, emptyList()))
                    .build()
                val transformer = Transformer.Builder(context)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (cont.isActive) cont.resume(null)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            if (cont.isActive) {
                                cont.resume(exportException.message ?: "audio conversion failed")
                            }
                        }
                    })
                    .build()
                cont.invokeOnCancellation {
                    Handler(Looper.getMainLooper()).post { transformer.cancel() }
                }
                transformer.start(item, output.absolutePath)
            }
        }
}

/** Streams the samples of an already-encoded audio file into a muxer, interleaved by time. */
class AudioSampleSource(file: File) : Closeable {
    private val extractor = MediaExtractor()
    val format: MediaFormat
    private val buffer: ByteBuffer
    private val info = MediaCodec.BufferInfo()
    private var done = false

    init {
        extractor.setDataSource(file.absolutePath)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IllegalStateException("Converted audio has no audio track")
        extractor.selectTrack(track)
        format = extractor.getTrackFormat(track)
        val maxSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
        } else 0
        buffer = ByteBuffer.allocateDirect(maxOf(maxSize, 256 * 1024))
    }

    fun writeUntil(muxer: MediaMuxer, track: Int, ptsUs: Long) {
        while (!done) {
            if (extractor.sampleTrackIndex < 0) { done = true; return }
            if (extractor.sampleTime > ptsUs) return
            writeOne(muxer, track)
        }
    }

    fun writeAll(muxer: MediaMuxer, track: Int) {
        while (!done) writeOne(muxer, track)
    }

    private fun writeOne(muxer: MediaMuxer, track: Int) {
        if (extractor.sampleTrackIndex < 0) { done = true; return }
        val pts = extractor.sampleTime
        // AAC encoder-priming frames sit before 0 behind an edit list, which MediaMuxer can't
        // express; drop them (the real audio keeps its timestamps, so sync is unaffected).
        if (pts >= 0) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) { done = true; return }
            val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                MediaCodec.BUFFER_FLAG_KEY_FRAME
            } else 0
            info.set(0, size, pts, flags)
            muxer.writeSampleData(track, buffer, info)
        }
        if (!extractor.advance()) done = true
    }

    override fun close() {
        extractor.release()
    }
}
