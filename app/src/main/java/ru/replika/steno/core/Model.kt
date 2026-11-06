package ru.replika.steno.core

/**
 * One utterance of the transcript. [speakerId] is the stable identifier that goes into the
 * export ("speaker_1", "speaker_2", ...); the human-readable name lives in [Recording.speakerNames]
 * so renaming a participant never touches the exported ids.
 */
data class Utterance(
    val speakerId: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    /** Text as the recognizer produced it, before the LLM formatting pass. */
    val rawText: String = text,
    /**
     * How sure the diarizer is that this turn belongs to [speakerId]: the model's own score in [-1,1]
     * (similarity to the speaker's cluster). Null when the model did not report one. Never invented.
     */
    val speakerConfidence: Float? = null,
    /** True once the user edited [text] by hand; [rawText] always keeps the recognizer output. */
    val edited: Boolean = false,
    /** "ru" or "en": which recognizer produced [rawText]. */
    val lang: String = "ru",
)

data class Recording(
    val id: String,
    val title: String,
    val sourceUri: String,
    val createdAtMs: Long,
    val durationMs: Long,
    val utterances: List<Utterance> = emptyList(),
    /** speaker_id -> display name. Absent id means "show the id as is". */
    val speakerNames: Map<String, String> = emptyMap(),
    val state: RecordingState = RecordingState.NEW,
    val error: String? = null,
    val summary: MeetingSummary? = null,
    val metrics: ProcessingMetrics? = null,
    /** Transcript is READY but the LLM pass (formatting + summary) is still running. */
    val llmPending: Boolean = false,
    /** Why there is no LLM result, if there is none (shown honestly in the summary tab). */
    val llmNote: String? = null,
)

enum class RecordingState { NEW, PROCESSING, READY, FAILED }

data class MeetingSummary(
    val points: List<SummaryPoint> = emptyList(),
    val actions: List<ActionItem> = emptyList(),
)

enum class PointKind(val title: String) { SUMMARY("Кратко"), DECISION("Решения"), QUESTION("Открытые вопросы") }

/** A summary line that always points back at the utterances it was built from. */
data class SummaryPoint(val text: String, val utteranceIndices: List<Int>, val kind: PointKind = PointKind.SUMMARY)

/**
 * An agreement extracted from the meeting: what to do / who / by when. [assignee] and [due] are kept
 * only if those words were actually said in the cited utterance; otherwise they stay null.
 * Nothing is assigned or sent anywhere - the user just confirms the card.
 */
data class ActionItem(
    val text: String,
    /** who said the cited utterance (stable id), for context */
    val speakerId: String?,
    val utteranceIndex: Int,
    val assignee: String? = null,
    val due: String? = null,
    val confirmed: Boolean = false,
)

/** Measured, not estimated: filled in by the pipeline from real wall-clock timings. */
data class ProcessingMetrics(
    val audioDurationMs: Long,
    val decodeMs: Long,
    val diarizationMs: Long,
    val asrMs: Long,
    val llmMs: Long,
) {
    /** Time until the transcript is ready (what the user waits for); the LLM pass runs after it. */
    val transcriptMs: Long get() = decodeMs + diarizationMs + asrMs
    val totalMs: Long get() = transcriptMs + llmMs
    /** RTF of the transcript (decode + diarization + ASR) / audio length. */
    val realTimeFactor: Float
        get() = if (audioDurationMs > 0) transcriptMs.toFloat() / audioDurationMs else 0f
}

fun Recording.displayName(speakerId: String): String = speakerNames[speakerId] ?: speakerId
