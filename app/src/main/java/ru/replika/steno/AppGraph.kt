package ru.replika.steno

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
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
        if (running?.isActive == true) return
        running = scope.launch { process(recordingId) }
    }

    fun cancel() {
        running?.cancel()
    }

    private suspend fun process(id: String) {
        val audioFile = store.audioFile(id)
        store.update(id) { it.copy(state = RecordingState.PROCESSING, error = null, llmPending = false, llmNote = null) }
        fun report(p: Progress, note: String? = null) = _job.update { ActiveJob(id, p, note) }
        var transcriptReady = false
        try {
            report(Progress(Stage.DECODE, 0f), "Подготовка моделей")
            val (rec, dia) = asrEngines { report(Progress(Stage.DECODE, 0f), it) }
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
            val fmt = llmEngine()
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
        } catch (e: CancellationException) {
            if (transcriptReady) store.update(id) { it.copy(llmPending = false, llmNote = "Оформление и итоги отменены.") }
            else store.update(id) { it.copy(state = RecordingState.FAILED, error = "Обработка отменена.") }
        } catch (e: NoSpeechException) {
            store.update(id) { it.copy(state = RecordingState.FAILED, error = "В записи не найдено речи. Проверьте, что файл не пустой и голос слышно.") }
        } catch (e: AudioDecodeException) {
            store.update(id) { it.copy(state = RecordingState.FAILED, error = e.message) }
        } catch (e: OutOfMemoryError) {
            if (transcriptReady) store.update(id) { it.copy(llmPending = false, llmNote = "Не хватило памяти для языковой модели.") }
            else store.update(id) { it.copy(state = RecordingState.FAILED, error = "Не хватило памяти устройства для этой записи. Попробуйте файл короче.") }
        } catch (e: Throwable) {
            Log.e(TAG, "processing failed", e)
            if (transcriptReady) store.update(id) { it.copy(llmPending = false, llmNote = "Итоги не построены: ${e.message ?: e.javaClass.simpleName}") }
            else store.update(id) { it.copy(state = RecordingState.FAILED, error = "Не удалось обработать запись: ${e.message ?: e.javaClass.simpleName}") }
        } finally {
            _job.value = null
            releaseWhenIdle()
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
                .onSuccess { llmStatus = "работает"; llm = it }
                .onFailure { llmStatus = "не загрузилась: ${it.message ?: it.javaClass.simpleName}"; Log.e(TAG, "llm", it) }
                .getOrNull()
        }
    }

    companion object {
        private const val TAG = "Replika"
        private const val IDLE_RELEASE_MS = 30_000L
    }
}
