package ru.replika.steno.core

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

class AudioDecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Mono float PCM at [SAMPLE_RATE], ready for the models. */
class DecodedAudio(val samples: FloatArray) {
    val durationMs: Long get() = samples.size * 1000L / SAMPLE_RATE

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}

/**
 * Decodes any format the platform can read (mp3, m4a/aac, ogg/opus, flac, wav, amr, 3gp...)
 * into 16 kHz mono float samples. Uses only MediaExtractor + MediaCodec, so nothing leaves the device.
 */
object AudioDecoder {

    suspend fun decode(context: Context, uri: Uri, onProgress: (Float) -> Unit = {}): DecodedAudio {
        val extractor = MediaExtractor()
        try {
            try {
                extractor.setDataSource(context, uri, null)
            } catch (e: Exception) {
                throw AudioDecodeException("Не удалось открыть файл. Возможно, он повреждён или это не аудио.", e)
            }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw AudioDecodeException("В файле нет звуковой дорожки.")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

            val codec = try {
                MediaCodec.createDecoderByType(mime).apply { configure(format, null, null, 0) }
            } catch (e: Exception) {
                throw AudioDecodeException("Формат «$mime» не поддерживается этим устройством.", e)
            }

            var srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmFloat = false
            val mono = FloatArrayBuilder(if (durationUs > 0) (durationUs / 1_000_000L * srcRate).toInt() + srcRate else 1 shl 20)

            codec.start()
            try {
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                while (!outputDone) {
                    coroutineContext.ensureActive()
                    if (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(1_000)
                        if (inIdx >= 0) {
                            val buf = codec.getInputBuffer(inIdx)!!
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                if (durationUs > 0) onProgress((extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                                extractor.advance()
                            }
                        }
                    }
                    val outIdx = codec.dequeueOutputBuffer(info, 1_000)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val of = codec.outputFormat
                            srcRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            pcmFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                of.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                        }
                        outIdx >= 0 -> {
                            val out = codec.getOutputBuffer(outIdx)!!.order(ByteOrder.LITTLE_ENDIAN)
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            if (pcmFloat) {
                                val fb = out.asFloatBuffer()
                                while (fb.remaining() >= channels) {
                                    var s = 0f
                                    repeat(channels) { s += fb.get() }
                                    mono.add(s / channels)
                                }
                            } else {
                                val sb = out.asShortBuffer()
                                while (sb.remaining() >= channels) {
                                    var s = 0f
                                    repeat(channels) { s += sb.get() / 32768f }
                                    mono.add(s / channels)
                                }
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
            } catch (e: IllegalStateException) {
                throw AudioDecodeException("Файл повреждён: декодер не смог его прочитать.", e)
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }

            val raw = mono.toArray()
            if (raw.isEmpty()) throw AudioDecodeException("Файл не содержит звука.")
            onProgress(1f)
            return DecodedAudio(resample(raw, srcRate, DecodedAudio.SAMPLE_RATE))
        } finally {
            extractor.release()
        }
    }

    /** Windowed-sinc-free but alias-safe resampler: box low-pass when downsampling, then linear interpolation. */
    fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to) return input
        var src = input
        if (from > to) {
            // simple moving-average low-pass sized to the decimation ratio to limit aliasing
            val win = (from / to).coerceAtLeast(1)
            if (win > 1) {
                val out = FloatArray(src.size)
                var acc = 0f
                for (i in src.indices) {
                    acc += src[i]
                    if (i >= win) acc -= src[i - win]
                    out[i] = acc / minOf(i + 1, win)
                }
                src = out
            }
        }
        val outLen = (src.size.toLong() * to / from).toInt()
        val out = FloatArray(outLen)
        val ratio = from.toDouble() / to
        for (i in 0 until outLen) {
            val pos = i * ratio
            val i0 = pos.toInt()
            val frac = (pos - i0).toFloat()
            val a = src[i0.coerceAtMost(src.size - 1)]
            val b = src[(i0 + 1).coerceAtMost(src.size - 1)]
            out[i] = a + (b - a) * frac
        }
        return out
    }
}

private class FloatArrayBuilder(initial: Int) {
    private var data = FloatArray(initial.coerceAtLeast(1024))
    private var size = 0
    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }
    fun toArray(): FloatArray = data.copyOf(size)
}
