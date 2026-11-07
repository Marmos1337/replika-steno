package ru.replika.steno.core

import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Gain for speech detection and voice embeddings on quiet recordings. Measured on the test set
 * (tools/preproc_ab.py): on a recording 24 dB quieter the speaker-time accuracy fell to 89.8 % (RU) /
 * 69.5 % (EN) and came back to 99.9 % / 99.5 % with this gain; normal-level and noisy recordings are
 * left untouched (gain 1), and the recognizer always gets the original audio. A plain high-pass plus
 * normalisation of everything, and GTCRN denoising, made Russian WER worse, so they are not used.
 */
object Loudness {
    private const val FRAME = 480 // 30 ms at 16 kHz
    const val QUIET_DB = -28f
    const val TARGET_DB = -20f
    const val MAX_GAIN_DB = 30f

    /** Level of the loudest 10 % of 30 ms frames, dBFS. */
    fun loudLevelDb(samples: FloatArray): Float {
        val frames = samples.size / FRAME
        if (frames == 0) return -120f
        val rms = FloatArray(frames) { f ->
            var acc = 0.0
            for (i in f * FRAME until (f + 1) * FRAME) acc += samples[i].toDouble() * samples[i]
            sqrt(acc / FRAME).toFloat()
        }
        rms.sort()
        val top = maxOf(1, frames / 10)
        var sum = 0.0
        for (i in frames - top until frames) sum += rms[i]
        return (20 * log10(sum / top + 1e-9)).toFloat()
    }

    /** 1 for normal recordings; for a quiet one, the gain that lifts it towards [TARGET_DB]. */
    fun quietGain(samples: FloatArray): Float {
        val level = loudLevelDb(samples)
        if (level >= QUIET_DB) return 1f
        return 10f.pow(min(TARGET_DB - level, MAX_GAIN_DB) / 20f)
    }

    /** Copy of [from, to) with [gain] applied and clipped to [-1, 1]. */
    fun slice(samples: FloatArray, from: Int, to: Int, gain: Float): FloatArray =
        if (gain == 1f) samples.copyOfRange(from, to)
        else FloatArray(to - from) { (samples[from + it] * gain).coerceIn(-1f, 1f) }
}
