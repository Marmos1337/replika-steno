package ru.replika.steno.ui

import ru.replika.steno.ActiveJob
import ru.replika.steno.FragmentJob
import ru.replika.steno.FragmentOutcome
import ru.replika.steno.core.RecognizeMode

import android.app.Application
import android.Manifest
import android.app.UiModeManager
import android.content.pm.PackageManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.replika.steno.ReplikaApp
import ru.replika.steno.core.Recording
import ru.replika.steno.core.RecordingState
import ru.replika.steno.core.live.LiveState
import ru.replika.steno.core.live.LiveStatus
import ru.replika.steno.data.ThemeMode
import ru.replika.steno.export.TranscriptJson

enum class ExportFormat(val ext: String, val mime: String) {
    JSON("json", "application/json"),
    TXT("txt", "text/plain");

    fun encode(rec: Recording): String = when (this) {
        JSON -> TranscriptJson.encode(rec)
        // "[00:12] Имя: текст" with the on-screen labels («Участник 2» when unnamed), one utterance per line
        TXT -> rec.utterances.joinToString("\n", postfix = "\n") { "[${fmtTime(it.startMs)}] ${rec.label(it.speakerId)}: ${it.text}" }
    }
}

sealed interface Screen {
    data object Library : Screen
    data object About : Screen
    /** Live recording: the session in progress, or the start screen when there is none. */
    data object Live : Screen
    data class Processing(val id: String) : Screen
    data class Transcript(val id: String) : Screen

    fun encode(): String = when (this) {
        Library -> "library"
        About -> "about"
        Live -> "live"
        is Processing -> "processing:$id"
        is Transcript -> "transcript:$id"
    }

    companion object {
        fun decode(s: String?): Screen = when {
            s == null -> Library
            s == "about" -> About
            s == "live" -> Live
            s.startsWith("processing:") -> Processing(s.substringAfter(':'))
            s.startsWith("transcript:") -> Transcript(s.substringAfter(':'))
            else -> Library
        }
    }
}

/** A short message over the player; [action] adds one text button (undo), and keeps it up longer. */
data class Toast(
    val text: String,
    val action: ToastAction? = null,
    val id: Long = SystemClock.elapsedRealtimeNanos(),
)

data class ToastAction(val label: String, val run: () -> Unit)

