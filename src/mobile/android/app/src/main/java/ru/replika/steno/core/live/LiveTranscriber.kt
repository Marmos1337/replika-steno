package ru.replika.steno.core.live

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.log10
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import ru.replika.steno.core.Recognizer

class LiveConfig(
    val sampleRate: Int = 16_000,
    /** Partials are refreshed at most this often. */
    val partialIntervalMs: Long = 600,
    /** An open phrase shorter than this is not worth a partial decode. */
    val minPartialMs: Long = 500,
    /** Context added before a final segment (VAD clips soft onsets), at most half the gap to the previous final. */
    val prePadMs: Long = 300,
    /** Context added after a segment that ended in silence. */
    val postPadMs: Long = 150,
    /** VAD reports speech only after its minimum speech duration; the open phrase starts this much earlier. */
    val onsetLookbackMs: Long = 600,
    /** Audio kept in memory. Must cover the longest VAD segment plus padding (checked in [LiveTranscriber.bind]). */
    val ringMs: Long = 30_000,
    /** Most audio waiting for the worker. Beyond it the live view skips audio (it stays on disk). */
    val maxQueueMs: Long = 30_000,
    /** With more chunks than this waiting, the worker is behind and skips partials. */
    val busyQueueChunks: Int = 2,
    val minFinalMs: Long = 250,
    /** Shorter phrases give unreliable voice embeddings; they keep the previous voice. */
    val minSpeakerMs: Long = 1_000,
    val speakerThreshold: Float = 0.6f,
    val latencyKeep: Int = 5_000,
)

/**
 * Pseudo-streaming recognition on the offline models.
 *
 * Capture side ([accept]) only stamps the block and hands it over through a queue; it never waits
 * for a model. One worker ([step], always on the same thread) runs VAD and decodes:
 *  - final: every segment the VAD closes is decoded once, so lines never overlap or repeat;
 *  - partial: while a phrase is open, its audio so far is decoded every [LiveConfig.partialIntervalMs],
 *    replacing the previous partial. Partials are skipped whenever audio is waiting, so a slow device
 *    spends its time on finals and the queue cannot grow because of partials.
 * Memory is bounded: [AudioRing] of [LiveConfig.ringMs], a queue capped at [LiveConfig.maxQueueMs]
 * and the last [LiveConfig.latencyKeep] latencies. Only the text lines grow with the recording.
 */
