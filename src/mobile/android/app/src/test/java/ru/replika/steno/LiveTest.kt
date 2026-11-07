package ru.replika.steno

import java.io.File
import kotlin.math.roundToInt
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.replika.steno.core.AsrResult
import ru.replika.steno.core.Recognizer
import ru.replika.steno.core.Recording
import ru.replika.steno.core.live.AudioRing
import ru.replika.steno.core.live.LatencyStats
import ru.replika.steno.core.live.LiveClock
import ru.replika.steno.core.live.LiveConfig
import ru.replika.steno.core.live.LiveLine
import ru.replika.steno.core.live.LiveMetrics
import ru.replika.steno.core.live.LiveTranscriber
import ru.replika.steno.core.live.OnlineSpeakers
import ru.replika.steno.core.live.StreamingVad
import ru.replika.steno.core.live.VadSegment
import ru.replika.steno.data.RecordingStore
import ru.replika.steno.data.WavFile
import ru.replika.steno.data.WavWriter

/**
 * Live engine on fakes. Each 100 ms speech chunk carries its own index as the sample value, and the
 * fake recognizer answers with one word per distinct value it sees, so duplicated or lost audio
 * shows up as duplicated or missing words.
 */
class LiveTest {

    private class FakeClock : LiveClock {
        var now = 0L
        override fun nowMs() = now
    }

    /** Sherpa-like: a phrase opens on the first non-zero sample, closes after [minSilence] of zeros, is cut at [maxSpeech]. */
    private class FakeVad(private val minSilence: Int = 4_800, private val maxSpeech: Int = 160_000) : StreamingVad {
        private var n = 0L
        private var open = -1L
        private var silence = 0
        private val out = ArrayList<VadSegment>()
        override val maxSpeechMs = maxSpeech / 16L
        override val isSpeech get() = open >= 0
        override fun accept(samples: FloatArray) {
            val speech = samples.any { it != 0f }
            if (speech) {
                if (open < 0) open = n
                silence = 0
            } else if (open >= 0) {
                silence += samples.size
            }
            n += samples.size
            if (open >= 0 && silence >= minSilence) { out += VadSegment(open, (n - silence - open).toInt()); open = -1; silence = 0 }
            if (open >= 0 && n - silence - open >= maxSpeech) { out += VadSegment(open, (n - silence - open).toInt()); open = n - silence }
        }
        override fun pollSegments(): List<VadSegment> = out.toList().also { out.clear() }
        override fun flush() {
            if (open >= 0) out += VadSegment(open, (n - silence - open).toInt())
            open = -1; silence = 0
        }
        override fun reset() { n = 0; open = -1; silence = 0; out.clear() }
    }

    private class FakeAsr(private val clock: FakeClock, private val cost: (audioMs: Long) -> Long) : Recognizer {
        var calls = 0
        override fun recognize(samples: FloatArray, sampleRate: Int): AsrResult {
            calls++
            clock.now += cost(samples.size * 1000L / sampleRate)
            return AsrResult(wordsOf(samples).joinToString(" "))
        }
    }

    companion object {
        fun wordsOf(samples: FloatArray): List<String> {
            val words = ArrayList<String>()
            var last = 0
            for (v in samples) {
                val id = (v * 1e5).roundToInt()
                if (id != 0 && id != last) { words += "w$id"; last = id }
            }
            return words
        }

        fun chunk(index: Int, speech: Boolean) = FloatArray(1_600) { if (speech) (index + 1) * 1e-5f else 0f }

        /** Words the speech chunks of [pattern] must produce, in order. */
        fun expected(pattern: List<Boolean>) = pattern.indices.filter { pattern[it] }.map { "w${it + 1}" }
    }

    /**
     * Real-time producer against a worker whose decode time is simulated on the same clock: chunks
     * arrive every 100 ms of clock time, decodes move the clock forward. Returns the largest queue seen.
     */
    private fun simulate(t: LiveTranscriber, clock: FakeClock, pattern: List<Boolean>, pauseAt: Set<Int> = emptySet()): Int {
        var next = 0
        var maxQueue = 0
        var ended = false
        while (true) {
            while (next < pattern.size && (next + 1) * 100L <= clock.now) {
                if (next in pauseAt) t.pause()
                t.accept(chunk(next, pattern[next]))
                next++
            }
            maxQueue = maxOf(maxQueue, t.queueLength)
            if (next == pattern.size && !ended) { t.finish(); ended = true }
            when (t.step()) {
                LiveTranscriber.Step.ENDED -> return maxQueue
                LiveTranscriber.Step.IDLE -> clock.now = (next + 1) * 100L
                LiveTranscriber.Step.WORKED -> Unit
            }
        }
    }

    private fun lineWords(lines: List<LiveLine>) = lines.flatMap { it.text.split(" ").filter(String::isNotBlank) }

    private fun assertDisjoint(lines: List<LiveLine>) {
        for (i in lines.indices) {
            assertTrue(lines[i].startMs < lines[i].endMs)
            if (i > 0) assertTrue("line $i overlaps", lines[i].startMs >= lines[i - 1].endMs)
        }
    }

