package ru.replika.steno.core

/** A speech region from the diarizer, in samples-independent milliseconds. [cluster] is the raw cluster index. */
data class SpeakerTurn(val startMs: Long, val endMs: Long, val cluster: Int, val confidence: Float? = null)

object Segments {

    /**
     * Normalizes raw diarizer output:
     *  - clamps to [0, durationMs], drops empty or sub-[minMs] slivers,
     *  - merges consecutive turns of the same cluster separated by less than [joinGapMs],
     *  - resolves overlaps so the export is a clean timeline (later turn starts where the earlier ends).
     */
    fun normalize(turns: List<SpeakerTurn>, durationMs: Long, minMs: Long = 250, joinGapMs: Long = 600): List<SpeakerTurn> {
        val sorted = turns
            .map { it.copy(startMs = it.startMs.coerceIn(0, durationMs), endMs = it.endMs.coerceIn(0, durationMs)) }
            .filter { it.endMs - it.startMs >= minMs }
            .sortedBy { it.startMs }
        val out = ArrayList<SpeakerTurn>()
        for (t in sorted) {
            val last = out.lastOrNull()
            if (last != null && last.cluster == t.cluster && t.startMs - last.endMs < joinGapMs) {
                out[out.size - 1] = last.copy(endMs = maxOf(last.endMs, t.endMs), confidence = minConf(last.confidence, t.confidence))
                continue
            }
            if (last != null && t.startMs < last.endMs) {
                // overlap between different speakers: keep both, trim the start of the newer one
                val start = last.endMs
                if (t.endMs - start >= minMs) out += t.copy(startMs = start)
                continue
            }
            out += t
        }
        return out
    }

    private fun minConf(a: Float?, b: Float?): Float? = if (a == null) b else if (b == null) a else minOf(a, b)

    /** Maps raw cluster ids to speaker_1, speaker_2... in order of first appearance. */
    fun speakerIds(turns: List<SpeakerTurn>): Map<Int, String> {
        val ids = LinkedHashMap<Int, String>()
        for (t in turns) if (t.cluster !in ids) ids[t.cluster] = "speaker_${ids.size + 1}"
        return ids
    }

    /**
     * Splits a turn longer than [maxMs] into pieces for the recognizer, cutting at the quietest
     * 100 ms frame inside the last third of each window so words are not chopped in half.
     */
    fun splitForAsr(turn: SpeakerTurn, samples: FloatArray, sampleRate: Int, maxMs: Long = 20_000): List<SpeakerTurn> {
        if (turn.endMs - turn.startMs <= maxMs) return listOf(turn)
        val out = ArrayList<SpeakerTurn>()
        var start = turn.startMs
        while (turn.endMs - start > maxMs) {
            val searchFrom = start + maxMs * 2 / 3
            val searchTo = start + maxMs
            val cut = quietestPoint(samples, sampleRate, searchFrom, searchTo)
            out += turn.copy(startMs = start, endMs = cut)
            start = cut
        }
        out += turn.copy(startMs = start)
        return out
    }

    private fun quietestPoint(samples: FloatArray, sr: Int, fromMs: Long, toMs: Long): Long {
        val frame = sr / 10
        var bestMs = toMs
        var bestEnergy = Double.MAX_VALUE
        var ms = fromMs
        while (ms + 100 <= toMs) {
            val a = (ms * sr / 1000).toInt().coerceIn(0, samples.size)
            val b = (a + frame).coerceAtMost(samples.size)
            var e = 0.0
            for (i in a until b) e += samples[i] * samples[i]
            if (e < bestEnergy) { bestEnergy = e; bestMs = ms + 50 }
            ms += 50
        }
        return bestMs
    }

    fun slice(samples: FloatArray, sampleRate: Int, startMs: Long, endMs: Long): FloatArray {
        val a = (startMs * sampleRate / 1000).toInt().coerceIn(0, samples.size)
        val b = (endMs * sampleRate / 1000).toInt().coerceIn(a, samples.size)
        return samples.copyOfRange(a, b)
    }
}
