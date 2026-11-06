package ru.replika.steno

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
import ru.replika.steno.asr.SherpaDiarizer
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
    private var diarizer: SherpaDiarizer? = null
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
        store.update(id) { it.copy(state = RecordingState.PROCESSING, error = null) }
        fun report(p: Progress, note: String? = null) = _job.update { ActiveJob(id, p, note) }
        try {
            report(Progress(Stage.DECODE, 0f), "Подготовка моделей")
            val (rec, dia, fmt) = engines { report(Progress(Stage.DECODE, 0f), it) }
            var t0 = SystemClock.elapsedRealtime()
            val audio = AudioDecoder.decode(context, android.net.Uri.fromFile(audioFile)) { report(Progress(Stage.DECODE, it)) }
            val decodeMs = SystemClock.elapsedRealtime() - t0
            store.update(id) { it.copy(durationMs = audio.durationMs) }
            val result = withContext(Dispatchers.Default) {
                Pipeline(dia, rec, fmt).run(audio, decodeMs) { report(it) }
            }
            store.update(id) {
                it.copy(
                    state = RecordingState.READY,
                    utterances = result.utterances,
                    durationMs = result.durationMs,
                    summary = result.summary,
                    metrics = result.metrics,
                    // a re-run keeps names the user already gave, as long as the ids still exist
                    speakerNames = it.speakerNames.filterKeys { k -> result.utterances.any { u -> u.speakerId == k } },
                )
            }
            Log.i(TAG, "done $id: ${result.metrics}")
        } catch (e: CancellationException) {
            store.update(id) { it.copy(state = RecordingState.FAILED, error = "Обработка отменена.") }
        } catch (e: NoSpeechException) {
            store.update(id) { it.copy(state = RecordingState.FAILED, error = "В записи не найдено речи. Проверьте, что файл не пустой и голос слышно.") }
        } catch (e: AudioDecodeException) {
            store.update(id) { it.copy(state = RecordingState.FAILED, error = e.message) }
        } catch (e: OutOfMemoryError) {
            store.update(id) { it.copy(state = RecordingState.FAILED, error = "Не хватило памяти устройства для этой записи. Попробуйте файл короче.") }
        } catch (e: Throwable) {
            Log.e(TAG, "processing failed", e)
            store.update(id) { it.copy(state = RecordingState.FAILED, error = "Не удалось обработать запись: ${e.message ?: e.javaClass.simpleName}") }
        } finally {
            _job.value = null
        }
    }

    private suspend fun engines(note: (String) -> Unit): Triple<SherpaRecognizer, SherpaDiarizer, LlmFormatter?> =
        engineLock.withLock {
            withContext(Dispatchers.IO) {
                val r = recognizer ?: run { note("Загрузка модели распознавания"); SherpaRecognizer(context.assets) }.also { recognizer = it }
                val d = diarizer ?: run { note("Загрузка модели голосов"); SherpaDiarizer(context.assets) }.also { diarizer = it }
                val wantLlm = settings.llmEnabled.first()
                val l = if (!wantLlm) {
                    llmStatus = "выключена в настройках"; null
                } else llm ?: runCatching {
                    note("Загрузка языковой модели")
                    LlmFormatter.create(context)
                }.onSuccess { llmStatus = "работает"; llm = it }
                    .onFailure { llmStatus = "не загрузилась: ${it.message ?: it.javaClass.simpleName}"; Log.e(TAG, "llm", it) }
                    .getOrNull()
                Triple(r, d, l)
            }
        }

    companion object { private const val TAG = "Replika" }
}
