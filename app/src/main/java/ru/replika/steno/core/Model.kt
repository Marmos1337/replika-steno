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
    /** Recognizer confidence in [0,1], or null when the model gives no real score. */
    val confidence: Float? = null,
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
)

enum class RecordingState { NEW, PROCESSING, READY, FAILED }

data class MeetingSummary(
    val points: List<SummaryPoint> = emptyList(),
    val actions: List<ActionItem> = emptyList(),
)

/** A summary line that always points back at the utterances it was built from. */
data class SummaryPoint(val text: String, val utteranceIndices: List<Int>)

data class ActionItem(
    val text: String,
    val speakerId: String?,
    val utteranceIndex: Int,
)

/** Measured, not estimated: filled in by the pipeline from real wall-clock timings. */
data class ProcessingMetrics(
    val audioDurationMs: Long,
    val decodeMs: Long,
    val diarizationMs: Long,
    val asrMs: Long,
    val llmMs: Long,
) {
    val totalMs: Long get() = decodeMs + diarizationMs + asrMs + llmMs
    val realTimeFactor: Float
        get() = if (audioDurationMs > 0) totalMs.toFloat() / audioDurationMs else 0f
}

fun Recording.displayName(speakerId: String): String = speakerNames[speakerId] ?: speakerId
