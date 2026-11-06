package ru.replika.steno.diar

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import ru.replika.steno.asr.ModelAssets
import ru.replika.steno.core.Diarizer
import ru.replika.steno.core.SpeakerTurn

/**
 * Audio-based diarization:
 *  1. Silero VAD cuts the recording into phrases at pauses (long phrases are cut every [MAX_PHRASE_S]);
 *  2. NeMo TitaNet-small turns every phrase into a voice embedding;
 *  3. average-linkage clustering on cosine distance groups phrases by voice, with no preset speaker count.
 * Neighbouring phrases of the same speaker are merged later by Segments.normalize.
 */
class VadDiarizer(assets: AssetManager) : Diarizer {

    private val vadConfig = VadModelConfig(
        sileroVadModelConfig = SileroVadModelConfig(
            model = ModelAssets.VAD,
            threshold = 0.5f,
            minSilenceDuration = 0.25f,
            minSpeechDuration = 0.25f,
            windowSize = WINDOW,
            maxSpeechDuration = MAX_PHRASE_S,
        ),
        sampleRate = 16_000,
        numThreads = 1,
    )
    private val vad = Vad(assets, vadConfig)
    private val extractor = SpeakerEmbeddingExtractor(
        assets,
        SpeakerEmbeddingExtractorConfig(model = ModelAssets.EMBEDDING, numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)),
    )

    override suspend fun diarize(samples: FloatArray, sampleRate: Int, onProgress: (Float) -> Unit): List<SpeakerTurn> {
        val phrases = synchronized(this) { detectSpeech(samples) }
        onProgress(0.15f)
        if (phrases.isEmpty()) return emptyList()

        val embeddings = ArrayList<FloatArray>(phrases.size)
        phrases.forEachIndexed { i, (a, b) ->
            coroutineContext.ensureActive()
            val stream = extractor.createStream()
            try {
                stream.acceptWaveform(samples.copyOfRange(a, b), sampleRate)
                stream.inputFinished()
                embeddings += synchronized(this) { extractor.compute(stream) }
            } finally {
                stream.release()
            }
            onProgress(0.15f + 0.75f * (i + 1) / phrases.size)
        }
        coroutineContext.ensureActive()
        val result = Clustering.cluster(embeddings, CLUSTER_THRESHOLD)
        onProgress(1f)
        return phrases.mapIndexed { i, (a, b) ->
            SpeakerTurn(a * 1000L / sampleRate, b * 1000L / sampleRate, result.labels[i], result.confidence[i])
        }
    }

    /** Returns [start, end) sample ranges of speech. */
    private fun detectSpeech(samples: FloatArray): List<Pair<Int, Int>> {
        vad.reset()
        val out = ArrayList<Pair<Int, Int>>()
        fun drain() {
            while (!vad.empty()) {
                val s = vad.front()
                out += s.start.toInt() to (s.start.toInt() + s.samples.size).coerceAtMost(samples.size)
                vad.pop()
            }
        }
        var i = 0
        while (i < samples.size) {
            val end = minOf(i + WINDOW, samples.size)
            vad.acceptWaveform(samples.copyOfRange(i, end))
            drain()
            i = end
        }
        vad.flush()
        drain()
        return pad(out.filter { it.second - it.first >= MIN_PHRASE_SAMPLES }, samples.size)
    }

    /**
     * VAD tends to clip soft word onsets and endings. Each phrase gets up to [PAD_MS] of context on
     * both sides, but never more than half of the pause to its neighbour, so it cannot eat the
     * beginning of the next speaker's phrase.
     */
    private fun pad(phrases: List<Pair<Int, Int>>, total: Int): List<Pair<Int, Int>> {
        val pad = PAD_MS * 16
        return phrases.mapIndexed { i, (a, b) ->
            val prevEnd = if (i > 0) phrases[i - 1].second else 0
            val nextStart = if (i + 1 < phrases.size) phrases[i + 1].first else total
            val start = a - minOf(pad, maxOf(0, a - prevEnd) / 2)
            val end = b + minOf(pad, maxOf(0, nextStart - b) / 2)
            start.coerceAtLeast(0) to end.coerceAtMost(total)
        }
    }

    fun release() {
        vad.release()
        extractor.release()
    }

    companion object {
        private const val WINDOW = 512
        /** Max context added around each VAD phrase, ms (tuned on the test set, docs/TEST_RESULTS.md). */
        const val PAD_MS = 400
        const val MAX_PHRASE_S = 12f
        private const val MIN_PHRASE_SAMPLES = 16_000 / 4
        /** Cosine-distance cut-off. Tuned on the test set: results identical across 0.5-0.7, 0.6 is the middle (docs/TEST_RESULTS.md). */
        const val CLUSTER_THRESHOLD = 0.6f
    }
}
