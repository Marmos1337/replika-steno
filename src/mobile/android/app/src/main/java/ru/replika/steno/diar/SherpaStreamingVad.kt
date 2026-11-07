package ru.replika.steno.diar

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import ru.replika.steno.asr.ModelAssets
import ru.replika.steno.core.live.StreamingVad
import ru.replika.steno.core.live.VadSegment

/**
 * Silero VAD for the live mode: its own instance (the offline diarizer's VAD may be busy with an
 * import), same threshold, slightly longer pause to close a phrase and shorter max phrase, so live
 * lines arrive at least every [MAX_SPEECH_S]. Fed in exact [WINDOW]-sample steps; the remainder is
 * carried to the next call.
 */
class SherpaStreamingVad(assets: AssetManager) : StreamingVad {
    private val vad = Vad(
        assets,
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = ModelAssets.VAD,
                threshold = 0.5f,
                minSilenceDuration = 0.3f,
                minSpeechDuration = 0.25f,
                windowSize = WINDOW,
                maxSpeechDuration = MAX_SPEECH_S,
            ),
            sampleRate = 16_000,
            numThreads = 1,
        ),
    )
    private val carry = FloatArray(WINDOW)
    private var carried = 0

    override val maxSpeechMs: Long = (MAX_SPEECH_S * 1000).toLong()

    override fun accept(samples: FloatArray) {
        var i = 0
        while (i < samples.size) {
            val n = minOf(WINDOW - carried, samples.size - i)
            System.arraycopy(samples, i, carry, carried, n)
            carried += n
            i += n
            if (carried == WINDOW) {
                vad.acceptWaveform(carry.copyOf())
                carried = 0
            }
        }
    }

    override val isSpeech: Boolean get() = vad.isSpeechDetected()

    override fun pollSegments(): List<VadSegment> {
        if (vad.empty()) return emptyList()
        val out = ArrayList<VadSegment>(1)
        while (!vad.empty()) {
            val s = vad.front()
            out += VadSegment(s.start, s.samples.size)
            vad.pop()
        }
        return out
    }

    override fun flush() {
        if (carried > 0) {
            // the tail is padded with silence; the engine clamps segment ends to the real audio
            vad.acceptWaveform(carry.copyOf(carried).copyOf(WINDOW))
            carried = 0
        }
        vad.flush()
    }

    override fun reset() {
        vad.reset()
        carried = 0
    }

    fun release() = vad.release()

    companion object {
        private const val WINDOW = 512
        const val MAX_SPEECH_S = 10f
    }
}
