package ru.replika.steno.data

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * PCM16 mono WAV written while recording. The size fields in the header are refreshed every
 * [SYNC_SAMPLES] and on [close]; a file cut short by a crash is fixed by [WavFile.repair].
 * MediaExtractor reads such files as audio/raw, so the normal pipeline decodes them like any import.
 */
class WavWriter(file: File, private val sampleRate: Int = 16_000) : Closeable {
    private val raf = RandomAccessFile(file, "rw").apply {
        setLength(0)
        write(WavFile.header(0, sampleRate))
    }
    private var bytes = ByteArray(0)
    private var lastSync = 0L
    @Volatile var samples = 0L
        private set

    /** Throws IOException when the disk is full; what was written before stays readable. */
    @Synchronized
    fun write(pcm: ShortArray, n: Int = pcm.size) {
        if (bytes.size < n * 2) bytes = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = pcm[i].toInt()
            bytes[2 * i] = v.toByte()
            bytes[2 * i + 1] = (v shr 8).toByte()
        }
        raf.write(bytes, 0, n * 2)
        samples += n
        if (samples - lastSync >= SYNC_SAMPLES) sync()
    }

    /** Writes the current sizes into the header, so the file is valid up to here. */
    @Synchronized
    fun sync() {
        val pos = raf.filePointer
        WavFile.writeSizes(raf, samples * 2)
        raf.seek(pos)
        lastSync = samples
    }

    @Synchronized
    override fun close() {
        runCatching { sync() }
        raf.close()
    }

    companion object {
        private const val SYNC_SAMPLES = 16_000L * 5
    }
}

object WavFile {
    const val HEADER = 44

    fun header(dataBytes: Long, sampleRate: Int): ByteArray {
        val b = ByteArray(HEADER)
        fun str(at: Int, s: String) = s.forEachIndexed { i, c -> b[at + i] = c.code.toByte() }
        fun int(at: Int, v: Long) { for (i in 0 until 4) b[at + i] = (v shr (8 * i)).toByte() }
        fun short(at: Int, v: Int) { b[at] = v.toByte(); b[at + 1] = (v shr 8).toByte() }
        str(0, "RIFF"); int(4, 36 + dataBytes); str(8, "WAVE")
        str(12, "fmt "); int(16, 16); short(20, 1); short(22, 1)
        int(24, sampleRate.toLong()); int(28, sampleRate * 2L); short(32, 2); short(34, 16)
        str(36, "data"); int(40, dataBytes)
        return b
    }

    internal fun writeSizes(raf: RandomAccessFile, dataBytes: Long) {
        raf.seek(4); raf.write(le(36 + dataBytes))
        raf.seek(40); raf.write(le(dataBytes))
    }

    private fun le(v: Long) = ByteArray(4) { (v shr (8 * it)).toByte() }

    /** Data size the header declares, or null when this is not a WAV written by [WavWriter]. */
    fun declaredDataBytes(file: File): Long? = RandomAccessFile(file, "r").use { raf ->
        if (raf.length() < HEADER) return null
        val h = ByteArray(HEADER)
        raf.readFully(h)
        if (String(h, 0, 4, Charsets.US_ASCII) != "RIFF" || String(h, 8, 4, Charsets.US_ASCII) != "WAVE" ||
            String(h, 36, 4, Charsets.US_ASCII) != "data"
        ) return null
        (0 until 4).fold(0L) { acc, i -> acc or ((h[40 + i].toLong() and 0xff) shl (8 * i)) }
    }

    /**
     * Makes the header match the data actually on disk (a crash leaves it at the last sync), drops
     * a trailing odd byte and cuts anything past [maxSamples]. Returns the number of samples, or null
     * when the file is not our WAV.
     */
    fun repair(file: File, maxSamples: Long = Long.MAX_VALUE): Long? {
        declaredDataBytes(file) ?: return null
        RandomAccessFile(file, "rw").use { raf ->
            var data = raf.length() - HEADER
            if (data % 2 != 0L) data--
            if (data / 2 > maxSamples) data = maxSamples * 2
            if (raf.length() != HEADER + data) raf.setLength(HEADER + data)
            writeSizes(raf, data)
            return data / 2
        }
    }
}
