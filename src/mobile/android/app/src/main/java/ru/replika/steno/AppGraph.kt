package ru.replika.steno

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.replika.steno.asr.SherpaRecognizer
import ru.replika.steno.core.AudioDecodeException
import ru.replika.steno.core.AudioDecoder
import ru.replika.steno.core.NoSpeechException
import ru.replika.steno.core.Pipeline
import ru.replika.steno.core.Progress
import ru.replika.steno.core.RecordingState
import ru.replika.steno.core.Stage
import ru.replika.steno.core.LIVE_SOURCE
import ru.replika.steno.core.live.LiveState
import ru.replika.steno.core.live.VoicePrint
import ru.replika.steno.data.RecordingStore
import ru.replika.steno.data.Settings
import ru.replika.steno.diar.SherpaStreamingVad
import ru.replika.steno.diar.VadDiarizer
import ru.replika.steno.live.FileSource
import ru.replika.steno.live.LiveAudioSource
import ru.replika.steno.live.LiveEngines
import ru.replika.steno.live.LiveRecordingService
import ru.replika.steno.live.LiveSession
import ru.replika.steno.live.MicSource
import ru.replika.steno.llm.LlmFormatter

/** Live state of the one recording being processed. */
data class ActiveJob(val recordingId: String, val progress: Progress, val note: String? = null)

