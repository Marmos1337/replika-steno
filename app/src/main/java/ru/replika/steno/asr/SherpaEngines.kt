package ru.replika.steno.asr

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import ru.replika.steno.core.AsrResult
import ru.replika.steno.core.Recognizer

/** Model files inside the APK (assets/models/...). Kept in one place so the About screen shows the same list. */
object ModelAssets {
    const val ASR_MODEL = "models/asr/model.int8.onnx"
    const val ASR_TOKENS = "models/asr/tokens.txt"
    const val ASR_EN_MODEL = "models/asr_en/model.int8.onnx"
    const val ASR_EN_TOKENS = "models/asr_en/tokens.txt"
    const val VAD = "models/diar/silero_vad.onnx"
    const val EMBEDDING = "models/diar/titanet_small.onnx"
    const val LLM = "models/llm/qwen2.5-0.5b-instruct-q8.task"
}

private fun threads() = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

/** One NeMo CTC model through sherpa-onnx (GigaAM v3 for Russian, Parakeet TDT-CTC 110M for English). */
class NemoCtc(assets: AssetManager, model: String, tokens: String) {
    private val recognizer = OfflineRecognizer(
        assetManager = assets,
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80), // NeMo models override this from their metadata
            modelConfig = OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(model = model),
                tokens = tokens,
                numThreads = threads(),
                provider = "cpu",
            ),
            decodingMethod = "greedy_search",
        ),
    )

    @Synchronized
    fun recognize(samples: FloatArray, sampleRate: Int): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    fun release() = recognizer.release()
}

/**
 * Russian first. GigaAM writes English speech in Latin letters, so when most letters of its answer
 * are Latin the phrase is recognized again with the English model (loaded only on first need).
 * Works per phrase, so mixed-language meetings are handled too.
 */
class SherpaRecognizer(private val assets: AssetManager) : Recognizer {
    private val ru = NemoCtc(assets, ModelAssets.ASR_MODEL, ModelAssets.ASR_TOKENS)
    private val enLazy = lazy { NemoCtc(assets, ModelAssets.ASR_EN_MODEL, ModelAssets.ASR_EN_TOKENS) }
    private val en by enLazy

    override fun recognize(samples: FloatArray, sampleRate: Int): AsrResult {
        if (samples.size < sampleRate / 10) return AsrResult("")
        val textRu = ru.recognize(samples, sampleRate)
        if (latinShare(textRu) <= 0.5f) return AsrResult(textRu, "ru")
        val textEn = en.recognize(samples, sampleRate)
        return if (textEn.isNotBlank()) AsrResult(textEn, "en") else AsrResult(textRu, "ru")
    }

    fun release() {
        ru.release()
        if (enLazy.isInitialized()) en.release()
    }

    companion object {
        fun latinShare(s: String): Float {
            var latin = 0
            var letters = 0
            for (c in s) {
                if (!c.isLetter()) continue
                letters++
                if (c in 'a'..'z' || c in 'A'..'Z') latin++
            }
            return if (letters == 0) 0f else latin.toFloat() / letters
        }
    }
}
