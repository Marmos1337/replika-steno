package ru.replika.steno.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.replika.steno.core.ActionItem
import ru.replika.steno.core.MeetingSummary
import ru.replika.steno.core.PointKind
import ru.replika.steno.core.ProcessingMetrics
import ru.replika.steno.core.Recording
import ru.replika.steno.core.RecordingState
import ru.replika.steno.core.SummaryPoint
import ru.replika.steno.core.Utterance
import ru.replika.steno.core.LIVE_SOURCE
import ru.replika.steno.core.live.LiveLimits
import ru.replika.steno.core.live.LiveLine
import ru.replika.steno.core.live.LiveMetrics

/**
 * Local-only persistence: every recording is a folder in filesDir with the audio copy and a JSON
 * document. No database, nothing synced anywhere.
 */
class RecordingStore(private val context: Context) {

    private val root = File(context.filesDir, "recordings").apply { mkdirs() }
    private val _items = MutableStateFlow<List<Recording>>(emptyList())
    val items: StateFlow<List<Recording>> = _items.asStateFlow()
    /** Live takes started by this process: [load] must not mistake them for leftovers of a crash. */
    private val liveHere = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val _loaded = MutableStateFlow(false)
    /** False until the first [load] finished, so the UI does not flash an empty library on start. */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        val list = root.listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { dir ->
            runCatching { fromJson(JSONObject(File(dir, META).readText())) }.getOrNull()
        }.mapNotNull { if (it.state == RecordingState.RECORDING && it.id !in liveHere) recoverLive(it) else it }.map {
            // a run interrupted by process death cannot resume half-way: mark it so the user can restart it
            when {
                // a re-run that was killed keeps the transcript it already had
                it.state == RecordingState.PROCESSING && it.utterances.isNotEmpty() ->
                    it.copy(state = RecordingState.READY, error = null, llmPending = false, llmNote = "Повторная обработка была прервана, показан прежний результат.")
                it.state == RecordingState.PROCESSING -> it.copy(state = RecordingState.FAILED, error = INTERRUPTED)
                it.llmPending -> it.copy(llmPending = false, llmNote = "Итоги не достроены: приложение было закрыто. Запустите обработку снова.")
                else -> it
            }
        }
        // keep anything imported or changed while the disk was being read
        _items.update { cur -> (list.associateBy { it.id } + cur.associateBy { it.id }).values.sortedByDescending { it.createdAtMs } }
        _loaded.value = true
    }

    /**
     * A live recording the process died in the middle of (no session survives a restart). The WAV
     * header is fixed to cover everything that reached the disk and the recording is offered for
     * processing; a take shorter than [MIN_LIVE_MS] is removed.
     */
    private fun recoverLive(r: Recording): Recording? {
        val dir = File(root, r.id)
        val samples = runCatching { WavFile.repair(File(dir, AUDIO), LiveLimits.MAX_SAMPLES) }.getOrNull()
        val ms = (samples ?: 0) * 1000 / 16_000
        if (ms < MIN_LIVE_MS) {
            dir.deleteRecursively()
            android.util.Log.i("Replika", "dropped interrupted live take ${r.id.take(8)} (${ms} ms)")
            return null
        }
        val rec = r.copy(state = RecordingState.FAILED, error = LIVE_INTERRUPTED, durationMs = ms)
        runCatching {
            val tmp = File(dir, "$META.tmp")
            tmp.writeText(toJson(rec).toString())
            tmp.renameTo(File(dir, META))
        }
        android.util.Log.i("Replika", "recovered interrupted live take ${r.id.take(8)} (${ms} ms)")
        return rec
    }

    fun get(id: String): Recording? = _items.value.firstOrNull { it.id == id }

    fun audioFile(id: String) = File(File(root, id), AUDIO)

    /** Copies the picked file into app storage so playback keeps working after the URI grant expires. */
    suspend fun import(uri: Uri): Recording = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val dir = File(root, id).apply { mkdirs() }
        val name = displayName(uri) ?: "Запись"
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Не удалось прочитать файл" }
            File(dir, AUDIO).outputStream().use { input.copyTo(it) }
        }
        val rec = Recording(
            id = id,
            title = name.substringBeforeLast('.').ifBlank { name },
            sourceUri = uri.toString(),
            createdAtMs = System.currentTimeMillis(),
            durationMs = 0,
        )
        save(rec)
        rec
    }

    /** Registers an audio file that is already in app storage (microphone recording). */
    suspend fun adopt(file: File, title: String): Recording = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val dir = File(root, id).apply { mkdirs() }
        file.copyTo(File(dir, AUDIO), overwrite = true)
        file.delete()
        val rec = Recording(id, title, "mic", System.currentTimeMillis(), 0)
        save(rec)
        rec
    }

    /** Creates the entry a live session writes its WAV into ([audioFile]); state [RecordingState.RECORDING] until Stop. */
    suspend fun createLive(title: String, source: String = LIVE_SOURCE): Recording = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        liveHere += id
        File(root, id).mkdirs()
        val rec = Recording(id, title, source, System.currentTimeMillis(), 0, state = RecordingState.RECORDING)
        save(rec)
        rec
    }

    /** Serialises every read-modify-write, so a UI edit and the pipeline never overwrite each other. */
    private val lock = Mutex()

    suspend fun save(rec: Recording) = lock.withLock { write(rec) }

    private suspend fun write(rec: Recording) = withContext(Dispatchers.IO) {
        val dir = File(root, rec.id).apply { mkdirs() }
        val tmp = File(dir, "$META.tmp")
        tmp.writeText(toJson(rec).toString())
        tmp.renameTo(File(dir, META))
        _items.update { list -> (list.filterNot { it.id == rec.id } + rec).sortedByDescending { it.createdAtMs } }
    }

    suspend fun update(id: String, block: (Recording) -> Recording) = lock.withLock {
        val cur = get(id) ?: return@withLock
        val next = block(cur)
        if (next.state != cur.state) android.util.Log.i("Replika", "state ${id.take(8)}: ${cur.state} -> ${next.state}")
        write(next)
    }

    suspend fun delete(id: String) = lock.withLock {
        withContext(Dispatchers.IO) {
            File(root, id).deleteRecursively()
            _items.update { list -> list.filterNot { it.id == id } }
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    companion object {
        const val INTERRUPTED = "Обработка была прервана: приложение закрылось. Запустите её снова."
        const val LIVE_INTERRUPTED = "Запись прервалась: приложение закрылось. Звук до этого момента сохранён — запустите обработку."
        /** Shorter live takes are not kept (nothing useful to transcribe). */
        const val MIN_LIVE_MS = 1_000L
        private const val META = "recording.json"
        private const val AUDIO = "audio"

        fun toJson(r: Recording): JSONObject = JSONObject().apply {
            put("id", r.id); put("title", r.title); put("sourceUri", r.sourceUri)
            put("createdAtMs", r.createdAtMs); put("durationMs", r.durationMs)
            put("state", r.state.name); r.error?.let { put("error", it) }
            put("speakerNames", JSONObject(r.speakerNames))
            put("utterances", JSONArray().apply {
                r.utterances.forEach { u ->
                    put(JSONObject().apply {
                        put("speakerId", u.speakerId); put("startMs", u.startMs); put("endMs", u.endMs)
                        put("text", u.text); put("rawText", u.rawText)
                        u.speakerConfidence?.let { put("speakerConfidence", it.toDouble()) }
                        put("lang", u.lang)
                        if (u.edited) put("edited", true)
                    })
                }
            })
            r.summary?.let { s ->
                put("summary", JSONObject().apply {
                    put("points", JSONArray().apply {
                        s.points.forEach { p -> put(JSONObject().put("text", p.text).put("refs", JSONArray(p.utteranceIndices)).put("kind", p.kind.name)) }
                    })
                    put("actions", JSONArray().apply {
                        s.actions.forEach { a ->
                            put(JSONObject().put("text", a.text).put("speakerId", a.speakerId ?: JSONObject.NULL).put("ref", a.utteranceIndex)
                                .put("assignee", a.assignee ?: JSONObject.NULL).put("due", a.due ?: JSONObject.NULL).put("confirmed", a.confirmed))
                        }
                    })
                })
            }
            put("llmPending", r.llmPending)
            r.llmNote?.let { put("llmNote", it) }
            if (r.liveDraft.isNotEmpty()) put("liveDraft", JSONArray().apply {
                r.liveDraft.forEach { l ->
                    put(JSONObject().put("startMs", l.startMs).put("endMs", l.endMs).put("text", l.text).put("lang", l.lang)
                        .put("speaker", l.speaker ?: JSONObject.NULL))
                }
            })
            r.liveMetrics?.let { put("liveMetrics", it.toJson()) }
            r.metrics?.let { m ->
                put("metrics", JSONObject().apply {
                    put("audioDurationMs", m.audioDurationMs); put("decodeMs", m.decodeMs)
                    put("diarizationMs", m.diarizationMs); put("asrMs", m.asrMs); put("llmMs", m.llmMs)
                })
            }
        }

        fun fromJson(o: JSONObject): Recording {
            val names = o.optJSONObject("speakerNames")?.let { n -> n.keys().asSequence().associateWith { n.getString(it) } } ?: emptyMap()
            val utts = o.optJSONArray("utterances")?.let { a ->
                (0 until a.length()).map { i ->
                    val u = a.getJSONObject(i)
                    Utterance(
                        speakerId = u.getString("speakerId"),
                        startMs = u.getLong("startMs"),
                        endMs = u.getLong("endMs"),
                        text = u.getString("text"),
                        rawText = u.optString("rawText", u.getString("text")),
                        speakerConfidence = if (u.has("speakerConfidence")) u.getDouble("speakerConfidence").toFloat() else null,
                        lang = u.optString("lang", "ru"),
                        edited = u.optBoolean("edited", false),
                    )
                }
            } ?: emptyList()
            val summary = o.optJSONObject("summary")?.let { s ->
                MeetingSummary(
                    points = s.optJSONArray("points")?.let { a ->
                        (0 until a.length()).map { i ->
                            val p = a.getJSONObject(i)
                            val refs = p.getJSONArray("refs")
                            SummaryPoint(
                                p.getString("text"), (0 until refs.length()).map { refs.getInt(it) },
                                runCatching { PointKind.valueOf(p.optString("kind")) }.getOrDefault(PointKind.SUMMARY),
                            )
                        }
                    } ?: emptyList(),
                    actions = s.optJSONArray("actions")?.let { a ->
                        (0 until a.length()).map { i ->
                            val x = a.getJSONObject(i)
                            ActionItem(
                                text = x.getString("text"),
                                speakerId = if (x.isNull("speakerId")) null else x.getString("speakerId"),
                                utteranceIndex = x.getInt("ref"),
                                assignee = if (x.has("assignee") && !x.isNull("assignee")) x.getString("assignee") else null,
                                due = if (x.has("due") && !x.isNull("due")) x.getString("due") else null,
                                confirmed = x.optBoolean("confirmed", false),
                            )
                        }
                    } ?: emptyList(),
                )
            }
            val metrics = o.optJSONObject("metrics")?.let { m ->
                ProcessingMetrics(m.getLong("audioDurationMs"), m.getLong("decodeMs"), m.getLong("diarizationMs"), m.getLong("asrMs"), m.getLong("llmMs"))
            }
            val draft = o.optJSONArray("liveDraft")?.let { a ->
                (0 until a.length()).map { i ->
                    val l = a.getJSONObject(i)
                    LiveLine(l.getLong("startMs"), l.getLong("endMs"), l.getString("text"), l.optString("lang", "ru"),
                        if (l.isNull("speaker")) null else l.getInt("speaker"))
                }
            } ?: emptyList()
            return Recording(
                id = o.getString("id"), title = o.getString("title"), sourceUri = o.optString("sourceUri"),
                createdAtMs = o.getLong("createdAtMs"), durationMs = o.optLong("durationMs"),
                utterances = utts, speakerNames = names,
                state = runCatching { RecordingState.valueOf(o.getString("state")) }.getOrDefault(RecordingState.NEW),
                error = if (o.has("error")) o.getString("error") else null,
                summary = summary, metrics = metrics,
                llmPending = o.optBoolean("llmPending", false),
                llmNote = if (o.has("llmNote")) o.getString("llmNote") else null,
                liveDraft = draft,
                liveMetrics = o.optJSONObject("liveMetrics")?.let { LiveMetrics.fromJson(it) },
            )
        }
    }
}