class LiveTranscriber(
    private val clock: LiveClock,
    private val config: LiveConfig = LiveConfig(),
    initial: LiveState = LiveState(),
) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<LiveState> = _state.asStateFlow()

    /** Session-level fields (status, ids, notices). Thread-safe. */
    fun update(block: (LiveState) -> LiveState) = _state.update(block)

    enum class Step { IDLE, WORKED, ENDED }

    private sealed interface Item
    private class Audio(val samples: FloatArray, val startSample: Long, val capturedAtMs: Long) : Item
    private data object Flush : Item
    private data object End : Item

    private val sr = config.sampleRate
    private fun msToSamples(ms: Long) = ms * sr / 1000
    private fun samplesToMs(s: Long) = s * 1000 / sr

    private val queue = LinkedBlockingQueue<Item>()
    private val queuedSamples = AtomicLong()
    private val queuedChunks = AtomicInteger()
    private val maxQueueSamples = msToSamples(config.maxQueueMs)

    // ---- capture side (one thread)
    @Volatile private var captured = 0L
    @Volatile private var dropped = 0L
    @Volatile private var startedAtMs = -1L
    private var level = 0f
    private val maxBacklog = AtomicLong()
    private val maxQueue = AtomicInteger()
    @Volatile private var peakPssMb: Int? = null

    // ---- worker side (one thread)
    private var vad: StreamingVad? = null
    private var recognizer: Recognizer? = null
    private var voices: VoicePrint? = null
    private val speakers = OnlineSpeakers(config.speakerThreshold)
    private val ring = AudioRing(msToSamples(config.ringMs).toInt())
    /** End sample (exclusive) and capture time of recent blocks, to time each update. */
    private val stamps = ArrayDeque<LongArray>()
    private var vadBase = 0L
    private var vadPos = 0L
    private var inSpeech = false
    private var onset = 0L
    private var lastFinalEnd = 0L
    private var lastPartialEnd = 0L
    private var lastPartialAt = Long.MIN_VALUE / 2
    private var lastSpeaker: Int? = null
    private val lines = ArrayList<LiveLine>()
    private var partial: LivePartial? = null
    @Volatile private var covered = 0L
    private val latency = LatencyStats(config.latencyKeep)
    @Volatile private var firstTextAtMs = -1L
    private var decodeMs = 0L
    private var decodedSamples = 0L
    private var busyMs = 0L
    private var partials = 0
    private var finals = 0
    private var skippedPartials = 0

    /** Engines arrive after the models load; audio queues up meanwhile. Call before the first [step]. */
    fun bind(vad: StreamingVad, recognizer: Recognizer, voices: VoicePrint?) {
        require(config.ringMs >= vad.maxSpeechMs + config.prePadMs + config.onsetLookbackMs + 2_000) {
            "ring must hold the longest VAD segment with its padding"
        }
        this.vad = vad
        this.recognizer = recognizer
        this.voices = voices
        _state.update { it.copy(modelsReady = true) }
    }

    // ------------------------------------------------------------ capture side

    /** Takes one block of 16 kHz mono audio. Never blocks: when the worker is >[LiveConfig.maxQueueMs] behind the block is skipped for the live view. */
    fun accept(block: FloatArray) {
        if (block.isEmpty()) return
        val now = clock.nowMs()
        if (startedAtMs < 0) startedAtMs = now - samplesToMs(block.size.toLong())
        val start = captured
        captured = start + block.size
        if (queuedSamples.get() + block.size > maxQueueSamples) {
            dropped += block.size
        } else {
            queuedSamples.addAndGet(block.size.toLong())
            queue.offer(Audio(block, start, now))
            maxQueue.accumulateAndGet(queuedChunks.incrementAndGet(), ::maxOf)
        }
        level = smooth(level, rmsLevel(block))
        val backlog = samplesToMs(captured - covered)
        maxBacklog.accumulateAndGet(backlog, ::maxOf)
        val lv = level
        _state.update { it.copy(elapsedMs = samplesToMs(captured), level = lv, backlogMs = backlog) }
    }

    /** Pause: the open phrase is closed and decoded; nothing is lost at the pause point. */
    fun pause() { queue.offer(Flush) }

    /** No more audio: the worker decodes everything queued, then [step] returns [Step.ENDED]. */
    fun finish() { queue.offer(End) }

    fun notePss(mb: Int) {
        if (mb > (peakPssMb ?: 0)) peakPssMb = mb
    }

    // ------------------------------------------------------------ worker

    /** Processes one queued item. [waitMs] > 0 blocks that long for audio (production loop). */
    fun step(waitMs: Long = 0): Step {
        val item = (if (waitMs > 0) queue.poll(waitMs, TimeUnit.MILLISECONDS) else queue.poll()) ?: return Step.IDLE
        val t0 = clock.nowMs()
        when (item) {
            is Audio -> {
                queuedSamples.addAndGet(-item.samples.size.toLong())
                queuedChunks.decrementAndGet()
                consume(item)
            }
            Flush -> closePhrase()
            End -> {
                closePhrase()
                busyMs += clock.nowMs() - t0
                publish()
                return Step.ENDED
            }
        }
        busyMs += clock.nowMs() - t0
        return Step.WORKED
    }

    private fun consume(a: Audio) {
        val vad = checkNotNull(vad) { "bind() first" }
        if (a.startSample != vadPos) {
            // the queue overflowed before this block: close what we have and continue after the gap
            closePhrase()
            restartAt(a.startSample)
        }
        ring.write(a.samples)
        vadPos += a.samples.size
        stamps.addLast(longArrayOf(vadPos, a.capturedAtMs))
        while (stamps.size > 1 && stamps.first()[0] < ring.start) stamps.removeFirst()

        vad.accept(a.samples)
        drainSegments()
        val speech = vad.isSpeech
        if (speech && !inSpeech) onset = maxOf(vadPos - msToSamples(config.onsetLookbackMs), lastFinalEnd, ring.start)
        inSpeech = speech
        if (!speech && partial != null) { partial = null; publish() }
        if (speech) maybePartial()
        updateCovered()
    }

    private fun drainSegments() {
        val vad = vad ?: return
        val segs = vad.pollSegments()
        segs.forEachIndexed { i, s ->
            val start = vadBase + s.startSample
            val end = minOf(start + s.length, vadPos)
            val next = segs.getOrNull(i + 1)?.let { vadBase + it.startSample }
            val post = when {
                next != null -> minOf(msToSamples(config.postPadMs), maxOf(0, next - end) / 2)
                vad.isSpeech -> 0 // cut at max length while speech goes on: the next segment starts right here
                else -> minOf(msToSamples(config.postPadMs), vadPos - end)
            }
            emitFinal(start, end + post)
        }
    }

    private fun emitFinal(segStart: Long, segEnd: Long) {
        val pad = minOf(msToSamples(config.prePadMs), maxOf(0, segStart - lastFinalEnd) / 2)
        val from = maxOf(segStart - pad, lastFinalEnd, ring.start)
        val to = maxOf(from, segEnd)
        lastFinalEnd = to
        onset = maxOf(onset, to)
        partial = null
        if (to - from < msToSamples(config.minFinalMs)) { publish(); return }
        val samples = ring.read(from, to)
        val t0 = clock.nowMs()
        val res = checkNotNull(recognizer).recognize(samples, sr)
        val now = clock.nowMs()
        decodeMs += now - t0
        decodedSamples += samples.size
        finals++
        val speaker = speakerOf(samples)
        if (res.text.isNotBlank()) {
            lines += LiveLine(samplesToMs(from), samplesToMs(to), res.text.trim(), res.lang, speaker)
            latency.add(now - captureTimeOf(to - 1))
            markFirstText(now)
        }
        publish()
    }

    private fun speakerOf(samples: FloatArray): Int? {
        val v = voices ?: return null
        if (samples.size < msToSamples(config.minSpeakerMs)) return lastSpeaker
        return speakers.assign(v.embed(samples)).also { lastSpeaker = it }
    }

    private fun maybePartial() {
        val now = clock.nowMs()
        if (now - lastPartialAt < config.partialIntervalMs) return
        val from = maxOf(onset, lastFinalEnd, ring.start)
        val to = vadPos
        if (to - from < msToSamples(config.minPartialMs)) return
        if (queuedChunks.get() > config.busyQueueChunks) {
            // behind: this interval gets no partial, the time goes to catching up
            skippedPartials++
            lastPartialAt = now
            return
        }
        val samples = ring.read(from, to)
        val res = checkNotNull(recognizer).recognize(samples, sr)
        val done = clock.nowMs()
        decodeMs += done - now
        decodedSamples += samples.size
        partials++
        lastPartialAt = done
        lastPartialEnd = to
        val text = res.text.trim()
        if (text.isNotEmpty()) {
            partial = LivePartial(samplesToMs(from), samplesToMs(to), text, res.lang)
            latency.add(done - captureTimeOf(to - 1))
            markFirstText(done)
        }
        publish()
    }

    /** Closes the open phrase (pause, gap, end) and starts the VAD afresh at the current position. */
    private fun closePhrase() {
        val vad = vad ?: return
        vad.flush()
        drainSegments()
        vad.reset()
        vadBase = vadPos
        inSpeech = false
        if (partial != null) partial = null
        updateCovered()
        publish()
    }

    private fun restartAt(sample: Long) {
        ring.reset(sample)
        stamps.clear()
        vadPos = sample
        vadBase = sample
        lastFinalEnd = maxOf(lastFinalEnd, sample)
        onset = sample
    }

    private fun updateCovered() {
        covered = if (inSpeech) maxOf(onset, lastPartialEnd, lastFinalEnd).coerceAtMost(vadPos) else vadPos
    }

    private fun captureTimeOf(sample: Long): Long {
        for (s in stamps) if (s[0] > sample) return s[1]
        return stamps.lastOrNull()?.get(1) ?: clock.nowMs()
    }

    private fun markFirstText(now: Long) {
        if (firstTextAtMs < 0) firstTextAtMs = now
    }

    private fun publish() {
        val l = lines.toList()
        val p = partial
        val backlog = samplesToMs(captured - covered)
        maxBacklog.accumulateAndGet(backlog, ::maxOf)
        val m = metrics()
        _state.update { it.copy(lines = l, partial = p, backlogMs = backlog, metrics = m) }
    }

    /** Current measurements; the session logs and stores them at Stop. */
    fun metrics(): LiveMetrics = LiveMetrics(
        audioMs = samplesToMs(captured),
        timeToFirstTextMs = if (firstTextAtMs >= 0 && startedAtMs >= 0) firstTextAtMs - startedAtMs else null,
        latencyMedianMs = latency.percentile(0.5),
        latencyP95Ms = latency.percentile(0.95),
        latencyCount = latency.count,
        decodeRtf = if (decodedSamples > 0) decodeMs.toFloat() / samplesToMs(decodedSamples).coerceAtLeast(1) else null,
        speedX = if (busyMs > 0) samplesToMs(vadPos).toFloat() / busyMs else null,
        maxBacklogMs = maxBacklog.get(),
        maxQueueChunks = maxQueue.get(),
        peakPssMb = peakPssMb,
        partials = partials,
        finals = finals,
        skippedPartials = skippedPartials,
        droppedAudioMs = samplesToMs(dropped),
    )

    /** Lines confirmed so far (worker thread, or after the worker ended). */
    fun lines(): List<LiveLine> = lines.toList()

    /** Audio blocks waiting for the worker right now. */
    val queueLength: Int get() = queuedChunks.get()

    companion object {
        /** RMS mapped from -60..0 dBFS to 0..1. */
        fun rmsLevel(x: FloatArray): Float {
            var s = 0.0
            for (v in x) s += v * v
            val rms = sqrt(s / x.size)
            if (rms <= 1e-6) return 0f
            return ((20 * log10(rms) + 60) / 60).toFloat().coerceIn(0f, 1f)
        }

        /** Fast attack, slow release, so the meter does not flicker between words. */
        fun smooth(prev: Float, next: Float): Float = if (next > prev) next else prev * 0.7f + next * 0.3f
    }
}
