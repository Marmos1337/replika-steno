package ru.replika.steno.core.live

import org.json.JSONObject

enum class LiveStatus {
    /** Opening the microphone. */
    STARTING,
    RECORDING,
    PAUSED,
    /** Capture stopped; the worker is finishing the queued phrases. */
    FINISHING,
    /** Recording saved and handed to the normal pipeline; [LiveState.recordingId] is set. */
    DONE,
    /** Could not record (or recording broke off). Audio captured so far is kept when there is any. */
    ERROR;

    val terminal: Boolean get() = this == DONE || this == ERROR
    val capturing: Boolean get() = this == RECORDING || this == PAUSED
}

/**
 * A confirmed live phrase. Times are on the recording timeline (paused time excluded).
 * [speaker] is a provisional voice number (1, 2...) from online clustering, or null when unknown;
 * it is never the final speaker id - the full pipeline re-diarizes the whole recording after Stop.
 */
data class LiveLine(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val lang: String = "ru",
    val speaker: Int? = null,
) {
    /** Always true: live speaker numbers are a hint, not the transcript's speaker_N. */
    val speakerProvisional: Boolean get() = true
}

/** The phrase still being spoken: preliminary text that the next update or the final line replaces. */
data class LivePartial(val startMs: Long, val endMs: Long, val text: String, val lang: String = "ru")

/** Measured on the device during a live session; nothing here is estimated. */
data class LiveMetrics(
    /** Captured audio, ms (paused time excluded). */
    val audioMs: Long = 0,
    /** Capture start -> first non-blank text on screen. */
    val timeToFirstTextMs: Long? = null,
    /** Text published (wall clock) minus capture time of the last audio sample in that decode. */
    val latencyMedianMs: Long? = null,
    val latencyP95Ms: Long? = null,
    val latencyCount: Int = 0,
    /** Decode time / audio decoded (finals and partials); < 1 is faster than real time. */
    val decodeRtf: Float? = null,
    /** Audio consumed by the worker / time the worker was busy: how many times faster than the stream it runs. */
    val speedX: Float? = null,
    val maxBacklogMs: Long = 0,
    val maxQueueChunks: Int = 0,
    val peakPssMb: Int? = null,
    val partials: Int = 0,
    val finals: Int = 0,
    val skippedPartials: Int = 0,
    /** Audio the live view skipped because the device fell >30 s behind (still on disk, still in the final transcript). */
    val droppedAudioMs: Long = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("audioMs", audioMs)
        timeToFirstTextMs?.let { put("timeToFirstTextMs", it) }
        latencyMedianMs?.let { put("latencyMedianMs", it) }
        latencyP95Ms?.let { put("latencyP95Ms", it) }
        put("latencyCount", latencyCount)
        decodeRtf?.let { put("decodeRtf", it.toDouble()) }
        speedX?.let { put("speedX", it.toDouble()) }
        put("maxBacklogMs", maxBacklogMs)
        put("maxQueueChunks", maxQueueChunks)
        peakPssMb?.let { put("peakPssMb", it) }
        put("partials", partials); put("finals", finals); put("skippedPartials", skippedPartials)
        put("droppedAudioMs", droppedAudioMs)
    }

    companion object {
        fun fromJson(o: JSONObject) = LiveMetrics(
            audioMs = o.optLong("audioMs"),
            timeToFirstTextMs = if (o.has("timeToFirstTextMs")) o.getLong("timeToFirstTextMs") else null,
            latencyMedianMs = if (o.has("latencyMedianMs")) o.getLong("latencyMedianMs") else null,
            latencyP95Ms = if (o.has("latencyP95Ms")) o.getLong("latencyP95Ms") else null,
            latencyCount = o.optInt("latencyCount"),
            decodeRtf = if (o.has("decodeRtf")) o.getDouble("decodeRtf").toFloat() else null,
            speedX = if (o.has("speedX")) o.getDouble("speedX").toFloat() else null,
            maxBacklogMs = o.optLong("maxBacklogMs"),
            maxQueueChunks = o.optInt("maxQueueChunks"),
            peakPssMb = if (o.has("peakPssMb")) o.getInt("peakPssMb") else null,
            partials = o.optInt("partials"), finals = o.optInt("finals"), skippedPartials = o.optInt("skippedPartials"),
            droppedAudioMs = o.optLong("droppedAudioMs"),
        )
    }
}

/** Everything the live screen needs, as one snapshot. */
data class LiveState(
    val status: LiveStatus = LiveStatus.STARTING,
    /** The recording the audio is written to; set once capture has started. */
    val recordingId: String? = null,
    /** Captured audio length, ms; paused time excluded. */
    val elapsedMs: Long = 0,
    /** Input loudness 0..1, smoothed for a meter. */
    val level: Float = 0f,
    val lines: List<LiveLine> = emptyList(),
    val partial: LivePartial? = null,
    /** Captured audio not yet covered by any decode. Above [LAG_WARN_MS] the UI says the phone is behind. */
    val backlogMs: Long = 0,
    /** False while the speech models are still loading (audio is already being recorded). */
    val modelsReady: Boolean = false,
    /** Android muted our capture because another app took the microphone (API 29+). */
    val micSilenced: Boolean = false,
    /** Non-fatal message while recording (models failed, time limit reached...). */
    val notice: String? = null,
    /** Fatal message with [LiveStatus.ERROR]. */
    val error: String? = null,
    val metrics: LiveMetrics = LiveMetrics(),
    /** Debug runs paced from a file instead of the microphone. */
    val fromFile: Boolean = false,
) {
    val lagging: Boolean get() = backlogMs > LAG_WARN_MS

    companion object {
        const val LAG_WARN_MS = 4_000L
    }
}

/** Display label for a provisional live voice. */
fun liveVoiceLabel(speaker: Int?): String = if (speaker == null) "Голос ?" else "Голос $speaker"
