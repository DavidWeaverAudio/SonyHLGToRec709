package com.weaveraudio.sonycam.color

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** A transfer curve between an encoded signal (0..1) and scene-linear light. */
interface TransferCurve {
    fun toLinear(signal: Double): Double
    fun toSignal(linear: Double): Double
}

/**
 * ITU-R BT.2100 Hybrid Log-Gamma OETF. Scene-linear is normalised so that the HLG signal
 * 1.0 maps to linear 1.0 (i.e. the inverse OETF, with no display OOTF applied).
 */
object Hlg : TransferCurve {
    private const val A = 0.17883277
    private const val B = 0.28466892 // 1 - 4A
    private const val C = 0.55991073 // 0.5 - A * ln(4A)

    override fun toLinear(signal: Double): Double {
        val e = max(signal, 0.0)
        return if (e <= 0.5) e * e / 3.0 else (exp((e - C) / A) + B) / 12.0
    }

    override fun toSignal(linear: Double): Double {
        val l = max(linear, 0.0)
        return if (l <= 1.0 / 12.0) sqrt(3.0 * l) else A * ln(12.0 * l - B) + C
    }
}

/** ITU-R BT.709 camera OETF, with the precise constants from BT.2020 so it inverts exactly. */
object Rec709 : TransferCurve {
    private const val ALPHA = 1.09929682680944
    private const val BETA = 0.018053968510807

    override fun toLinear(signal: Double): Double =
        if (signal < 4.5 * BETA) signal / 4.5 else ((signal + ALPHA - 1.0) / ALPHA).pow(1.0 / 0.45)

    override fun toSignal(linear: Double): Double =
        if (linear < BETA) 4.5 * linear else ALPHA * linear.pow(0.45) - (ALPHA - 1.0)
}

/** Sony S-Log3 (as published in Sony's S-Log3 technical summary). */
object SLog3 : TransferCurve {
    private const val BREAK_CODE = 171.2102946929

    override fun toLinear(signal: Double): Double {
        val code = signal * 1023.0
        return if (code >= BREAK_CODE) {
            10.0.pow((code - 420.0) / 261.5) * (0.18 + 0.01) - 0.01
        } else {
            (code - 95.0) * 0.01125 / (BREAK_CODE - 95.0)
        }
    }

    override fun toSignal(linear: Double): Double =
        if (linear >= 0.01125) {
            (420.0 + log10((linear + 0.01) / (0.18 + 0.01)) * 261.5) / 1023.0
        } else {
            (linear * (BREAK_CODE - 95.0) / 0.01125 + 95.0) / 1023.0
        }
}
