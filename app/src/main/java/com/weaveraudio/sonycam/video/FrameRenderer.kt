package com.weaveraudio.sonycam.video

import android.graphics.SurfaceTexture
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.weaveraudio.sonycam.color.Lut3D
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeoutException

/** How the decoder's Y'CbCr code values map to R'G'B'. */
data class YuvInfo(val kr: Double, val kb: Double, val fullRange: Boolean, val tenBit: Boolean) {

    /** Column-major mat3 taking (Y', Cb, Cr) with Y' in 0..1 and Cb/Cr in -0.5..0.5 to R'G'B'. */
    fun matrix(): FloatArray {
        val kg = 1.0 - kr - kb
        return floatArrayOf(
            1f, 1f, 1f,
            0f, (-2.0 * kb * (1 - kb) / kg).toFloat(), (2.0 * (1 - kb)).toFloat(),
            (2.0 * (1 - kr)).toFloat(), (-2.0 * kr * (1 - kr) / kg).toFloat(), 0f,
        )
    }

    /**
     * Offset and scale for normalised samples. 10-bit buffers (P010) hold the code in the top
     * bits of 16, so they normalise by 65535, as EXT_YUV_target returns them.
     */
    fun offset(): FloatArray {
        val unit = if (tenBit) 64.0 / 65535.0 else 1.0 / 255.0
        val black = if (fullRange) 0.0 else (if (tenBit) 64.0 else 16.0) * unit
        val mid = (if (tenBit) 512.0 else 128.0) * unit
        return floatArrayOf(black.toFloat(), mid.toFloat(), mid.toFloat())
    }

    fun scale(): FloatArray {
        val unit = if (tenBit) 64.0 / 65535.0 else 1.0 / 255.0
        val max = if (tenBit) 1023.0 else 255.0
        val yRange = if (fullRange) max else (if (tenBit) 876.0 else 219.0)
        val cRange = if (fullRange) max else (if (tenBit) 896.0 else 224.0)
        return floatArrayOf(
            (1.0 / (yRange * unit)).toFloat(),
            (1.0 / (cRange * unit)).toFloat(),
            (1.0 / (cRange * unit)).toFloat(),
        )
    }

    companion object {
        /** Reads colour aspects from [primary], falling back to [secondary] for missing keys. */
        fun from(primary: MediaFormat, secondary: MediaFormat? = null): YuvInfo {
            fun int(key: String): Int? = when {
                primary.containsKey(key) -> primary.getInteger(key)
                secondary?.containsKey(key) == true -> secondary.getInteger(key)
                else -> null
            }
            val standard = int(MediaFormat.KEY_COLOR_STANDARD)
            val transfer = int(MediaFormat.KEY_COLOR_TRANSFER)
            val range = int(MediaFormat.KEY_COLOR_RANGE)
            val profile = int(MediaFormat.KEY_PROFILE)
            val mime = primary.getString(MediaFormat.KEY_MIME) ?: secondary?.getString(MediaFormat.KEY_MIME)
            val height = int(MediaFormat.KEY_HEIGHT) ?: 1080
            val hdrTransfer = transfer == MediaFormat.COLOR_TRANSFER_HLG ||
                transfer == MediaFormat.COLOR_TRANSFER_ST2084
            val (kr, kb) = when (standard) {
                MediaFormat.COLOR_STANDARD_BT2020 -> 0.2627 to 0.0593
                MediaFormat.COLOR_STANDARD_BT709 -> 0.2126 to 0.0722
                MediaFormat.COLOR_STANDARD_BT601_PAL,
                MediaFormat.COLOR_STANDARD_BT601_NTSC -> 0.299 to 0.114
                else -> when {
                    hdrTransfer -> 0.2627 to 0.0593
                    height >= 720 -> 0.2126 to 0.0722
                    else -> 0.299 to 0.114
                }
            }
            return YuvInfo(
                kr = kr,
                kb = kb,
                fullRange = range == MediaFormat.COLOR_RANGE_FULL,
                tenBit = hdrTransfer || isTenBitProfile(mime, profile),
            )
        }

        private fun isTenBitProfile(mime: String?, profile: Int?): Boolean {
            if (profile == null || mime == null) return false
            return when (mime) {
                MediaFormat.MIMETYPE_VIDEO_HEVC -> profile in setOf(
                    CodecProfileLevel.HEVCProfileMain10,
                    CodecProfileLevel.HEVCProfileMain10HDR10,
                    CodecProfileLevel.HEVCProfileMain10HDR10Plus,
                )
                MediaFormat.MIMETYPE_VIDEO_AVC -> profile in setOf(
                    CodecProfileLevel.AVCProfileHigh10,
                    CodecProfileLevel.AVCProfileHigh422,
                    CodecProfileLevel.AVCProfileHigh444,
                )
                MediaFormat.MIMETYPE_VIDEO_VP9 -> profile in setOf(
                    CodecProfileLevel.VP9Profile2, CodecProfileLevel.VP9Profile3,
                    CodecProfileLevel.VP9Profile2HDR, CodecProfileLevel.VP9Profile3HDR,
                    CodecProfileLevel.VP9Profile2HDR10Plus, CodecProfileLevel.VP9Profile3HDR10Plus,
                )
                MediaFormat.MIMETYPE_VIDEO_AV1 -> profile in setOf(
                    CodecProfileLevel.AV1ProfileMain10,
                    CodecProfileLevel.AV1ProfileMain10HDR10,
                    CodecProfileLevel.AV1ProfileMain10HDR10Plus,
                )
                else -> false
            }
        }
    }
}

