package ru.replika.steno.core.live

import kotlin.math.ceil
import ru.replika.steno.diar.Clustering

/**
 * The last [capacity] samples of the stream, addressed by absolute sample index. The engine keeps
 * only this window in memory; the full recording goes to disk as it is captured.
 */
class AudioRing(private val capacity: Int) {
    private val buf = FloatArray(capacity)
    private var size = 0
    /** Absolute index just after the newest sample. */
    var end = 0L
        private set
    val start: Long get() = end - size

    fun reset(at: Long) {
        end = at
        size = 0
    }

    fun write(x: FloatArray) {
        for (v in x) {
            buf[(end % capacity).toInt()] = v
            end++
        }
        size = minOf(capacity, size + x.size)
    }

    /** Samples in [from, to), clamped to what the window still holds. */
    fun read(from: Long, to: Long): FloatArray {
        val a = maxOf(from, start)
        val b = minOf(to, end)
        if (b <= a) return FloatArray(0)
        return FloatArray((b - a).toInt()) { buf[((a + it) % capacity).toInt()] }
    }
}

/** Latencies of the last [keep] updates; percentiles by nearest rank. */
class LatencyStats(private val keep: Int = 5_000) {
    private val values = LongArray(keep)
    private var n = 0L
    val count: Int get() = minOf(n, keep.toLong()).toInt()

    fun add(ms: Long) {
        values[(n % keep).toInt()] = ms
        n++
    }

    fun percentile(p: Double): Long? = percentile(values.copyOf(count), p)

    companion object {
        /** Nearest-rank percentile: the smallest value with at least [p] of the data at or below it. */
        fun percentile(data: LongArray, p: Double): Long? {
            if (data.isEmpty()) return null
            val sorted = data.sortedArray()
            val rank = ceil(p * sorted.size).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }
    }
}

/**
 * Online speaker numbering for the live view: each phrase goes to the nearest running centroid when
 * its cosine distance is within [threshold] (same cut-off as the offline clustering), otherwise it
 * opens a new voice. Good enough as a hint; the final transcript is re-clustered from scratch.
 */
class OnlineSpeakers(private val threshold: Float) {
    private val sums = ArrayList<FloatArray>()

    val count: Int get() = sums.size

    /** Returns a 1-based voice number. */
    fun assign(embedding: FloatArray): Int {
        val e = Clustering.normalize(embedding)
        var best = -1
        var bestDist = Float.MAX_VALUE
        sums.forEachIndexed { i, s ->
            val c = Clustering.normalize(s)
            var dot = 0f
            for (k in e.indices) dot += e[k] * c[k]
            val d = 1f - dot
            if (d < bestDist) { bestDist = d; best = i }
        }
        if (best >= 0 && bestDist <= threshold) {
            val s = sums[best]
            for (k in s.indices) s[k] += e[k]
            return best + 1
        }
        sums += e.copyOf()
        return sums.size
    }
}
