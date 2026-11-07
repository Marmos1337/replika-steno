package ru.replika.steno.core

/** Which recognizer a single re-recognized utterance goes through. */
enum class RecognizeMode(val title: String, val hint: String) {
    AUTO("Автоматически", "Русская модель, английская — если фраза на английском"),
    RU("Как русскую речь", "Только русская модель"),
    EN("Как английскую речь", "Только английская модель"),
}

/**
 * The utterance text before the last re-recognition. One slot per utterance, persisted with the
 * recording (never exported): undo swaps it with the current text, so neither version is ever lost.
 */
data class PreviousText(val text: String, val rawText: String, val lang: String, val edited: Boolean)

/** A time range of the recording, end exclusive. */
data class Span(val startMs: Long, val endMs: Long) {
    val lengthMs: Long get() = endMs - startMs
}

/**
 * Pure rules of «Распознать заново» for one utterance. The time bounds and speaker of the utterance
 * never change: widening only adds up to [WIDEN_MS] of the audio around it for the recognizer.
 */
object Fragment {
    /** «Захватить соседнее»: up to this much audio on each side, never past a neighbour or the recording. */
    const val WIDEN_MS = 1_000L

    /** One utterance is at most ~20 s; anything far longer is not a fragment and is refused. */
    const val MAX_MS = 10L * 60 * 1000

    /**
     * Audio range to decode for utterance [index]. With [widen], it grows by up to [WIDEN_MS] on each
     * side but stops at the previous utterance's end, the next one's start, 0 and [durationMs]
     * (0 = unknown). Null when the index is out of range or the bounds are broken.
     */
    fun range(utts: List<Utterance>, index: Int, widen: Boolean, durationMs: Long): Span? {
        val u = utts.getOrNull(index) ?: return null
        val limit = if (durationMs > 0) durationMs else Long.MAX_VALUE
        val start = u.startMs.coerceAtLeast(0)
        val end = u.endMs.coerceAtMost(limit)
        if (end <= start) return null
        if (!widen) return Span(start, end)
        // the closest neighbour edges; utterances are time-ordered but a neighbour may overlap a little
        val before = utts.getOrNull(index - 1)?.endMs?.coerceAtMost(start) ?: 0L
        val after = utts.getOrNull(index + 1)?.startMs?.coerceAtLeast(end) ?: limit
        val a = maxOf(start - WIDEN_MS, before, 0L)
        val b = minOf(end + WIDEN_MS, after, limit)
        return Span(a, b)
    }

    /** Same utterance as when the work started: a re-run or edit of bounds in between drops the result. */
    fun same(a: Utterance, b: Utterance): Boolean = a.speakerId == b.speakerId && a.startMs == b.startMs && a.endMs == b.endMs

    /**
     * The recognizer said exactly what it said before and the user did not edit the line: nothing is
     * replaced (this also keeps the punctuation the LLM pass may have added).
     */
    fun unchanged(u: Utterance, rawText: String, lang: String): Boolean =
        !u.edited && u.rawText == rawText && u.lang == lang

    /** The utterance with the new recognizer output; the text it had goes into [Utterance.previous]. */
    fun apply(u: Utterance, rawText: String, lang: String): Utterance = u.copy(
        text = TextGuard.tidy(rawText),
        rawText = rawText,
        lang = lang,
        edited = false,
        previous = PreviousText(u.text, u.rawText, u.lang, u.edited),
    )

    /** Swaps the current text with [Utterance.previous]; null when there is nothing to return to. */
    fun undo(u: Utterance): Utterance? {
        val p = u.previous ?: return null
        return u.copy(
            text = p.text, rawText = p.rawText, lang = p.lang, edited = p.edited,
            previous = PreviousText(u.text, u.rawText, u.lang, u.edited),
        )
    }

    /** Language of a fragment recognized in several pieces: the one that produced most of the text. */
    fun mainLang(parts: List<AsrResult>): String =
        parts.groupBy { it.lang }.maxByOrNull { (_, v) -> v.sumOf { it.text.length } }?.key ?: "ru"
}
