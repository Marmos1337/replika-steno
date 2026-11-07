package ru.replika.steno

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
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
import kotlinx.coroutines.flow.Flow
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
import ru.replika.steno.core.DecodedAudio
import ru.replika.steno.core.Fragment
import ru.replika.steno.core.RecognizeMode
import ru.replika.steno.core.Segments
import ru.replika.steno.core.SpeakerTurn
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

/** The one utterance being recognized again. */
data class FragmentJob(val recordingId: String, val index: Int)

sealed interface FragmentOutcome {
    /** The new text is saved; the old one is in [ru.replika.steno.core.Utterance.previous]. */
    data object Replaced : FragmentOutcome
    /** The model said the same as before: nothing was changed. */
    data object Same : FragmentOutcome
    data class Failed(val message: String) : FragmentOutcome
}

@OptIn(ExperimentalCoroutinesApi::class)
class AppGraph(private val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store = RecordingStore(context)
    val settings = Settings(context)

    private val _job = MutableStateFlow<ActiveJob?>(null)
    val job: StateFlow<ActiveJob?> = _job.asStateFlow()
    private var running: kotlinx.coroutines.Job? = null

    private val _fragment = MutableStateFlow<FragmentJob?>(null)
    /** The utterance being recognized again, if any (its row shows a spinner). */
    val fragment: StateFlow<FragmentJob?> = _fragment.asStateFlow()
    private var fragmentRun: kotlinx.coroutines.Job? = null

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

    /**
     * The same as [live], read straight from the current session on subscription. [live] is updated on
     * another dispatcher and may still be null right after [startLive]; the recording service must
     * not take that for "no session" and stop itself.
     */
    val liveNow: Flow<LiveState?> get() = liveSession.flatMapLatest { it?.state ?: flowOf(null) }

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

    /**
     * Throws the take away: nothing is saved. Returns false when there was nothing to discard or the
     * take was already being saved (Stop came first), so the caller never reports a deletion that did
     * not happen.
     */
    suspend fun discardLive(): Boolean {
        val s = liveSession.value ?: return false
        if (s.state.value.status == ru.replika.steno.core.live.LiveStatus.FINISHING) return false
        val done = withContext(NonCancellable) { s.discard() }
        // a discard pressed while starting completes inside start(); the session clears itself then
        if (liveSession.value === s && !s.active) liveSession.value = null
        return done
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
        if (recordingId != null) launchJob(recordingId) // busy -> stays NEW and the queue picks it up
        if (running?.isActive != true) releaseWhenIdle()
    }

    /**
     * Processes a recording that is NEW. While a live take is recording, paused or finishing, it stays
     * NEW and the queue (ReplikaViewModel) starts it after the take, so live recognition never competes
     * with a new import for the recognizer.
     */
    fun start(recordingId: String) {
        if (liveActive) { Log.i(TAG, "start ${recordingId.take(8)} deferred: live recording"); return }
        launchJob(recordingId)
    }

    private fun launchJob(recordingId: String) {
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

    // ------------------------------------------------------------ one utterance again

    /**
     * Why «Распознать заново» is not available now, or null when it is. A full processing run and a
     * live take own the recognizer (and a re-run rewrites the utterances anyway); one fragment at a time.
     */
    fun fragmentBlocker(): String? = when {
        liveActive -> "Недоступно во время живой записи"
        running?.isActive == true -> "Недоступно, пока идёт обработка записи"
        fragmentRun?.isActive == true -> "Дождитесь, пока распознается другая реплика"
        else -> null
    }

    /**
     * Recognizes utterance [index] of [recordingId] again from the stored audio: only its time range is
     * decoded ([widen] adds up to a second of the gaps around it). Only text, rawText and lang of that
     * utterance change; speaker and bounds stay. Runs in the app scope, so leaving the screen does not
     * stop it.
     */
    fun rerecognize(recordingId: String, index: Int, mode: RecognizeMode, widen: Boolean): Deferred<FragmentOutcome> {
        fragmentBlocker()?.let { return CompletableDeferred(FragmentOutcome.Failed(it)) }
        _fragment.value = FragmentJob(recordingId, index)
        val run = scope.async { runFragment(recordingId, index, mode, widen) }
        fragmentRun = run
        return run
    }

    private suspend fun runFragment(id: String, index: Int, mode: RecognizeMode, widen: Boolean): FragmentOutcome {
        try {
            val rec = store.get(id) ?: return FragmentOutcome.Failed("Запись не найдена.")
            val u = rec.utterances.getOrNull(index) ?: return FragmentOutcome.Failed("Реплика не найдена.")
            val span = Fragment.range(rec.utterances, index, widen, rec.durationMs)
                ?: return FragmentOutcome.Failed("У реплики неверные границы времени. Обработайте запись заново.")
            if (span.lengthMs > Fragment.MAX_MS) return FragmentOutcome.Failed("Реплика слишком длинная для повторного распознавания.")
            val t0 = SystemClock.elapsedRealtime()
            val rec0 = recognizerEngine()
            val sr = DecodedAudio.SAMPLE_RATE
            val samples = AudioDecoder.decodeRange(context, android.net.Uri.fromFile(store.audioFile(id)), span.startMs, span.endMs)
            // the same <= 20 s pieces as the full pipeline, cut at the quietest point
            val whole = SpeakerTurn(0, samples.size * 1000L / sr, 0)
            val parts = withContext(Dispatchers.Default) {
                Segments.splitForAsr(whole, samples, sr).map { p ->
                    coroutineContext.ensureActive()
                    rec0.recognize(Segments.slice(samples, sr, p.startMs, p.endMs), sr, mode)
                }
            }
            val raw = parts.joinToString(" ") { it.text.trim() }.trim().replace(Regex("\\s+"), " ")
            Log.i(TAG, "fragment ${id.take(8)}#$index $mode widen=$widen ${span.lengthMs} ms in ${SystemClock.elapsedRealtime() - t0} ms")
            if (raw.isEmpty()) return FragmentOutcome.Failed("Модель не услышала речи в этом фрагменте. Текст не изменился.")
            val lang = if (mode == RecognizeMode.AUTO) Fragment.mainLang(parts) else parts.first().lang
            var outcome: FragmentOutcome = FragmentOutcome.Failed("Расшифровка изменилась, пока шло распознавание. Текст не заменён.")
            withContext(NonCancellable) {
                store.update(id) { cur ->
                    val cu = cur.utterances.getOrNull(index)
                    when {
                        cur.state != RecordingState.READY || cu == null || !Fragment.same(cu, u) -> cur
                        Fragment.unchanged(cu, raw, lang) -> { outcome = FragmentOutcome.Same; cur }
                        else -> {
                            outcome = FragmentOutcome.Replaced
                            cur.copy(utterances = cur.utterances.toMutableList().also { it[index] = Fragment.apply(cu, raw, lang) })
                        }
                    }
                }
            }
            return outcome
        } catch (e: CancellationException) {
            throw e
        } catch (e: AudioDecodeException) {
            Log.w(TAG, "fragment decode", e)
            return FragmentOutcome.Failed(e.message ?: "Не удалось прочитать звук записи.")
        } catch (e: OutOfMemoryError) {
            return FragmentOutcome.Failed("Не хватило памяти устройства. Закройте другие приложения и попробуйте ещё раз.")
        } catch (e: Exception) {
            Log.e(TAG, "fragment failed", e)
            return FragmentOutcome.Failed("Не удалось распознать фрагмент: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            _fragment.value = null
            // a full run may have started meanwhile: its LLM must stay open
            releaseWhenIdle(closeLlm = running?.isActive != true)
        }
    }

    /** Undo of «Распознать заново»: swaps the utterance text with the one it replaced. */
    suspend fun undoFragment(recordingId: String, index: Int): Boolean {
        var done = false
        store.update(recordingId) { r ->
            val u = r.utterances.getOrNull(index) ?: return@update r
            val back = Fragment.undo(u) ?: return@update r
            done = true
            r.copy(utterances = r.utterances.toMutableList().also { it[index] = back })
        }
        return done
    }

    /**
     * Models take ~1.5 GB while working. The LLM is closed right after its pass; the speech models are
     * kept for [IDLE_RELEASE_MS] in case the next recording is queued, then freed too.
     */
    private fun releaseWhenIdle(closeLlm: Boolean = true) {
        scope.launch {
            if (closeLlm) engineLock.withLock { llm?.close(); llm = null }
            delay(IDLE_RELEASE_MS)
            engineLock.withLock {
                if (running?.isActive == true || fragmentRun?.isActive == true || liveActive || liveHold) return@withLock
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

    /** The recognizer alone: one fragment does not need the voice model. */
    private suspend fun recognizerEngine(): SherpaRecognizer =
        engineLock.withLock {
            withContext(Dispatchers.IO) {
                recognizer ?: SherpaRecognizer(context.assets).also { recognizer = it; _enginesReady.value = diarizer != null }
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
