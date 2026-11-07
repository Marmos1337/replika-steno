package ru.replika.steno.live

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import ru.replika.steno.core.AudioDecoder

/** A start failure the user can act on; [message] is shown as is. */
class LiveSourceException(message: String) : Exception(message)

/**
 * Where live audio comes from: 16 kHz mono PCM16 in ~100 ms blocks, produced on the source's own
 * thread. The sink must return quickly (it writes to disk and queues for the engine).
 */
interface LiveAudioSource {
    interface Sink {
        fun onAudio(block: ShortArray)
        /** Capture broke off; [message] is for the user. No more audio follows. */
        fun onError(message: String)
        /** The source has no more audio (end of a test file). */
        fun onEnd()
        /** API 29+: the system muted us because another app took the microphone. */
        fun onSilenced(silenced: Boolean) {}
    }

    /** Throws [LiveSourceException] when capture cannot start. */
    fun start(sink: Sink)
    fun pause()
    fun resume()
    /** Stops and joins the capture thread. Safe to call more than once. */
    fun stop()

    companion object {
        const val SAMPLE_RATE = 16_000
        const val BLOCK = SAMPLE_RATE / 10
    }
}

/**
 * Microphone through AudioRecord. VOICE_RECOGNITION gives the unprocessed signal the models were
 * trained on (no AGC/noise suppression); MIC is the fallback for devices that do not offer it.
 * Pause stops the AudioRecord itself, so the system mic indicator goes off while paused.
 */
class MicSource(private val context: Context) : LiveAudioSource {
    private var record: AudioRecord? = null
    private var worker: Thread? = null
    private val lock = Object()
    @Volatile private var running = false
    @Volatile private var paused = false
    private var callback: Any? = null

    @SuppressLint("MissingPermission")
    override fun start(sink: LiveAudioSource.Sink) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw LiveSourceException(NO_PERMISSION)
        }
        val rec = open(MediaRecorder.AudioSource.VOICE_RECOGNITION) ?: open(MediaRecorder.AudioSource.MIC)
            ?: throw LiveSourceException(BUSY)
        try {
            rec.startRecording()
        } catch (e: IllegalStateException) {
            rec.release()
            throw LiveSourceException(BUSY)
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release()
            throw LiveSourceException(BUSY)
        }
        record = rec
        if (Build.VERSION.SDK_INT >= 29) watchSilencing(rec, sink)
        running = true
        worker = thread(name = "live-mic") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            loop(rec, sink)
        }
    }

    @SuppressLint("MissingPermission")
    private fun open(source: Int): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        return runCatching {
            // one second of headroom: the reader is never late by that much, so the driver never overruns
            AudioRecord(source, SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, SR * 2))
        }.getOrNull()?.takeIf {
            if (it.state != AudioRecord.STATE_INITIALIZED) { it.release(); false } else true
        }
    }

    private fun loop(rec: AudioRecord, sink: LiveAudioSource.Sink) {
        val buf = ShortArray(LiveAudioSource.BLOCK)
        var recording = true
        while (running) {
            if (paused) {
                if (recording) { runCatching { rec.stop() }; recording = false }
                synchronized(lock) { while (paused && running) lock.wait(200) }
                if (!running) break
                try {
                    rec.startRecording()
                } catch (e: IllegalStateException) {
                    sink.onError(BUSY); return
                }
                if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) { sink.onError(BUSY); return }
                recording = true
                continue
            }
            var off = 0
            while (off < buf.size && running && !paused) {
                val n = rec.read(buf, off, buf.size - off)
                if (n < 0) {
                    Log.w(TAG, "AudioRecord.read: $n")
                    sink.onError(if (n == AudioRecord.ERROR_DEAD_OBJECT) LOST else BROKEN)
                    return
                }
                off += n
            }
            if (off > 0) sink.onAudio(buf.copyOf(off))
        }
    }

    private fun watchSilencing(rec: AudioRecord, sink: LiveAudioSource.Sink) {
        if (Build.VERSION.SDK_INT < 29) return
        val am = context.getSystemService(AudioManager::class.java) ?: return
        val cb = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                val mine = configs.firstOrNull { it.clientAudioSessionId == rec.audioSessionId } ?: return
                sink.onSilenced(mine.isClientSilenced)
            }
        }
        am.registerAudioRecordingCallback(cb, null)
        callback = cb
    }

    override fun pause() { paused = true }

    override fun resume() {
        synchronized(lock) { paused = false; lock.notifyAll() }
    }

    override fun stop() {
        running = false
        synchronized(lock) { lock.notifyAll() }
        worker?.takeIf { it != Thread.currentThread() }?.join(2_000)
        worker = null
        if (Build.VERSION.SDK_INT >= 29) {
            (callback as? AudioManager.AudioRecordingCallback)?.let { context.getSystemService(AudioManager::class.java)?.unregisterAudioRecordingCallback(it) }
        }
        callback = null
        record?.let { runCatching { it.stop() }; it.release() }
        record = null
    }

    companion object {
        private const val TAG = "Replika"
        private const val SR = LiveAudioSource.SAMPLE_RATE
        const val NO_PERMISSION = "Нет доступа к микрофону. Разрешите «Реплике» запись звука в настройках телефона."
        const val BUSY = "Микрофон занят другим приложением или недоступен. Закройте приложение, которое его использует, и попробуйте снова."
        const val LOST = "Микрофон отключился во время записи. Записанное до этого момента сохранено."
        const val BROKEN = "Не удалось читать звук с микрофона. Записанное до этого момента сохранено."
    }
}

/**
 * Debug-only stand-in for the microphone: decodes a file and plays it into the engine at real-time
 * pace, so live latency and lag can be measured on an emulator. Started from adb (docs/ARCHITECTURE.md).
 */
class FileSource(private val context: Context, private val file: File) : LiveAudioSource {
    private var worker: Thread? = null
    private val lock = Object()
    @Volatile private var running = false
    @Volatile private var paused = false

    override fun start(sink: LiveAudioSource.Sink) {
        if (!file.canRead()) throw LiveSourceException("Тестовый файл не найден или недоступен: ${file.path}")
        running = true
        worker = thread(name = "live-file") {
            val audio = try {
                runBlocking { AudioDecoder.decode(context, Uri.fromFile(file)) }
            } catch (e: Exception) {
                sink.onError("Не удалось прочитать тестовый файл: ${e.message ?: e.javaClass.simpleName}")
                return@thread
            }
            val n = (audio.durationMs * LiveAudioSource.SAMPLE_RATE / 1000).toInt()
            var next = SystemClock.elapsedRealtime()
            var i = 0
            while (running && i < n) {
                if (paused) {
                    val t = SystemClock.elapsedRealtime()
                    synchronized(lock) { while (paused && running) lock.wait(200) }
                    next += SystemClock.elapsedRealtime() - t
                    continue
                }
                next += 100
                val wait = next - SystemClock.elapsedRealtime()
                if (wait > 0) Thread.sleep(wait)
                val end = minOf(i + LiveAudioSource.BLOCK, n)
                sink.onAudio(ShortArray(end - i) { k -> (audio.samples[i + k].coerceIn(-1f, 1f) * 32767f).toInt().toShort() })
                i = end
            }
            if (running) sink.onEnd()
        }
    }

    override fun pause() { paused = true }

    override fun resume() {
        synchronized(lock) { paused = false; lock.notifyAll() }
    }

    override fun stop() {
        running = false
        synchronized(lock) { lock.notifyAll() }
        worker?.takeIf { it != Thread.currentThread() }?.join(2_000)
        worker = null
    }
}
