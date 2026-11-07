package ru.replika.steno.live

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.replika.steno.core.DecodedAudio
import ru.replika.steno.core.Recognizer
import ru.replika.steno.core.RecordingState
import ru.replika.steno.core.live.LiveClock
import ru.replika.steno.core.live.LiveState
import ru.replika.steno.core.live.LiveStatus
import ru.replika.steno.core.live.LiveTranscriber
import ru.replika.steno.core.live.StreamingVad
import ru.replika.steno.core.live.VoicePrint
import ru.replika.steno.data.RecordingStore
import ru.replika.steno.data.WavWriter

/** Models for one live session; [release] frees only what the session owns (its VAD). */
class LiveEngines(val vad: StreamingVad, val recognizer: Recognizer, val voices: VoicePrint?, val release: () -> Unit)

/**
 * One live recording: capture -> WAV on disk + [LiveTranscriber], then hand-off to the normal
 * pipeline. Capture starts at once; the speech models load in parallel while audio queues up, so
 * nothing said in the first seconds is lost. The WAV is the source of truth: whatever happens to
 * live recognition (slow phone, model failure), the full recording is processed after Stop.
 */
class LiveSession(
    private val store: RecordingStore,
    private val scope: CoroutineScope,
    private val source: LiveAudioSource,
    private val title: String,
    private val sourceTag: String,
    fromFile: Boolean,
    private val loadEngines: suspend () -> LiveEngines,
    /** Called once when the session is over: the recording id to process, or null when nothing was kept. */
    private val onClosed: (LiveSession, String?) -> Unit,
) {
    private val transcriber = LiveTranscriber(LiveClock { SystemClock.elapsedRealtime() }, initial = LiveState(fromFile = fromFile))
    val state = transcriber.state

    private val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "live-asr") }.asCoroutineDispatcher()
    private var engines: LiveEngines? = null
    private var loadJob: Job? = null
    private var workerJob: Job? = null
    private var wav: WavWriter? = null
    private var recordingId: String? = null
    private val ending = AtomicBoolean(false)
    private val limitHit = AtomicBoolean(false)
    /** Stop or discard pressed before capture was up: carried out as soon as [start] gets there. */
    @Volatile private var pendingEnd: Boolean? = null

    /** True until the session is over (also while finishing). */
    val active: Boolean get() = !state.value.status.terminal

    suspend fun start() {
        val rec = try {
            store.createLive(title, sourceTag)
        } catch (e: Exception) {
            Log.e(TAG, "live: cannot create recording", e)
            return fail("Не удалось создать запись: на устройстве мало места?")
        }
        recordingId = rec.id
        try {
            wav = withContext(Dispatchers.IO) { WavWriter(store.audioFile(rec.id)) }
            withContext(Dispatchers.IO) { source.start(sink) }
        } catch (e: Exception) {
            runCatching { wav?.close() }
            store.delete(rec.id)
            Log.w(TAG, "live: capture did not start: ${e.message}")
            return fail(if (e is LiveSourceException) e.message!! else "Не удалось начать запись: ${e.message ?: e.javaClass.simpleName}")
        }
        transcriber.update { it.copy(status = LiveStatus.RECORDING, recordingId = rec.id) }
        Log.i(TAG, "live ${rec.id.take(8)}: capture started")
        loadJob = scope.launch { load() }
        scope.launch { samplePss() }
        when (pendingEnd) {
            true -> discard()
            false -> finish()
            null -> Unit
        }
    }

    private suspend fun load() {
        val t0 = SystemClock.elapsedRealtime()
        val e = try {
            loadEngines()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "live: models failed", e)
            transcriber.update { it.copy(notice = "Распознавание на лету недоступно: ${e.message ?: e.javaClass.simpleName}. Звук записывается, текст появится после остановки.") }
            return
        }
        if (!active || ending.get()) { e.release(); return }
        engines = e
        transcriber.bind(e.vad, e.recognizer, e.voices)
        Log.i(TAG, "live: models ready in ${SystemClock.elapsedRealtime() - t0} ms")
        workerJob = scope.launch(dispatcher) {
            try {
                while (isActive) if (transcriber.step(WORKER_POLL_MS) == LiveTranscriber.Step.ENDED) break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // live text stops, the recording goes on and is transcribed in full after Stop
                Log.e(TAG, "live: worker failed", e)
                transcriber.update { it.copy(notice = "Распознавание на лету остановилось из-за ошибки. Запись продолжается, текст появится после остановки.") }
            }
        }
    }

    private val sink = object : LiveAudioSource.Sink {
        override fun onAudio(block: ShortArray) {
            val w = wav ?: return
            try {
                w.write(block)
            } catch (e: IOException) {
                Log.e(TAG, "live: wav write failed", e)
                scope.launch { finish("Не хватило места на устройстве. Записанное до этого момента сохранено.") }
                return
            }
            transcriber.accept(FloatArray(block.size) { block[it] / 32768f })
            if (w.samples >= MAX_SAMPLES && limitHit.compareAndSet(false, true)) {
                transcriber.update { it.copy(notice = "Достигнут предел записи 2,5 часа. Запись остановлена.") }
                scope.launch { finish() }
            }
        }

        override fun onError(message: String) { scope.launch { finish(message) } }

        override fun onEnd() { scope.launch { finish() } }

        override fun onSilenced(silenced: Boolean) = transcriber.update { it.copy(micSilenced = silenced) }
    }

    fun notice(text: String) = transcriber.update { it.copy(notice = text) }

    fun pause() {
        if (state.value.status != LiveStatus.RECORDING || ending.get()) return
        source.pause()
        transcriber.pause()
        transcriber.update { it.copy(status = LiveStatus.PAUSED, level = 0f) }
        // the header covers everything up to the pause, in case the app is killed while paused
        scope.launch(Dispatchers.IO) { runCatching { wav?.sync() } }
    }

    fun resume() {
        if (state.value.status != LiveStatus.PAUSED || ending.get()) return
        source.resume()
        transcriber.update { it.copy(status = LiveStatus.RECORDING) }
    }

    /**
     * Stop: capture ends, the worker decodes everything still queued, the WAV is closed and the
     * recording goes to the normal pipeline. [error] marks a forced stop (mic lost, disk full).
     */
    suspend fun finish(error: String? = null) {
        if (state.value.status == LiveStatus.STARTING) {
            if (pendingEnd == null) pendingEnd = false
            // start() reads the flag after leaving STARTING: either it sees it, or we see the new status
            if (state.value.status == LiveStatus.STARTING) return
        }
        if (!ending.compareAndSet(false, true)) return
        val id = recordingId ?: return
        transcriber.update { it.copy(status = LiveStatus.FINISHING, level = 0f, partial = null) }
        withContext(NonCancellable) {
            withContext(Dispatchers.IO) { source.stop() }
            transcriber.finish()
            loadJob?.join()
            workerJob?.join()
            val samples = withContext(Dispatchers.IO) { runCatching { wav?.close() }; wav?.samples ?: 0 }
            engines?.release?.invoke()
            dispatcher.close()
            val durationMs = samples * 1000 / DecodedAudio.SAMPLE_RATE
            val metrics = transcriber.metrics()
            Log.i(TAG, "LIVE_METRICS ${metrics.toJson()}")
            if (durationMs < RecordingStore.MIN_LIVE_MS) {
                store.delete(id)
                transcriber.update { it.copy(status = LiveStatus.ERROR, error = error ?: "Запись слишком короткая. Говорите хотя бы пару секунд.") }
                onClosed(this@LiveSession, null)
                return@withContext
            }
            val draft = state.value.lines
            store.update(id) { it.copy(state = RecordingState.NEW, durationMs = durationMs, liveDraft = draft, liveMetrics = metrics, error = null) }
            Log.i(TAG, "live ${id.take(8)}: saved $durationMs ms, ${draft.size} lines")
            transcriber.update {
                if (error != null) it.copy(status = LiveStatus.ERROR, error = error, metrics = metrics)
                else it.copy(status = LiveStatus.DONE, metrics = metrics)
            }
            onClosed(this@LiveSession, id)
        }
    }

    /** Drops the take: nothing is kept. */
    suspend fun discard() {
        if (state.value.status == LiveStatus.STARTING) {
            pendingEnd = true
            if (state.value.status == LiveStatus.STARTING) return
        }
        if (!ending.compareAndSet(false, true)) return
        withContext(NonCancellable) {
            withContext(Dispatchers.IO) { source.stop() }
            workerJob?.cancel()
            loadJob?.join()
            workerJob?.join()
            withContext(Dispatchers.IO) { runCatching { wav?.close() } }
            engines?.release?.invoke()
            dispatcher.close()
            recordingId?.let { store.delete(it) }
            Log.i(TAG, "live: discarded")
            transcriber.update { it.copy(status = LiveStatus.DONE, recordingId = null, partial = null) }
            onClosed(this@LiveSession, null)
        }
    }

    private fun fail(message: String) {
        ending.set(true)
        dispatcher.close()
        transcriber.update { it.copy(status = LiveStatus.ERROR, error = message, recordingId = null) }
        onClosed(this, null)
    }

    private suspend fun samplePss() {
        while (active) {
            val kb = withContext(Dispatchers.Default) { Debug.getPss() }
            transcriber.notePss((kb / 1024).toInt())
            delay(PSS_EVERY_MS)
        }
    }

    companion object {
        private const val TAG = "Replika"
        private const val WORKER_POLL_MS = 200L
        private const val PSS_EVERY_MS = 3_000L
        /** Same cap as imports: longer audio would not fit the pipeline's memory. */
        private const val MAX_SAMPLES = DecodedAudio.MAX_DURATION_MS * DecodedAudio.SAMPLE_RATE / 1000
    }
}