    @Test fun finalsCoverEverySpeechChunkExactlyOnceAcrossVadCuts() {
        // 35 s of non-stop speech (cut by the VAD at 10 s) then phrases with short pauses
        val pattern = List(350) { true } + List(10) { false } + List(400) { (it % 25) < 20 } + List(10) { false }
        val clock = FakeClock()
        val t = LiveTranscriber(clock)
        val asr = FakeAsr(clock) { it / 20 }
        t.bind(FakeVad(), asr, null)
        simulate(t, clock, pattern)
        val lines = t.state.value.lines
        assertEquals(expected(pattern), lineWords(lines))
        assertDisjoint(lines)
        assertNull(t.state.value.partial)
        assertTrue(t.metrics().partials > 0)
    }

    @Test fun partialIsReplacedByFinal() {
        val clock = FakeClock()
        val t = LiveTranscriber(clock)
        t.bind(FakeVad(), FakeAsr(clock) { 10 }, null)
        var sawPartial = false
        for (i in 0 until 30) {
            clock.now += 100
            t.accept(chunk(i, true))
            t.step()
            t.state.value.partial?.let { sawPartial = true; assertTrue(it.text.isNotBlank()) }
        }
        assertTrue(sawPartial)
        assertTrue(t.state.value.lines.isEmpty())
        for (i in 30 until 40) { clock.now += 100; t.accept(chunk(i, false)); t.step() }
        assertNull(t.state.value.partial)
        assertEquals(1, t.state.value.lines.size)
        assertEquals(expected(List(30) { true }), lineWords(t.state.value.lines))
        val m = t.metrics()
        assertTrue(m.timeToFirstTextMs != null && m.timeToFirstTextMs!! <= 1_000)
        assertTrue(m.latencyCount > 0)
    }

    @Test fun slowRecognizerKeepsQueueBoundedOverFifteenMinutes() {
        // 15.5 min: 4 s phrases, 1 s pauses. Decoding takes half the audio length + 100 ms: repeated
        // partials of a growing phrase would not keep up, finals alone do.
        val pattern = List(9_300) { (it % 50) < 40 }
        val clock = FakeClock()
        val t = LiveTranscriber(clock)
        t.bind(FakeVad(), FakeAsr(clock) { it / 2 + 100 }, null)
        val maxQueue = simulate(t, clock, pattern)
        val m = t.metrics()
        assertEquals(expected(pattern), lineWords(t.state.value.lines))
        assertEquals(0L, m.droppedAudioMs)
        assertTrue("queue grew to $maxQueue chunks", maxQueue <= 60)
        assertTrue(m.maxQueueChunks <= 60)
        assertTrue(m.skippedPartials > 0)
        assertTrue(m.partials > 0)
        assertEquals(930_000L, m.audioMs)
        // the run ends no later than a few seconds after the audio does
        assertTrue("finished at ${clock.now}", clock.now <= 930_000L + 10_000)
    }

    @Test fun pauseClosesPhraseAndTimelineSkipsPausedTime() {
        val pattern = List(20) { true } + List(20) { true } + List(10) { false }
        val clock = FakeClock()
        val t = LiveTranscriber(clock)
        t.bind(FakeVad(), FakeAsr(clock) { 10 }, null)
        // the pause happens before chunk 20; wall time spent paused is not audio and never reaches the engine
        simulate(t, clock, pattern, pauseAt = setOf(20))
        val lines = t.state.value.lines
        assertEquals(2, lines.size)
        assertEquals(expected(pattern), lineWords(lines))
        assertDisjoint(lines)
        assertTrue(lines[0].endMs <= 2_000)
        assertTrue(lines[1].startMs in 1_700L..2_000L)
        assertTrue(lines[1].endMs <= 5_000)
    }

    @Test fun overflowSkipsAudioForLiveViewOnly() {
        val clock = FakeClock()
        val t = LiveTranscriber(clock, LiveConfig(maxQueueMs = 1_000))
        t.bind(FakeVad(), FakeAsr(clock) { 10 }, null)
        val pattern = List(40) { (it % 10) < 6 }
        pattern.forEachIndexed { i, s -> clock.now += 100; t.accept(chunk(i, s)) }
        t.finish()
        while (t.step() != LiveTranscriber.Step.ENDED) Unit
        val m = t.metrics()
        assertEquals(3_000L, m.droppedAudioMs)
        assertEquals(4_000L, m.audioMs)
        assertDisjoint(t.state.value.lines)
        // what was queued is transcribed, nothing twice
        val words = lineWords(t.state.value.lines)
        assertEquals(words.distinct(), words)
        assertEquals(expected(pattern).take(6), words)
    }

