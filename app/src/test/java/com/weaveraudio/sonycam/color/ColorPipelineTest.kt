package com.weaveraudio.sonycam.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class ColorPipelineTest {

    private fun roundTrip(curve: TransferCurve, from: Double, to: Double) {
        var x = from
        while (x <= to) {
            val back = curve.toSignal(curve.toLinear(x))
            assertEquals("${curve.javaClass.simpleName} at $x", x, back, 1e-9)
            x += 0.001
        }
    }

    @Test fun curvesInvert() {
        roundTrip(Hlg, 0.0, 1.0)
        roundTrip(Rec709, 0.0, 1.0)
        roundTrip(SLog3, 0.093, 1.0) // S-Log3 code 95 = linear 0
    }

    @Test fun hlgLandmarks() {
        assertEquals(1.0 / 12.0, Hlg.toLinear(0.5), 1e-12)
        assertEquals(1.0, Hlg.toLinear(1.0), 1e-6)
        assertEquals(0.75, Hlg.toSignal(0.26496), 1e-4) // BT.2408 reference white
    }

    @Test fun sLog3Landmarks() {
        // 18% grey = code 420 (41.1%), 90% white = code 598 (58.4%) per Sony's spec.
        assertEquals(420.0 / 1023.0, SLog3.toSignal(0.18), 1e-9)
        assertEquals(598.0 / 1023.0, SLog3.toSignal(0.90), 0.6 / 1023.0)
    }

    private fun builtIn(v: Double, ev: Float = 0f): DoubleArray {
        val out = DoubleArray(3)
        ColorPipeline.gradePixel(GradeSettings(exposureEv = ev), null, v, v, v, out)
        return out
    }

    @Test fun builtInGreyScale() {
        val black = builtIn(0.0)
        val grey = builtIn(HlgToRec709.HLG_GREY_SIGNAL)
        val white = builtIn(0.75)
        val peak = builtIn(1.0)
        assertEquals(0.0, black[0], 1e-9)
        assertEquals(0.409, grey[0], 0.002) // standard Rec.709 18% grey
        assertTrue("ref white ${white[0]}", white[0] in 0.90..0.94)
        assertTrue("peak ${peak[0]}", peak[0] > 0.995)
        // Neutral in, neutral out.
        for (c in 1..2) assertEquals(grey[0], grey[c], 1e-6)
        // Monotonic ramp.
        var prev = -1.0
        for (i in 0..1000) {
            val y = builtIn(i / 1000.0)[0]
            assertTrue("not monotonic at $i", y >= prev - 1e-12)
            prev = y
        }
    }

    @Test fun exposureBeforeIsALinearGainOnScene() {
        val grey = builtIn(HlgToRec709.HLG_GREY_SIGNAL, ev = -1f)
        // 18% grey one stop down = 9% linear -> 709 signal 0.2886 (still below the knee).
        assertEquals(Rec709.toSignal(0.09), grey[0], 0.002)
        // Pulling down recovers highlights that were rolling off.
        val white0 = builtIn(1.0)[0]
        val whiteDown = builtIn(1.0, ev = -2f)[0]
        assertTrue(whiteDown < white0 - 0.05)
    }

    @Test fun exposureAfterScalesOutputLinearLight() {
        val out = DoubleArray(3)
        val s = GradeSettings(exposureEv = -1f, exposureStage = ExposureStage.AFTER)
        ColorPipeline.gradePixel(s, null, 1.0, 1.0, 1.0, out)
        assertEquals(Rec709.toSignal(Rec709.toLinear(builtIn(1.0)[0]) * 0.5), out[0], 1e-9)
    }

    @Test fun bt2020PrimariesLandInGamut() {
        val out = DoubleArray(3)
        val primaries = listOf(
            doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0),
            doubleArrayOf(0.0, 1.0, 1.0), doubleArrayOf(1.0, 0.0, 1.0), doubleArrayOf(1.0, 1.0, 0.0),
        )
        for ((i, p) in primaries.withIndex()) {
            ColorPipeline.gradePixel(GradeSettings(), null, p[0], p[1], p[2], out)
            out.forEach { assertTrue("primary $i -> ${out.toList()}", it in 0.0..1.0) }
        }
        // Pure 2020 green should stay clearly green, not clip to yellow/cyan.
        ColorPipeline.gradePixel(GradeSettings(), null, 0.0, 0.75, 0.0, out)
        assertTrue(out[1] > out[0] + 0.3 && out[1] > out[2] + 0.3)
    }

    @Test fun bakedLutMatchesExactMath() {
        val settings = GradeSettings(exposureEv = -0.7f)
        val lut = ColorPipeline.bake(settings, null, ColorPipeline.EXPORT_SIZE)
        val rnd = Random(42)
        val exact = DoubleArray(3)
        val errors = ArrayList<Double>()
        repeat(20_000) {
            val r = rnd.nextDouble(); val g = rnd.nextDouble(); val b = rnd.nextDouble()
            ColorPipeline.gradePixel(settings, null, r, g, b, exact)
            val px = floatArrayOf(r.toFloat(), g.toFloat(), b.toFloat())
            lut.applyInPlace(px, 1)
            errors += (0..2).maxOf { abs(px[it] - exact[it]) }
        }
        errors.sort()
        val p99 = errors[(errors.size * 0.99).toInt()]
        assertTrue("p99 baked error $p99", p99 < 1.0 / 255)
        // Only extreme out-of-709 colours (gamut-compression kink) exceed 1/255.
        assertTrue("max baked error ${errors.last()}", errors.last() < 5.0 / 255)

        var neutral = 0.0
        for (i in 0..1000) {
            val v = i / 1000.0
            ColorPipeline.gradePixel(settings, null, v, v, v, exact)
            val px = floatArrayOf(v.toFloat(), v.toFloat(), v.toFloat())
            lut.applyInPlace(px, 1)
            neutral = maxOf(neutral, abs(px[0] - exact[0]))
        }
        assertTrue("neutral axis error $neutral", neutral < 0.5 / 255)
    }

    @Test fun identityCubeIsIdentity() {
        val text = buildString {
            appendLine("TITLE \"identity\"")
            appendLine("# comment")
            appendLine("LUT_3D_SIZE 2")
            for (b in 0..1) for (g in 0..1) for (r in 0..1) appendLine("$r.0 $g.0 $b.0")
        }
        val cube = CubeLut.parse(text.reader())
        assertEquals("identity", cube.title)
        val out = DoubleArray(3)
        cube.apply(0.25, 0.5, 0.75, out)
        assertEquals(0.25, out[0], 1e-6)
        assertEquals(0.5, out[1], 1e-6)
        assertEquals(0.75, out[2], 1e-6)
    }

    @Test fun cubeOrderingIsRedFastest() {
        // A LUT that outputs only the blue input in the red channel: proves axis order.
        val text = buildString {
            appendLine("LUT_3D_SIZE 2")
            for (b in 0..1) for (g in 0..1) for (r in 0..1) appendLine("$b 0 0")
        }
        val cube = CubeLut.parse(text.reader())
        val out = DoubleArray(3)
        cube.apply(1.0, 0.0, 0.0, out); assertEquals(0.0, out[0], 1e-9)
        cube.apply(0.0, 0.0, 1.0, out); assertEquals(1.0, out[0], 1e-9)
    }

    @Test fun cubeDomainAndShaper() {
        val text = buildString {
            appendLine("LUT_1D_SIZE 2")
            appendLine("LUT_1D_INPUT_RANGE 0 2")
            appendLine("0 0 0")
            appendLine("1 1 1")
            appendLine("LUT_3D_SIZE 2")
            for (b in 0..1) for (g in 0..1) for (r in 0..1) appendLine("$r $g $b")
        }
        val cube = CubeLut.parse(text.reader())
        val out = DoubleArray(3)
        cube.apply(1.0, 2.0, 0.0, out) // shaper halves, 3D is identity
        assertEquals(0.5, out[0], 1e-9)
        assertEquals(1.0, out[1], 1e-9)
    }

    @Test fun cubeRejectsWrongCount() {
        try {
            CubeLut.parse("LUT_3D_SIZE 2\n0 0 0\n1 1 1\n".reader())
            fail("expected ParseException")
        } catch (e: CubeLut.ParseException) {
            assertTrue(e.message!!.contains("Expected 8"))
        }
    }

    @Test fun lutModeAppliesExposureInLutInputCurve() {
        val text = buildString {
            appendLine("LUT_3D_SIZE 2")
            for (b in 0..1) for (g in 0..1) for (r in 0..1) appendLine("$r $g $b")
        }
        val cube = CubeLut.parse(text.reader())
        val out = DoubleArray(3)
        val s = GradeSettings(mode = TransformMode.LUT, exposureEv = -1f, lutInput = LutInput.SLOG3)
        val grey = SLog3.toSignal(0.18)
        ColorPipeline.gradePixel(s, cube, grey, grey, grey, out)
        assertEquals(SLog3.toSignal(0.09), out[0], 1e-6)
    }
}