@OptIn(ExperimentalCoroutinesApi::class)
class AppGraph(private val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store = RecordingStore(context)
    val settings = Settings(context)

    private val _job = MutableStateFlow<ActiveJob?>(null)
    val job: StateFlow<ActiveJob?> = _job.asStateFlow()
    private var running: kotlinx.coroutines.Job? = null

    private val engineLock = Mutex()
    private var recognizer: SherpaRecognizer? = null
    private var diarizer: VadDiarizer? = null
    private var llm: LlmFormatter? = null
    /** Why the LLM is not in use, if it is not. Shown on the About screen. */
    @Volatile var llmStatus: String = "ещё не загружена"
        private set

    private val liveSession = MutableStateFlow<LiveSession?>(null)
    /**
     * The live recording, if any: null when none was started or the last one was dismissed.
     * Terminal states (DONE / ERROR) stay until [dismissLive] or the next [startLive].
     */
    val live: StateFlow<LiveState?> = liveSession
        .flatMapLatest { it?.state ?: flowOf(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** True while a live session is recording, paused or finishing. */
    val liveActive: Boolean get() = liveSession.value?.active == true

    private val _enginesReady = MutableStateFlow(false)
    /** The speech models are loaded and kept (live text can start at once). */
    val enginesReady: StateFlow<Boolean> = _enginesReady.asStateFlow()

    /** The live screen is open: keep the speech models loaded even with no session yet. */
    @Volatile private var liveHold = false

    init {
        scope.launch { store.load() }
    }

    // ------------------------------------------------------------ live recording

    /**
     * Starts a live recording from the microphone. Returns null when it started (follow [live]),
     * or a message for the user when it could not (no permission, already recording).
     * An import that is already being processed keeps running: both share the recognizer, so live
     * text may lag behind until it ends ([LiveState.lagging] shows it); queued imports wait.
     */
    fun startLive(): String? {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return MicSource.NO_PERMISSION
        return startLive(MicSource(context), "Живая запись " + SimpleDateFormat("dd.MM HH:mm", Locale.ROOT).format(Date()), LIVE_SOURCE, fromFile = false)
    }

    /** Debug builds only: plays [file] into the live engine at real-time pace (MainActivity, adb extra). */
    fun startLiveFromFile(file: File): String? =
        startLive(FileSource(context, file), "Живой тест: ${file.name}", "$LIVE_SOURCE:test", fromFile = true)

    private fun startLive(source: LiveAudioSource, title: String, tag: String, fromFile: Boolean): String? {
        if (liveActive) return "Запись уже идёт."
        val session = LiveSession(store, scope, source, title, tag, fromFile, ::liveEngines, ::onLiveClosed)
        liveSession.value = session
        Log.i(TAG, "live: start (${if (fromFile) "file" else "mic"})")
        // the service needs RECORD_AUDIO for type=microphone; a file test without it runs in the foreground only
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            if (!LiveRecordingService.start(context)) session.notice("Запись идёт, пока приложение открыто: система не разрешила работу в фоне.")
        }
        scope.launch { session.start() }
        return null
    }

    /**
     * The live screen opened: load the speech models now, so the first text comes ~1-2 s after the
     * user starts talking instead of after the model load. They stay loaded until [leaveLive].
     */
    fun prepareLive() {
        liveHold = true
        if (_enginesReady.value) return
        scope.launch {
            runCatching { asrEngines {} }.onFailure { Log.w(TAG, "live: preload failed", it) }
        }
    }

    /** The live screen closed: the models may be freed once nothing else uses them. */
    fun leaveLive() {
        if (!liveHold) return
        liveHold = false
        if (running?.isActive != true && !liveActive) releaseWhenIdle()
    }

    fun pauseLive() { liveSession.value?.pause() }

    fun resumeLive() { liveSession.value?.resume() }

    /** Stops capture; the recording is finished in the background and then processed like an import. */
    fun stopLive() {
        val s = liveSession.value ?: return
        scope.launch { s.finish() }
    }

    /** Throws the take away: nothing is saved. */
    fun discardLive() {
        val s = liveSession.value ?: return
        scope.launch {
            s.discard()
            // a discard pressed while starting completes inside start(); the session clears itself then
            if (liveSession.value === s && !s.active) liveSession.value = null
        }
    }

    /** Forgets a finished or failed session (the UI has shown its result). */
    fun dismissLive() {
        if (liveSession.value?.active == false) liveSession.value = null
    }

    private suspend fun liveEngines(): LiveEngines {
        val (rec, dia) = asrEngines {}
        val vad = withContext(Dispatchers.IO) { SherpaStreamingVad(context.assets) }
        return LiveEngines(vad, rec, VoicePrint { dia.embed(it) }, release = { vad.release() })
    }

    private fun onLiveClosed(session: LiveSession, recordingId: String?) {
        // a discarded take leaves nothing to show
        if (recordingId == null && session.state.value.status == ru.replika.steno.core.live.LiveStatus.DONE && liveSession.value === session) liveSession.value = null
        if (recordingId != null) start(recordingId) // busy -> stays NEW and the queue picks it up
        if (running?.isActive != true) releaseWhenIdle()
    }

    fun start(recordingId: String) {
        if (running?.isActive == true) { Log.i(TAG, "start ${recordingId.take(8)} ignored: busy"); return }
        Log.i(TAG, "start ${recordingId.take(8)}")
        running = scope.launch { process(recordingId) }
    }

    fun cancel() {
        // model loading is native and cannot be interrupted, so say at once that the stop is coming
        _job.update { it?.copy(note = "Останавливаю…") }
        running?.cancel()
    }

    private suspend fun process(id: String) {
        val audioFile = store.audioFile(id)
        fun report(p: Progress, note: String? = null) = _job.update { ActiveJob(id, p, note) }
        var transcriptReady = false
        try {
            store.update(id) { it.copy(state = RecordingState.PROCESSING, error = null, llmPending = false, llmNote = null) }
            report(Progress(Stage.DECODE, 0f), "Подготовка моделей")
            val tLoad = SystemClock.elapsedRealtime()
            val (rec, dia) = asrEngines { report(Progress(Stage.DECODE, 0f), it) }
            Log.i(TAG, "models ready in ${SystemClock.elapsedRealtime() - tLoad} ms")
            val t0 = SystemClock.elapsedRealtime()
            val audio = AudioDecoder.decode(context, android.net.Uri.fromFile(audioFile)) { report(Progress(Stage.DECODE, it)) }
            val decodeMs = SystemClock.elapsedRealtime() - t0
            store.update(id) { it.copy(durationMs = audio.durationMs) }

            // phase 1: transcript - shown to the user as soon as it is ready
            val result = withContext(Dispatchers.Default) { Pipeline(dia, rec, null).transcribe(audio, decodeMs) { report(it) } }
            store.update(id) {
                it.copy(
                    state = RecordingState.READY,
                    utterances = result.utterances,
                    durationMs = result.durationMs,
                    summary = null,
                    metrics = result.metrics,
                    llmPending = true,
                    // a re-run keeps names the user already gave, as long as the ids still exist
                    speakerNames = it.speakerNames.filterKeys { k -> result.utterances.any { u -> u.speakerId == k } },
                )
            }
            transcriptReady = true
            Log.i(TAG, "transcript $id: ${result.metrics}")

            // phase 2: local LLM - punctuation/case (guarded) and the meeting summary
            report(Progress(Stage.FORMAT, 0f), "Загрузка языковой модели")
            val tLlm = SystemClock.elapsedRealtime()
            val fmt = llmEngine()
            Log.i(TAG, "llm ready in ${SystemClock.elapsedRealtime() - tLlm} ms")
            val llm = withContext(Dispatchers.Default) { Pipeline(dia, rec, fmt).polish(result.utterances) { report(it) } }
            store.update(id) { cur ->
                // keep anything the user edited while the LLM was working
                val merged = cur.utterances.mapIndexed { i, u ->
                    val original = result.utterances.getOrNull(i)
                    val polished = llm.utterances.getOrNull(i)
                    if (original != null && polished != null && !u.edited && u.text == original.text) u.copy(text = polished.text) else u
                }
                cur.copy(
                    utterances = merged,
                    summary = llm.summary,
                    llmPending = false,
                    llmNote = when {
                        fmt == null -> "Языковая модель недоступна: $llmStatus."
                        llm.summary == null -> "Модель не нашла в записи пунктов для итогов."
                        else -> null
                    },
                    metrics = cur.metrics?.copy(llmMs = llm.llmMs),
                )
            }
            Log.i(TAG, "llm $id: ${llm.llmMs} ms")
        } catch (e: Throwable) {
            // the coroutine may already be cancelled, so the final save must not be cancellable itself
            withContext(NonCancellable) {
                runCatching { fail(id, e, transcriptReady) }.onFailure { Log.e(TAG, "could not save the failure", it) }
            }
        } finally {
            _job.value = null
            releaseWhenIdle()
        }
    }

    private suspend fun fail(id: String, e: Throwable, transcriptReady: Boolean) {
        if (e is CancellationException) Log.i(TAG, "cancelled ${id.take(8)} (transcript ready: $transcriptReady)")
        else Log.e(TAG, "processing failed", e)
        if (transcriptReady) {
            val note = when (e) {
                is CancellationException -> "Оформление и итоги отменены."
                is OutOfMemoryError -> "Не хватило памяти для языковой модели."
                else -> "Итоги не построены: ${e.message ?: e.javaClass.simpleName}"
            }
            store.update(id) { it.copy(llmPending = false, llmNote = note) }
            return
        }
        val error = when (e) {
            is CancellationException -> CANCELLED
            is NoSpeechException -> "В записи не найдено речи. Проверьте, что файл не пустой и голос слышно."
            is AudioDecodeException -> e.message ?: "Не удалось прочитать файл."
            is OutOfMemoryError -> "Не хватило памяти устройства для этой записи. Попробуйте файл короче."
            else -> "Не удалось обработать запись: ${e.message ?: e.javaClass.simpleName}"
        }
        store.update(id) {
            // stopping a re-run keeps the transcript that was already there
            if (e is CancellationException && it.utterances.isNotEmpty()) it.copy(state = RecordingState.READY, error = null, llmPending = false)
            else it.copy(state = RecordingState.FAILED, error = error)
        }
    }

    /**
     * Models take ~1.5 GB while working. The LLM is closed right after its pass; the speech models are
     * kept for [IDLE_RELEASE_MS] in case the next recording is queued, then freed too.
     */
    private fun releaseWhenIdle() {
        scope.launch {
            engineLock.withLock { llm?.close(); llm = null }
            delay(IDLE_RELEASE_MS)
            engineLock.withLock {
                if (running?.isActive == true || liveActive || liveHold) return@withLock
                recognizer?.release(); recognizer = null
                diarizer?.release(); diarizer = null
                _enginesReady.value = false
                Log.i(TAG, "engines released")
            }
        }
    }

    private suspend fun asrEngines(note: (String) -> Unit): Pair<SherpaRecognizer, VadDiarizer> =
        engineLock.withLock {
            withContext(Dispatchers.IO) {
                val r = recognizer ?: run { note("Загрузка модели распознавания"); SherpaRecognizer(context.assets) }.also { recognizer = it }
                val d = diarizer ?: run { note("Загрузка модели голосов"); VadDiarizer(context.assets) }.also { diarizer = it }
                _enginesReady.value = true
                r to d
            }
        }

    private suspend fun llmEngine(): LlmFormatter? = engineLock.withLock {
        withContext(Dispatchers.IO) {
            if (!settings.llmEnabled.first()) {
                llmStatus = "выключена в настройках"; null
            } else llm ?: runCatching { LlmFormatter.create(context) }
                .onFailure { if (it is CancellationException) throw it }
                .onSuccess { llmStatus = "работает"; llm = it }
                .onFailure { llmStatus = "не загрузилась: ${it.message ?: it.javaClass.simpleName}"; Log.e(TAG, "llm", it) }
                .getOrNull()
        }
    }

    companion object {
        private const val TAG = "Replika"
        private const val IDLE_RELEASE_MS = 30_000L
        const val CANCELLED = "Обработка отменена."
    }
}