/**
 * Receives decoded frames on a SurfaceTexture and draws them through the grade.
 *
 * Where the GPU supports GL_EXT_YUV_target, the decoder's Y'CbCr is sampled directly and
 * converted with the stream's own matrix at full (10-bit) precision; otherwise the driver's
 * YUV->RGB conversion is used. Must be created and used on the thread owning the GL context.
 */
class FrameRenderer(yuvInfo: YuvInfo) : AutoCloseable {
    private val textureId: Int
    val surfaceTexture: SurfaceTexture
    val surface: Surface
    val samplesRawYuv: Boolean

    private val program: Int
    private val vbo: Int
    private val stMatrix = FloatArray(16)
    private var yuv = yuvInfo
    private var lutTexture = 0
    private var lutSize = 0
    private var applyLut = false

    private val uSt: Int
    private val uFlip: Int
    private val uTex: Int
    private val uLut: Int
    private val uLutSize: Int
    private val uApplyLut: Int
    private val uYuvToRgb: Int
    private val uYuvOffset: Int
    private val uYuvScale: Int

    private val frameLock = Object()
    private var frameAvailable = false
    private val callbackThread = HandlerThread("FrameAvailable").apply { start() }

    init {
        val extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: ""
        var raw = false
        var prog = 0
        if (extensions.contains("GL_EXT_YUV_target")) {
            prog = runCatching { link(VERTEX, fragment(FragmentKind.YUV_TARGET)) }
                .onFailure { Log.w(TAG, "YUV target shader failed, falling back", it) }
                .getOrDefault(0)
            raw = prog != 0
        }
        if (prog == 0) {
            prog = runCatching { link(VERTEX, fragment(FragmentKind.EXTERNAL_ESSL3)) }
                .getOrElse { link(VERTEX, fragment(FragmentKind.EXTERNAL)) }
        }
        program = prog
        samplesRawYuv = raw
        Log.i(TAG, "Frame renderer: rawYuv=$raw")

        uSt = GLES30.glGetUniformLocation(program, "uSt")
        uFlip = GLES30.glGetUniformLocation(program, "uFlip")
        uTex = GLES30.glGetUniformLocation(program, "uTex")
        uLut = GLES30.glGetUniformLocation(program, "uLut")
        uLutSize = GLES30.glGetUniformLocation(program, "uLutSize")
        uApplyLut = GLES30.glGetUniformLocation(program, "uApplyLut")
        uYuvToRgb = GLES30.glGetUniformLocation(program, "uYuvToRgb")
        uYuvOffset = GLES30.glGetUniformLocation(program, "uYuvOffset")
        uYuvScale = GLES30.glGetUniformLocation(program, "uYuvScale")

        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        vbo = ids[0]
        val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val qb = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().put(quad).position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.size * 4, qb, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        GLES30.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        // EXT_YUV_target samples raw texels; frames are drawn 1:1 so nearest is exact.
        val filter = if (raw) GLES30.GL_NEAREST else GLES30.GL_LINEAR
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, filter)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, filter)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        checkGl("texture setup")

        surfaceTexture = SurfaceTexture(textureId)
        surfaceTexture.setOnFrameAvailableListener({
            synchronized(frameLock) {
                frameAvailable = true
                frameLock.notifyAll()
            }
        }, Handler(callbackThread.looper))
        surface = Surface(surfaceTexture)
    }

    fun setYuvInfo(info: YuvInfo) {
        yuv = info
    }

    /** Uploads [lut] as an RGBA16F 3D texture, or disables grading when null (raw signal out). */
    fun setLut(lut: Lut3D?) {
        if (lut == null) {
            applyLut = false
            return
        }
        if (lutTexture == 0) {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            lutTexture = ids[0]
        }
        val n = lut.size
        val rgba = FloatArray(n * n * n * 4)
        for (i in 0 until n * n * n) {
            rgba[i * 4] = lut.data[i * 3]
            rgba[i * 4 + 1] = lut.data[i * 3 + 1]
            rgba[i * 4 + 2] = lut.data[i * 3 + 2]
            rgba[i * 4 + 3] = 1f
        }
        val buf = ByteBuffer.allocateDirect(rgba.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().put(rgba).position(0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGBA16F, n, n, n, 0,
            GLES30.GL_RGBA, GLES30.GL_FLOAT, buf,
        )
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        checkGl("LUT upload")
        lutSize = n
        applyLut = true
    }

    /** Blocks until the decoder has delivered a new frame, then latches it. */
    fun awaitNewImage(timeoutMs: Long = 5000) {
        synchronized(frameLock) {
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (!frameAvailable) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) throw TimeoutException("Timed out waiting for a decoded frame")
                frameLock.wait(remaining)
            }
            frameAvailable = false
        }
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(stMatrix)
    }

    /** Draws the latched frame into the current framebuffer. [flipY] gives top-down readback. */
    fun draw(width: Int, height: Int, flipY: Boolean) {
        GLES30.glViewport(0, 0, width, height)
        GLES30.glUseProgram(program)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glUniform1i(uTex, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, if (applyLut) lutTexture else 0)
        GLES30.glUniform1i(uLut, 1)
        GLES30.glUniform1f(uLutSize, lutSize.toFloat().coerceAtLeast(2f))
        GLES30.glUniform1i(uApplyLut, if (applyLut) 1 else 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)

        GLES30.glUniformMatrix4fv(uSt, 1, false, stMatrix, 0)
        GLES30.glUniform1f(uFlip, if (flipY) -1f else 1f)
        if (samplesRawYuv) {
            GLES30.glUniformMatrix3fv(uYuvToRgb, 1, false, yuv.matrix(), 0)
            GLES30.glUniform3fv(uYuvOffset, 1, yuv.offset(), 0)
            GLES30.glUniform3fv(uYuvScale, 1, yuv.scale(), 0)
        }

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        checkGl("draw")
    }

    override fun close() {
        surface.release()
        surfaceTexture.release()
        callbackThread.quitSafely()
        GLES30.glDeleteTextures(2, intArrayOf(textureId, lutTexture), 0)
        GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0)
        GLES30.glDeleteProgram(program)
    }

    private enum class FragmentKind { YUV_TARGET, EXTERNAL_ESSL3, EXTERNAL }

    companion object {
        private const val TAG = "FrameRenderer"

        private const val VERTEX = """#version 300 es
layout(location = 0) in vec2 aPos;
uniform mat4 uSt;
uniform float uFlip;
out vec2 vTex;
void main() {
  vec2 uv = aPos * 0.5 + 0.5;
  vTex = (uSt * vec4(uv, 0.0, 1.0)).xy;
  gl_Position = vec4(aPos.x, aPos.y * uFlip, 0.0, 1.0);
}
"""

        private fun fragment(kind: FragmentKind): String {
            val header = when (kind) {
                FragmentKind.YUV_TARGET -> """#version 300 es
#extension GL_OES_EGL_image_external : require
#extension GL_EXT_YUV_target : require
precision highp float;
uniform __samplerExternal2DY2YEXT uTex;
uniform mat3 uYuvToRgb;
uniform vec3 uYuvOffset;
uniform vec3 uYuvScale;
vec3 sampleSignal(vec2 tc) {
  vec3 yuv = texture(uTex, tc).xyz;
  return clamp(uYuvToRgb * ((yuv - uYuvOffset) * uYuvScale), 0.0, 1.0);
}
"""
                FragmentKind.EXTERNAL_ESSL3 -> """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uTex;
vec3 sampleSignal(vec2 tc) { return clamp(texture(uTex, tc).rgb, 0.0, 1.0); }
"""
                FragmentKind.EXTERNAL -> """#version 300 es
#extension GL_OES_EGL_image_external : require
precision highp float;
uniform samplerExternalOES uTex;
vec3 sampleSignal(vec2 tc) { return clamp(texture(uTex, tc).rgb, 0.0, 1.0); }
"""
            }
            return header + """
uniform highp sampler3D uLut;
uniform float uLutSize;
uniform int uApplyLut;
in vec2 vTex;
out vec4 outColor;
void main() {
  vec3 rgb = sampleSignal(vTex);
  if (uApplyLut == 1) {
    vec3 coord = rgb * ((uLutSize - 1.0) / uLutSize) + 0.5 / uLutSize;
    rgb = texture(uLut, coord).rgb;
  }
  outColor = vec4(rgb, 1.0);
}
"""
        }

        private fun compile(type: Int, source: String): Int {
            val shader = GLES30.glCreateShader(type)
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = GLES30.glGetShaderInfoLog(shader)
                GLES30.glDeleteShader(shader)
                throw IllegalStateException("Shader compile failed: $log")
            }
            return shader
        }

        private fun link(vertex: String, fragment: String): Int {
            val vs = compile(GLES30.GL_VERTEX_SHADER, vertex)
            val fs = try {
                compile(GLES30.GL_FRAGMENT_SHADER, fragment)
            } catch (t: Throwable) {
                GLES30.glDeleteShader(vs)
                throw t
            }
            val program = GLES30.glCreateProgram()
            GLES30.glAttachShader(program, vs)
            GLES30.glAttachShader(program, fs)
            GLES30.glLinkProgram(program)
            GLES30.glDeleteShader(vs)
            GLES30.glDeleteShader(fs)
            val ok = IntArray(1)
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = GLES30.glGetProgramInfoLog(program)
                GLES30.glDeleteProgram(program)
                throw IllegalStateException("Program link failed: $log")
            }
            return program
        }

        fun checkGl(op: String) {
            val err = GLES30.glGetError()
            check(err == GLES30.GL_NO_ERROR) { "$op: GL error 0x${Integer.toHexString(err)}" }
        }
    }
}
