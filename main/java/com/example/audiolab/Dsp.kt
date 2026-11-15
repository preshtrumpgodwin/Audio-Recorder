package com.example.audiolab

import kotlin.math.*

/** One RBJ peaking-EQ biquad band (Direct Form I). */
class PeakingBand(private val freq: Double, private val q: Double = 1.0, private val sampleRate: Int) {
    private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0
    private var a1 = 0.0; private var a2 = 0.0
    private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0

    @Volatile var gainDb: Double = 0.0
        set(v) { field = v; update() }

    init { update() }

    private fun update() {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * freq / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val cosW = cos(w0)
        val a0 = 1 + alpha / a
        b0 = (1 + alpha * a) / a0
        b1 = (-2 * cosW) / a0
        b2 = (1 - alpha * a) / a0
        a1 = (-2 * cosW) / a0
        a2 = (1 - alpha / a) / a0
    }

    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        return y
    }
}

/** 5-band graphic EQ: 60 Hz, 230 Hz, 910 Hz, 3.6 kHz, 14 kHz. */
class Equalizer(sampleRate: Int) {
    val frequencies = doubleArrayOf(60.0, 230.0, 910.0, 3600.0, 14000.0)
    private val bands = frequencies.map { PeakingBand(it, 1.0, sampleRate) }
    fun setGain(band: Int, db: Double) { bands[band].gainDb = db }
    fun process(x: Double): Double { var s = x; for (b in bands) s = b.process(s); return s }
}

/** Feed-forward peak compressor with attack/release smoothing and make-up gain. */
class Compressor(private val sampleRate: Int) {
    @Volatile var thresholdDb = -18.0
    @Volatile var ratio = 4.0
    @Volatile var attackMs = 10.0
    @Volatile var releaseMs = 120.0
    @Volatile var makeupDb = 6.0
    @Volatile var enabled = true

    private var envDb = -120.0

    /** @return processed sample; input/output are in -1..1 */
    fun process(x: Double): Double {
        if (!enabled) return x
        val levelDb = 20.0 * log10(max(abs(x), 1e-9))
        val coefA = exp(-1.0 / (0.001 * attackMs * sampleRate))
        val coefR = exp(-1.0 / (0.001 * releaseMs * sampleRate))
        envDb = if (levelDb > envDb) coefA * envDb + (1 - coefA) * levelDb
                else coefR * envDb + (1 - coefR) * levelDb
        val over = envDb - thresholdDb
        val reductionDb = if (over > 0) over * (1.0 - 1.0 / ratio) else 0.0
        val gain = 10.0.pow((makeupDb - reductionDb) / 20.0)
        // soft clip to keep peaks safe after make-up gain
        return tanh(x * gain)
    }
}
