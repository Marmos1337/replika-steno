package ru.replika.steno.asr

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import ru.replika.steno.core.AsrResult
import ru.replika.steno.core.Diarizer
import ru.replika.steno.core.Recognizer
import ru.replika.steno.core.SpeakerTurn

/** Model files inside the APK (assets/models/...). Kept in one place so the About screen shows the same list. */
object ModelAssets {
    const val ASR_MODEL = "models/asr/model.int8.onnx"
    const val ASR_TOKENS = "models/asr/tokens.txt"
    const val SEGMENTATION = "models/diar/segmentation.onnx"
    const val EMBEDDING = "models/diar/embedding.onnx"
    const val LLM = "models/llm/qwen2.5-0.5b-instruct-q8.task"
}

private fun threads() = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

/** GigaAM v3 (NeMo CTC, Russian, with punctuation and casing) through sherpa-onnx. */
class SherpaRecognizer(assets: AssetManager) : Recognizer {
    private val recognizer = OfflineRecognizer(
        assetManager = assets,
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80), // NeMo models override this from their metadata
            modelConfig = OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(model = ModelAssets.ASR_MODEL),
                tokens = ModelAssets.ASR_TOKENS,
                numThreads = threads(),
                provider = "cpu",
            ),
            decodingMethod = "greedy_search",
        ),
    )

    @Synchronized
    override fun recognize(samples: FloatArray, sampleRate: Int): AsrResult {
        if (samples.size < sampleRate / 10) return AsrResult("")
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate)
            recognizer.decode(stream)
            return AsrResult(recognizer.getResult(stream).text)
        } finally {
            stream.release()
        }
    }

    fun release() = recognizer.release()
}

/**
 * Real audio-based diarization: pyannote-3.0 segmentation finds speech and speaker changes,
 * 3D-Speaker CAM++ turns each piece into a voice embedding, and the embeddings are clustered.
 * The number of speakers is not given in advance - the threshold decides.
 */
class SherpaDiarizer(assets: AssetManager) : Diarizer {
    private val sd = OfflineSpeakerDiarization(
        assetManager = assets,
        config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = ModelAssets.SEGMENTATION),
                numThreads = threads(),
            ),
            embedding = SpeakerEmbeddingExtractorConfig(model = ModelAssets.EMBEDDING, numThreads = threads()),
            clustering = FastClusteringConfig(numClusters = -1, threshold = CLUSTER_THRESHOLD, computeConfidence = true),
            minDurationOn = 0.3f,
            minDurationOff = 0.5f,
        ),
    )

    override suspend fun diarize(samples: FloatArray, sampleRate: Int, onProgress: (Float) -> Unit): List<SpeakerTurn> {
        require(sampleRate == sd.sampleRate()) { "diarizer expects ${sd.sampleRate()} Hz" }
        val ctx = currentCoroutineContext()
        val segments = synchronized(this) {
            sd.processWithCallback(samples, callback = { done, total, _ ->
                if (total > 0) onProgress(done.toFloat() / total)
                // returning non-zero asks the native side to stop early
                if (ctx.isActive) 0 else 1
            })
        }
        if (!ctx.isActive) throw CancellationException("cancelled")
        return segments.map {
            SpeakerTurn(
                startMs = (it.start * 1000).toLong(),
                endMs = (it.end * 1000).toLong(),
                cluster = it.speaker,
                confidence = it.confidence.takeIf { c -> c >= -1f },
            )
        }
    }

    fun release() = sd.release()

    companion object {
        /** Cosine-distance threshold for joining embeddings into one speaker; tuned on test meetings (see docs/TESTS.md). */
        const val CLUSTER_THRESHOLD = 0.5f
    }
}