class ReplikaViewModel(app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {
    val graph = (app as ReplikaApp).graph
    private val store get() = graph.store

    private val _screen = MutableStateFlow(Screen.decode(saved.get<String>(KEY_SCREEN)))
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    val recordings: StateFlow<List<Recording>> = store.items
    val job = graph.job

    private val _theme = MutableStateFlow(graph.settings.themeSync())
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    val llmEnabled: StateFlow<Boolean> = graph.settings.llmEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val _toast = MutableStateFlow<Toast?>(null)
    val toast: StateFlow<Toast?> = _toast.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    /** When the current job started (elapsedRealtime), for the timer on the processing screen. */
    private val _jobStartedAt = MutableStateFlow<Pair<String, Long>?>(null)
    val jobStartedAt: StateFlow<Pair<String, Long>?> = _jobStartedAt.asStateFlow()

    private val uiPrefs = app.getSharedPreferences("ui", Context.MODE_PRIVATE)
    private val _railCollapsed = MutableStateFlow(uiPrefs.getBoolean("rail_collapsed", false))
    /** Wide screens: the permanent panel is collapsed to a rail. Persisted between launches. */
    val railCollapsed: StateFlow<Boolean> = _railCollapsed.asStateFlow()

    init {
        viewModelScope.launch {
            graph.settings.theme.collect { mode ->
                _theme.value = mode
                graph.settings.mirrorTheme(mode)
            }
        }
        viewModelScope.launch {
            var prev: ActiveJob? = null
            graph.job.collect { j ->
                // a new run starts the timer again, also when the same recording is processed once more
                if (j != null && (prev == null || prev?.recordingId != j.recordingId)) {
                    _jobStartedAt.value = j.recordingId to SystemClock.elapsedRealtime()
                }
                prev = j
            }
        }
        // The engine runs one recording at a time: anything imported meanwhile waits as NEW and starts
        // here once the engine is free. The short delay lets the finished job's coroutine fully end.
        // A live recording holds the queue too, so live recognition does not compete with a new import.
        viewModelScope.launch {
            combine(graph.job, store.items, graph.live) { j, items, _ -> j to items }.collectLatest { (j, items) ->
                if (j != null || graph.liveActive) return@collectLatest
                val next = items.filter { it.state == RecordingState.NEW }.minByOrNull { it.createdAtMs } ?: return@collectLatest
                delay(300)
                graph.start(next.id)
            }
        }
        // A take that finished while the live screen was not shown (Stop in the notification, a file
        // test that ran out) is simply handed over: the library row shows its processing.
        viewModelScope.launch {
            graph.live.collect { s ->
                if (s?.status == LiveStatus.DONE && _screen.value != Screen.Live) graph.dismissLive()
            }
        }
    }

    // ------------------------------------------------------------ navigation

    fun navigate(to: Screen) {
        _screen.value = to
        saved[KEY_SCREEN] = to.encode()
    }

    /** Returns false when there is nowhere to go back to (the system should handle Back). */
    fun back(): Boolean {
        if (_screen.value == Screen.Library) return false
        navigate(Screen.Library)
        return true
    }

    fun toast(text: String, action: ToastAction? = null) {
        _toast.value = Toast(text, action)
    }

    fun toastShown(t: Toast) {
        if (_toast.value?.id == t.id) _toast.value = null
    }

    // ------------------------------------------------------------ settings

    fun setTheme(mode: ThemeMode) {
        _theme.value = mode
        graph.settings.mirrorTheme(mode)
        applyNightMode(getApplication(), mode)
        viewModelScope.launch { graph.settings.setTheme(mode) }
    }

    fun toggleRail() {
        val v = !_railCollapsed.value
        _railCollapsed.value = v
        uiPrefs.edit().putBoolean("rail_collapsed", v).apply()
    }

    fun setLlmEnabled(on: Boolean) {
        viewModelScope.launch { graph.settings.setLlmEnabled(on) }
    }

    // ------------------------------------------------------------ recordings

    fun import(uri: Uri) {
        if (_importing.value) return
        _importing.value = true
        viewModelScope.launch {
            runCatching { store.import(uri) }
                .onSuccess { rec ->
                    graph.start(rec.id)
                    navigate(Screen.Processing(rec.id))
                }
                .onFailure { toast("Не удалось прочитать файл. Попробуйте выбрать его ещё раз.") }
            _importing.value = false
        }
    }

    fun reprocess(id: String) {
        if (isLiveRecording(id)) { navigate(Screen.Live); return }
        viewModelScope.launch {
            store.update(id) { it.copy(state = RecordingState.NEW, error = null) }
            graph.start(id)
            navigate(Screen.Processing(id))
        }
    }

    fun cancelJob() = graph.cancel()

    fun delete(id: String) {
        if (isLiveRecording(id)) {
            if (graph.live.value?.status == LiveStatus.FINISHING) {
                // the take is being saved; it can be deleted like any recording once it is processed
                toast(LIVE_SAVING)
                return
            }
            // the take in progress: throw it away through the session, which owns the file
            viewModelScope.launch { toast(if (graph.discardLive()) "Запись удалена" else LIVE_SAVING) }
            return
        }
        viewModelScope.launch {
            if (graph.job.value?.recordingId == id) {
                graph.cancel()
                withTimeoutOrNull(5_000) { graph.job.first { it?.recordingId != id } }
            }
            store.delete(id)
            val s = _screen.value
            if ((s is Screen.Processing && s.id == id) || (s is Screen.Transcript && s.id == id)) navigate(Screen.Library)
            toast("Запись удалена")
        }
    }

    fun renameSpeaker(id: String, speakerId: String, name: String) {
        val clean = name.trim()
        viewModelScope.launch {
            store.update(id) { r ->
                r.copy(speakerNames = if (clean.isEmpty() || clean == speakerId || clean == defaultSpeakerLabel(speakerId)) r.speakerNames - speakerId else r.speakerNames + (speakerId to clean))
            }
        }
    }

    fun editUtterance(id: String, index: Int, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            store.update(id) { r ->
                val list = r.utterances.toMutableList()
                val u = list.getOrNull(index) ?: return@update r
                if (u.text == clean) return@update r
                list[index] = u.copy(text = clean, edited = clean != u.rawText)
                r.copy(utterances = list)
            }
        }
    }

    /** The utterance being recognized again, if any. */
    val fragment: StateFlow<FragmentJob?> = graph.fragment

    /** «Распознать заново» for one utterance; the result (or why it failed) comes as a toast. */
    fun rerecognize(id: String, index: Int, mode: RecognizeMode, widen: Boolean) {
        viewModelScope.launch {
            when (val r = graph.rerecognize(id, index, mode, widen).await()) {
                FragmentOutcome.Replaced -> toast("Реплика распознана заново", ToastAction("Вернуть") { undoFragment(id, index) })
                FragmentOutcome.Same -> toast("Модель распознала то же самое — текст не изменился")
                is FragmentOutcome.Failed -> toast(r.message)
            }
        }
    }

    /** Swaps the utterance back to the text it had before «Распознать заново» (and forth again). */
    fun undoFragment(id: String, index: Int) {
        viewModelScope.launch {
            toast(if (graph.undoFragment(id, index)) "Прежний текст возвращён" else "Возвращать нечего")
        }
    }

    /** Marks an extracted agreement as confirmed by the user (UI state only, never exported). */
    fun setActionConfirmed(id: String, index: Int, confirmed: Boolean) {
        viewModelScope.launch {
            store.update(id) { r ->
                val s = r.summary ?: return@update r
                val list = s.actions.toMutableList()
                val a = list.getOrNull(index) ?: return@update r
                list[index] = a.copy(confirmed = confirmed)
                r.copy(summary = s.copy(actions = list))
            }
        }
    }

    // ------------------------------------------------------------ export

    suspend fun writeExport(uri: Uri, rec: Recording, format: ExportFormat): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt").use { out ->
                requireNotNull(out).write(format.encode(rec).toByteArray(Charsets.UTF_8))
            }
        }.isSuccess
    }

    suspend fun writeShareFile(rec: Recording, format: ExportFormat): File? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            File(dir, exportName(rec, format)).apply { writeText(format.encode(rec), Charsets.UTF_8) }
        }.getOrNull()
    }

    // ------------------------------------------------------------ live recording

    /** The live session for the live screen; null when there is none. */
    val live: StateFlow<LiveState?> = graph.live

    /** Starts live recording (RECORD_AUDIO must already be granted). False and a toast when it cannot start. */
    fun startLive(): Boolean {
        val error = graph.startLive() ?: return true
        toast(error)
        return false
    }

    /** Debug runs only (MainActivity checks the build is debuggable). Opens the live screen like a real take. */
    fun startLiveFromFile(file: File) {
        val error = graph.startLiveFromFile(file)
        if (error != null) toast(error) else navigate(Screen.Live)
    }

    /** The live screen is shown: preload the speech models so live text starts quickly. */
    fun prepareLive() = graph.prepareLive()

    /** The live screen left the composition: the preloaded models may be freed when idle. */
    fun leaveLive() = graph.leaveLive()

    val enginesReady: StateFlow<Boolean> = graph.enginesReady

    /** True when [id] is the recording of the live session in progress (rows open the live screen for it). */
    fun isLiveRecording(id: String): Boolean = graph.liveActive && graph.live.value?.recordingId == id

    fun pauseLive() = graph.pauseLive()
    fun resumeLive() = graph.resumeLive()
    fun stopLive() = graph.stopLive()
    /** The live screen's «Удалить»: leaves to the library and says what actually happened. */
    fun discardLive() {
        viewModelScope.launch { toast(if (graph.discardLive()) "Запись удалена" else LIVE_SAVING) }
    }
    /** After a DONE / ERROR state was shown; DONE also has the recording id to open its processing screen. */
    fun dismissLive() = graph.dismissLive()

    /** Android 13+: the recording notification needs a runtime grant; recording works without it. */
    fun notificationPermissionNeeded(): Boolean =
        Build.VERSION.SDK_INT >= 33 &&
            getApplication<Application>().checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    companion object {
        private const val KEY_SCREEN = "screen"
        private const val LIVE_SAVING = "Запись сохраняется — удалить её можно после обработки"

        fun exportName(rec: Recording, format: ExportFormat): String =
            rec.title.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").trim().ifBlank { "transcript" }.take(80) + "." + format.ext

        /** Makes the platform resources (windowBackground in values-night) follow the in-app choice. */
        fun applyNightMode(context: Context, mode: ThemeMode) {
            if (Build.VERSION.SDK_INT < 31) return
            val ui = context.getSystemService(UiModeManager::class.java) ?: return
            ui.setApplicationNightMode(
                when (mode) {
                    ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
                    ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
                    ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
                },
            )
        }
    }
}
