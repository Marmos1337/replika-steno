package ru.replika.steno.core

import android.os.SystemClock
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

enum class Stage(val title: String, val weight: Float) {
    DECODE("Чтение аудио", 0.05f),
    DIARIZE("Разделение говорящих", 0.30f),
    RECOGNIZE("Распознавание речи", 0.45f),
    FORMAT("Оформление текста (LLM)", 0.15f),
    SUMMARY("Итоги встречи", 0.05f),
}

data class Progress(val stage: Stage, val stageFraction: Float) {
    val overall: Float
        get() {
            var acc = 0f
            for (s in Stage.entries) {
                if (s == stage) return (acc + s.weight * stageFraction.coerceIn(0f, 1f)).coerceIn(0f, 1f)
                acc += s.weight
            }
            return 1f
        }
}

class NoSpeechException : Exception("В записи не найдено речи.")

data class PipelineResult(
    val utterances: List<Utterance>,
    val durationMs: Long,
    val summary: MeetingSummary?,
    val metrics: ProcessingMetrics,
)

/**
 * import -> decode -> diarize (audio embeddings + clustering) -> ASR per speaker turn -> LLM formatting
 * -> summary. Speaker ids and time boundaries come only from the diarizer; the LLM can touch text only,
 * and only through [TextGuard].
 */
class Pipeline(
    private val diarizer: Diarizer,
    private val recognizer: Recognizer,
    private val formatter: Formatter?,
) {

    suspend fun run(audio: DecodedAudio, decodeMs: Long, onProgress: (Progress) -> Unit): PipelineResult {
        val sr = DecodedAudio.SAMPLE_RATE
        val durationMs = audio.durationMs

        // 1. diarization
        var t0 = SystemClock.elapsedRealtime()
        onProgress(Progress(Stage.DIARIZE, 0f))
        val rawTurns = diarizer.diarize(audio.samples, sr) { onProgress(Progress(Stage.DIARIZE, it)) }
        val turns = Segments.normalize(rawTurns, durationMs)
        val diarMs = SystemClock.elapsedRealtime() - t0
        if (turns.isEmpty()) throw NoSpeechException()
        val ids = Segments.speakerIds(turns)

        // 2. recognition, per turn (split long turns at quiet points)
        t0 = SystemClock.elapsedRealtime()
        val pieces = turns.flatMap { Segments.splitForAsr(it, audio.samples, sr) }
        val utterances = ArrayList<Utterance>(pieces.size)
        pieces.forEachIndexed { i, p ->
            coroutineContext.ensureActive()
            val r = recognizer.recognize(Segments.slice(audio.samples, sr, p.startMs, p.endMs), sr)
            val text = r.text.trim()
            if (text.isNotEmpty()) {
                utterances += Utterance(ids.getValue(p.cluster), p.startMs, p.endMs, text, text, r.confidence)
            }
            onProgress(Progress(Stage.RECOGNIZE, (i + 1f) / pieces.size))
        }
        val asrMs = SystemClock.elapsedRealtime() - t0
        if (utterances.isEmpty()) throw NoSpeechException()

        // 3. LLM formatting (text only, guarded)
        t0 = SystemClock.elapsedRealtime()
        val formatted = format(utterances, onProgress)
        // 4. summary with references back to utterances
        val summary = summarize(formatted, onProgress)
        val llmMs = SystemClock.elapsedRealtime() - t0

        return PipelineResult(
            utterances = formatted,
            durationMs = durationMs,
            summary = summary,
            metrics = ProcessingMetrics(durationMs, decodeMs, diarMs, asrMs, llmMs),
        )
    }

    private suspend fun format(utts: List<Utterance>, onProgress: (Progress) -> Unit): List<Utterance> {
        onProgress(Progress(Stage.FORMAT, 0f))
        val f = formatter ?: return utts.map { it.copy(text = TextGuard.tidy(it.rawText)) }
        val out = ArrayList<Utterance>(utts.size)
        val batch = 6
        var i = 0
        while (i < utts.size) {
            coroutineContext.ensureActive()
            val chunk = utts.subList(i, minOf(i + batch, utts.size))
            val answers = runCatching { f.formatLines(chunk.map { it.rawText }) }.getOrNull()
            chunk.forEachIndexed { k, u ->
                val candidate = answers?.getOrNull(k)
                val accepted = TextGuard.accept(u.rawText, candidate)
                out += u.copy(text = if (accepted === u.rawText || accepted == u.rawText) TextGuard.tidy(u.rawText) else accepted)
            }
            i += batch
            onProgress(Progress(Stage.FORMAT, i.toFloat() / utts.size))
        }
        return out
    }

    private suspend fun summarize(utts: List<Utterance>, onProgress: (Progress) -> Unit): MeetingSummary? {
        val f = formatter ?: return null
        onProgress(Progress(Stage.SUMMARY, 0f))
        val numbered = SummaryParser.numbered(utts)
        val answer = runCatching { f.summarize(numbered) }.getOrNull() ?: return null
        onProgress(Progress(Stage.SUMMARY, 1f))
        return SummaryParser.parse(answer, utts).takeIf { it.points.isNotEmpty() || it.actions.isNotEmpty() }
    }
}
