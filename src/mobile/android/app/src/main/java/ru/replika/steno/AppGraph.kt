package ru.replika.steno

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
import ru.replika.steno.data.RecordingStore
import ru.replika.steno.data.Settings
import ru.replika.steno.diar.VadDiarizer
import ru.replika.steno.llm.LlmFormatter

/** Live state of the one recording being processed. */
data class ActiveJob(val recordingId: String, val progress: Progress, val note: String? = null)

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

    init {
        scope.launch { store.load() }
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
                if (running?.isActive == true) return@withLock
                recognizer?.release(); recognizer = null
                diarizer?.release(); diarizer = null
                Log.i(TAG, "engines released")
            }
        }
    }

    private suspend fun asrEngines(note: (String) -> Unit): Pair<SherpaRecognizer, VadDiarizer> =
        engineLock.withLock {
            withContext(Dispatchers.IO) {
                val r = recognizer ?: run { note("Загрузка модели распознавания"); SherpaRecognizer(context.assets) }.also { recognizer = it }
                val d = diarizer ?: run { note("Загрузка модели голосов"); VadDiarizer(context.assets) }.also { diarizer = it }
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
