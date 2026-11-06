package ru.replika.steno.core

import android.os.SystemClock
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

enum class Stage(val title: String, val weight: Float) {
    DECODE("Подготовка", 0.05f),
    DIARIZE("Участники", 0.25f),
    RECOGNIZE("Распознавание", 0.40f),
    FORMAT("Подготовка результата", 0.20f),
    SUMMARY("Итоги встречи", 0.10f),
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

data class TranscriptResult(
    val utterances: List<Utterance>,
    val durationMs: Long,
    val metrics: ProcessingMetrics,
)

data class LlmResult(val utterances: List<Utterance>, val summary: MeetingSummary?, val llmMs: Long)

/**
 * Phase 1 (what the user waits for): decode -> diarize (VAD + voice embeddings + clustering) ->
 * ASR per speaker turn. Phase 2 runs after the transcript is already shown: LLM formatting + summary.
 * Speaker ids and time boundaries come only from the diarizer; the LLM can touch text only, and only
 * through [TextGuard].
 */
class Pipeline(
    private val diarizer: Diarizer,
    private val recognizer: Recognizer,
    private val formatter: Formatter?,
) {

    suspend fun transcribe(audio: DecodedAudio, decodeMs: Long, onProgress: (Progress) -> Unit): TranscriptResult {
        val sr = DecodedAudio.SAMPLE_RATE
        val durationMs = audio.durationMs

        var t0 = SystemClock.elapsedRealtime()
        onProgress(Progress(Stage.DIARIZE, 0f))
        val rawTurns = diarizer.diarize(audio.samples, sr) { onProgress(Progress(Stage.DIARIZE, it)) }
        val turns = Segments.normalize(rawTurns, durationMs)
        val diarMs = SystemClock.elapsedRealtime() - t0
        if (turns.isEmpty()) throw NoSpeechException()
        val ids = Segments.speakerIds(turns)

        t0 = SystemClock.elapsedRealtime()
        val pieces = turns.flatMap { Segments.splitForAsr(it, audio.samples, sr) }
        val utterances = ArrayList<Utterance>(pieces.size)
        pieces.forEachIndexed { i, p ->
            coroutineContext.ensureActive()
            val r = recognizer.recognize(Segments.slice(audio.samples, sr, p.startMs, p.endMs), sr)
            val text = r.text.trim()
            if (text.isNotEmpty()) {
                utterances += Utterance(ids.getValue(p.cluster), p.startMs, p.endMs, TextGuard.tidy(text), text, p.confidence, lang = r.lang)
            }
            onProgress(Progress(Stage.RECOGNIZE, (i + 1f) / pieces.size))
        }
        val asrMs = SystemClock.elapsedRealtime() - t0
        if (utterances.isEmpty()) throw NoSpeechException()
        // ids by first appearance among the utterances that actually have text
        val remap = LinkedHashMap<String, String>()
        utterances.forEach { u -> remap.getOrPut(u.speakerId) { "speaker_${remap.size + 1}" } }
        val finalUtts = utterances.map { it.copy(speakerId = remap.getValue(it.speakerId)) }
        return TranscriptResult(finalUtts, durationMs, ProcessingMetrics(durationMs, decodeMs, diarMs, asrMs, 0))
    }

    suspend fun polish(utts: List<Utterance>, onProgress: (Progress) -> Unit): LlmResult {
        val t0 = SystemClock.elapsedRealtime()
        val formatted = format(utts, onProgress)
        val summary = summarize(formatted, onProgress)
        return LlmResult(formatted, summary, SystemClock.elapsedRealtime() - t0)
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
                out += u.copy(text = TextGuard.tidy(accepted))
            }
            i += batch
            onProgress(Progress(Stage.FORMAT, i.toFloat() / utts.size))
        }
        return out
    }

    private suspend fun summarize(utts: List<Utterance>, onProgress: (Progress) -> Unit): MeetingSummary? {
        val f = formatter ?: return null
        onProgress(Progress(Stage.SUMMARY, 0f))
        val windows = SummaryParser.windows(utts)
        val points = ArrayList<SummaryPoint>()
        val actions = ArrayList<ActionItem>()
        windows.forEachIndexed { i, w ->
            coroutineContext.ensureActive()
            val answer = runCatching { f.summarize(w) }.getOrNull()
            if (answer != null) {
                val part = SummaryParser.parse(answer, utts)
                part.points.filterTo(points) { p -> points.none { it.text.equals(p.text, ignoreCase = true) } }
                part.actions.filterTo(actions) { a -> actions.none { it.text.equals(a.text, ignoreCase = true) } }
                points.sortBy { it.kind.ordinal }
            }
            onProgress(Progress(Stage.SUMMARY, (i + 1f) / windows.size))
        }
        return MeetingSummary(points, actions).takeIf { points.isNotEmpty() || actions.isNotEmpty() }
    }
}
