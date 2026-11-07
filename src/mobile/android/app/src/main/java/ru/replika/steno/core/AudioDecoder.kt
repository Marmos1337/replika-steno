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

/**
 * Mono float PCM at [SAMPLE_RATE], ready for the models. [samples] may end with a short tail of
 * zeros (the buffer is sized from the declared duration), which is silence for every model.
 */
class DecodedAudio(
    val samples: FloatArray,
    /** Number of real decoded samples; anything after it in [samples] is the zero tail. */
    private val decodedSamples: Int = samples.size,
    private val containerDurationMs: Long = 0,
) {
    /**
     * Length used for all time bounds. Decoders may emit a few extra samples (AAC priming/padding),
     * so it never exceeds the duration the file itself declares.
     */
    val durationMs: Long
        get() {
            val decoded = decodedSamples * 1000L / SAMPLE_RATE
            return if (containerDurationMs > 0) minOf(decoded, containerDurationMs) else decoded
        }

    companion object {
        const val SAMPLE_RATE = 16_000
        /** Longer files would not fit the app's heap as 16 kHz floats (2.5 h = 576 MB). */
        const val MAX_DURATION_MS = 150L * 60 * 1000
    }
}

/**
 * Decodes any format the platform can read (mp3, m4a/aac, ogg/opus, flac, wav, amr, 3gp...)
 * into 16 kHz mono float samples. Uses only MediaExtractor + MediaCodec, so nothing leaves the device.
 * Downmixing and resampling happen while decoding, so only the 16 kHz signal is ever held in memory.
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
            if (durationUs / 1000 > DecodedAudio.MAX_DURATION_MS) {
                throw AudioDecodeException("Запись длиннее 2,5 часа. Разделите её на части и обработайте по очереди.")
            }

            var srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmFloat = false
            val out = FloatArrayBuilder(
                if (durationUs > 0) (durationUs * DecodedAudio.SAMPLE_RATE / 1_000_000L).toInt() + DecodedAudio.SAMPLE_RATE else 1 shl 20,
            )
            var resampler = StreamResampler(srcRate, DecodedAudio.SAMPLE_RATE, out)

            var codec: MediaCodec? = null
            try {
                // assigned before configure/start so the finally below releases it if they throw
                val c = try { MediaCodec.createDecoderByType(mime) } catch (e: Exception) {
                    throw AudioDecodeException("Формат «$mime» не поддерживается этим устройством.", e)
                }
                codec = c
                try {
                    c.configure(format, null, null, 0)
                    c.start()
                } catch (e: Exception) {
                    throw AudioDecodeException("Формат «$mime» не поддерживается этим устройством.", e)
                }
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                while (!outputDone) {
                    coroutineContext.ensureActive()
                    if (!inputDone) {
                        val inIdx = c.dequeueInputBuffer(1_000)
                        if (inIdx >= 0) {
                            val buf = c.getInputBuffer(inIdx)!!
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                c.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                if (durationUs > 0) onProgress((extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                                extractor.advance()
                            }
                        }
                    }
                    val outIdx = c.dequeueOutputBuffer(info, 1_000)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val of = c.outputFormat
                            val rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            pcmFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                of.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                            if (rate != srcRate) {
                                srcRate = rate
                                resampler = StreamResampler(srcRate, DecodedAudio.SAMPLE_RATE, out)
                            }
                        }
                        outIdx >= 0 -> {
                            val buf = c.getOutputBuffer(outIdx)!!.order(ByteOrder.LITTLE_ENDIAN)
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            if (pcmFloat) {
                                val fb = buf.asFloatBuffer()
                                while (fb.remaining() >= channels) {
                                    var s = 0f
                                    repeat(channels) { s += fb.get() }
                                    resampler.push(s / channels)
                                }
                            } else {
                                val sb = buf.asShortBuffer()
                                while (sb.remaining() >= channels) {
                                    var s = 0f
                                    repeat(channels) { s += sb.get() / 32768f }
                                    resampler.push(s / channels)
                                }
                            }
                            c.releaseOutputBuffer(outIdx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // CancellationException is an IllegalStateException: never report it as a broken file
            } catch (e: IllegalStateException) {
                throw AudioDecodeException("Файл повреждён: декодер не смог его прочитать.", e)
            } finally {
                codec?.let { runCatching { it.stop() }; it.release() }
            }

            if (out.size == 0) throw AudioDecodeException("Файл не содержит звука.")
            onProgress(1f)
            return DecodedAudio(out.result(), out.size, durationUs / 1000)
        } finally {
            extractor.release()
        }
    }

    /**
     * Only [startMs, endMs) of the file, for re-recognizing one utterance. The extractor seeks close to
     * [startMs] and decoding stops right after [endMs], so a fragment of a 2.5 h recording costs about
     * as much time and memory as the fragment itself. Times come from the decoder's presentation
     * timestamps, which match the sample count of [decode] up to the codec's priming (a few tens of ms).
     */
    suspend fun decodeRange(context: Context, uri: Uri, startMs: Long, endMs: Long): FloatArray {
        require(endMs > startMs) { "empty range" }
        val startUs = startMs * 1000
        val endUs = endMs * 1000
        val extractor = MediaExtractor()
        try {
            try {
                extractor.setDataSource(context, uri, null)
            } catch (e: Exception) {
                throw AudioDecodeException("Не удалось открыть звук записи.", e)
            }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw AudioDecodeException("В файле нет звуковой дорожки.")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            var srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmFloat = false
            val out = FloatArrayBuilder(((endMs - startMs) * DecodedAudio.SAMPLE_RATE / 1000).toInt() + DecodedAudio.SAMPLE_RATE / 10)
            var resampler = StreamResampler(srcRate, DecodedAudio.SAMPLE_RATE, out)

            var codec: MediaCodec? = null
            try {
                val c = try { MediaCodec.createDecoderByType(mime) } catch (e: Exception) {
                    throw AudioDecodeException("Формат «$mime» не поддерживается этим устройством.", e)
                }
                codec = c
                try {
                    c.configure(format, null, null, 0)
                    c.start()
                } catch (e: Exception) {
                    throw AudioDecodeException("Формат «$mime» не поддерживается этим устройством.", e)
                }
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                while (!outputDone) {
                    coroutineContext.ensureActive()
                    if (!inputDone) {
                        val inIdx = c.dequeueInputBuffer(1_000)
                        if (inIdx >= 0) {
                            val buf = c.getInputBuffer(inIdx)!!
                            val size = extractor.readSampleData(buf, 0)
                            // a little past the end, so the decoder's delay never cuts the last frames
                            if (size < 0 || extractor.sampleTime > endUs + 500_000) {
                                c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                c.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIdx = c.dequeueOutputBuffer(info, 1_000)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val of = c.outputFormat
                            val rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            pcmFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                of.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                            if (rate != srcRate) {
                                srcRate = rate
                                resampler = StreamResampler(srcRate, DecodedAudio.SAMPLE_RATE, out)
                            }
                        }
                        outIdx >= 0 -> {
                            val buf = c.getOutputBuffer(outIdx)!!.order(ByteOrder.LITTLE_ENDIAN)
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            var frame = 0L
                            var pastEnd = false
                            fun take(x: Float) {
                                val tUs = info.presentationTimeUs + frame * 1_000_000L / srcRate
                                frame++
                                if (tUs >= endUs) pastEnd = true else if (tUs >= startUs) resampler.push(x)
                            }
                            if (pcmFloat) {
                                val fb = buf.asFloatBuffer()
                                while (!pastEnd && fb.remaining() >= channels) {
                                    var x = 0f
                                    repeat(channels) { x += fb.get() }
                                    take(x / channels)
                                }
                            } else {
                                val sb = buf.asShortBuffer()
                                while (!pastEnd && sb.remaining() >= channels) {
                                    var x = 0f
                                    repeat(channels) { x += sb.get() / 32768f }
                                    take(x / channels)
                                }
                            }
                            c.releaseOutputBuffer(outIdx, false)
                            if (pastEnd || info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                throw AudioDecodeException("Файл повреждён: декодер не смог его прочитать.", e)
            } finally {
                codec?.let { runCatching { it.stop() }; it.release() }
            }
            if (out.size == 0) throw AudioDecodeException("В этом месте записи нет звука.")
            return out.result(exact = true)
        } finally {
            extractor.release()
        }
    }

    /** Whole-array resampling with the same filter as the streaming path (used by tests and tools). */
    fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to) return input
        val out = FloatArrayBuilder(input.size.toLong().times(to).div(from).toInt() + 2)
        val r = StreamResampler(from, to, out)
        for (x in input) r.push(x)
        return out.result(exact = true)
    }
}

/**
 * Streaming resampler: moving-average low-pass sized to the decimation ratio (limits aliasing when
 * going down from 44.1/48 kHz), then linear interpolation at the output rate.
 */
private class StreamResampler(from: Int, to: Int, private val out: FloatArrayBuilder) {
    private val ratio = from.toDouble() / to
    private val win = if (from > to) (from / to).coerceAtLeast(1) else 1
    private val ring = FloatArray(win)
    private var acc = 0.0
    private var count = 0L        // input samples seen
    private var prev = 0f
    private var nextPos = 0.0     // source position of the next output sample

    fun push(x: Float) {
        val slot = (count % win).toInt()
        acc += x - ring[slot]
        ring[slot] = x
        val y = (acc / minOf(count + 1, win.toLong())).toFloat()
        val g = count.toDouble()
        if (count == 0L) prev = y
        while (nextPos <= g) {
            val frac = (nextPos - (g - 1)).toFloat().coerceIn(0f, 1f)
            out.add(prev + (y - prev) * frac)
            nextPos += ratio
        }
        prev = y
        count++
    }
}

private class FloatArrayBuilder(initial: Int) {
    private var data = FloatArray(initial.coerceAtLeast(1024))
    var size = 0
        private set

    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(data.size + data.size / 2)
        data[size++] = v
    }

    /**
     * The buffer was sized from the declared duration, so it is normally only slightly longer than
     * the signal: the zero tail is returned as is to avoid a second copy of a long recording.
     * Copies only when asked or when the estimate was far off (more than 10 s of slack).
     */
    fun result(exact: Boolean = false): FloatArray =
        if (exact || data.size - size > 10 * DecodedAudio.SAMPLE_RATE) data.copyOf(size) else data
}
