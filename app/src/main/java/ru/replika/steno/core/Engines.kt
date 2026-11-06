package ru.replika.steno.core

/** Audio-based speaker diarization: who speaks when. */
interface Diarizer {
    /** [onProgress] gets 0..1. Must honour coroutine cancellation between chunks. */
    suspend fun diarize(samples: FloatArray, sampleRate: Int, onProgress: (Float) -> Unit): List<SpeakerTurn>
}

data class AsrResult(val text: String)

interface Recognizer {
    fun recognize(samples: FloatArray, sampleRate: Int): AsrResult
}

/**
 * Local LLM. Only ever asked to (a) restore punctuation/case of given lines and (b) summarise with
 * references. Its output goes through [TextGuard] / reference validation before use.
 */
interface Formatter {
    val name: String
    /** Returns one formatted string per input line (null = model gave nothing usable). */
    suspend fun formatLines(lines: List<String>): List<String?>
    /** Raw model answer for the summary prompt; parsed and validated by [SummaryParser]. */
    suspend fun summarize(numberedTranscript: String): String
}