    @Test fun percentilesByNearestRank() {
        val data = longArrayOf(500, 100, 300, 200, 400)
        assertEquals(300L, LatencyStats.percentile(data, 0.5))
        assertEquals(500L, LatencyStats.percentile(data, 0.95))
        assertEquals(100L, LatencyStats.percentile(data, 0.0))
        assertNull(LatencyStats.percentile(LongArray(0), 0.5))
        val s = LatencyStats(keep = 100)
        for (i in 1..1_000) s.add(i.toLong())
        assertEquals(100, s.count) // bounded: only the last 100 count
        assertEquals(950L, s.percentile(0.5))
        assertEquals(995L, s.percentile(0.95))
    }

    @Test fun ringKeepsOnlyTheLastWindow() {
        val r = AudioRing(10)
        r.write(FloatArray(25) { it.toFloat() })
        assertEquals(15L, r.start)
        assertEquals(25L, r.end)
        assertEquals(listOf(15f, 16f, 17f), r.read(0, 18).toList())
        assertEquals(0, r.read(30, 40).size)
    }

    @Test fun onlineSpeakersGroupByVoice() {
        val s = OnlineSpeakers(0.6f)
        assertEquals(1, s.assign(floatArrayOf(1f, 0f, 0f)))
        assertEquals(1, s.assign(floatArrayOf(0.9f, 0.2f, 0f)))
        assertEquals(2, s.assign(floatArrayOf(0f, 1f, 0f)))
        assertEquals(1, s.assign(floatArrayOf(2f, 0.1f, 0f)))
        assertEquals(3, s.assign(floatArrayOf(0f, 0f, -1f)))
        assertEquals(3, s.count)
    }

    @Test fun shortPhrasesKeepThePreviousVoice() {
        val clock = FakeClock()
        val t = LiveTranscriber(clock)
        // voice by loudness sign: chunks 0..29 one voice, the rest another
        var embeds = 0
        t.bind(FakeVad(), FakeAsr(clock) { 10 }) { x -> embeds++; if (x.maxOrNull()!! < 31e-5f) floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f) }
        val pattern = List(20) { true } + List(10) { false } + List(5) { true } + List(10) { false } + List(20) { true } + List(10) { false }
        simulate(t, clock, pattern)
        val lines = t.state.value.lines
        assertEquals(3, lines.size)
        assertEquals(listOf(1, 1, 2), lines.map { it.speaker })
        assertEquals(2, embeds) // the 0.5 s phrase is too short for an embedding
        assertTrue(lines.all { it.speakerProvisional })
    }

    @Test fun wavHeaderWrittenAndRepairedAfterCrash() {
        val f = File.createTempFile("live", ".wav")
        try {
            WavWriter(f).use { w -> w.write(ShortArray(1_000) { it.toShort() }) }
            assertEquals(2_000L, WavFile.declaredDataBytes(f))
            assertEquals(1_000L, WavFile.repair(f))
            val bytes = f.readBytes()
            assertEquals("RIFF", String(bytes, 0, 4)); assertEquals("WAVE", String(bytes, 8, 4))
            assertEquals(44 + 2_000, bytes.size)
            assertEquals(999, (bytes[44 + 2 * 999 + 1].toInt() shl 8) or (bytes[44 + 2 * 999].toInt() and 0xff)) // little-endian PCM16

            // killed mid-recording: the header still says the size of the last sync
            val w = WavWriter(f)
            w.write(ShortArray(100_000))
            w.write(ShortArray(3_000))
            assertEquals(200_000L, WavFile.declaredDataBytes(f)) // synced every 5 s of audio
            java.io.RandomAccessFile(f, "rw").use { it.seek(it.length()); it.write(7) } // torn last sample
            assertEquals(103_000L, WavFile.repair(f))
            assertEquals(206_000L, WavFile.declaredDataBytes(f))
            assertEquals(44L + 206_000, f.length())
            w.close()
            val junk = File.createTempFile("junk", ".m4a").apply { writeText("not a wav file at all, but long enough for a header") }
            assertNull(WavFile.repair(junk))
            junk.delete()
        } finally {
            f.delete()
        }
    }

    @Test fun liveDraftAndMetricsRoundTripAndOldJsonStillLoads() {
        val rec = Recording(
            "id1", "t", "live", 1L, 5_000,
            liveDraft = listOf(LiveLine(0, 1_200, "привет", "ru", 1), LiveLine(1_300, 2_000, "hello", "en", null)),
            liveMetrics = LiveMetrics(audioMs = 5_000, timeToFirstTextMs = 900, latencyMedianMs = 700, latencyP95Ms = 1_400, latencyCount = 12, decodeRtf = 0.1f, peakPssMb = 400),
        )
        val back = RecordingStore.fromJson(JSONObject(RecordingStore.toJson(rec).toString()))
        assertEquals(rec.liveDraft, back.liveDraft)
        assertEquals(rec.liveMetrics, back.liveMetrics)
        val old = RecordingStore.toJson(Recording("id2", "t", "content://x", 1L, 10)).apply { remove("liveDraft"); remove("liveMetrics") }
        val o = RecordingStore.fromJson(old)
        assertTrue(o.liveDraft.isEmpty())
        assertNull(o.liveMetrics)
    }
}
