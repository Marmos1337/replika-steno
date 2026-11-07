package ru.replika.steno.core.live

/** A finished speech segment: [startSample] counts from the last [StreamingVad.reset]. */
data class VadSegment(val startSample: Long, val length: Int)

/**
 * Incremental voice activity detection. Fed in arrival order; sample indices restart at 0 after
 * [reset]. Implemented over sherpa-onnx Silero VAD on the device and by fakes in tests.
 */
interface StreamingVad {
    /** Longest segment the detector emits before cutting; bounds how far back the engine must keep audio. */
    val maxSpeechMs: Long
    fun accept(samples: FloatArray)
    /** True while a phrase is open (started and not yet emitted as a segment). */
    val isSpeech: Boolean
    fun pollSegments(): List<VadSegment>
    /** Closes the open phrase, so the next [pollSegments] returns it. */
    fun flush()
    fun reset()
}

/** Voice embedding of a phrase (TitaNet on the device). */
fun interface VoicePrint {
    fun embed(samples: FloatArray): FloatArray
}

/** Monotonic wall clock, ms. */
fun interface LiveClock {
    fun nowMs(): Long
}
