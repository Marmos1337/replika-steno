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
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.replika.steno.core.ActionItem
import ru.replika.steno.core.MeetingSummary
import ru.replika.steno.core.ProcessingMetrics
import ru.replika.steno.core.Recording
import ru.replika.steno.core.RecordingState
import ru.replika.steno.core.SummaryPoint
import ru.replika.steno.core.Utterance

/**
 * Local-only persistence: every recording is a folder in filesDir with the audio copy and a JSON
 * document. No database, nothing synced anywhere.
 */
class RecordingStore(private val context: Context) {

    private val root = File(context.filesDir, "recordings").apply { mkdirs() }
    private val _items = MutableStateFlow<List<Recording>>(emptyList())
    val items: StateFlow<List<Recording>> = _items.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        val list = root.listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { dir ->
            runCatching { fromJson(JSONObject(File(dir, META).readText())) }.getOrNull()
        }.map {
            // a run interrupted by process death cannot resume half-way: mark it so the user can restart it
            if (it.state == RecordingState.PROCESSING) it.copy(state = RecordingState.FAILED, error = "Обработка была прервана. Запустите её снова.") else it
        }
        _items.value = list.sortedByDescending { it.createdAtMs }
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

    suspend fun save(rec: Recording) = withContext(Dispatchers.IO) {
        val dir = File(root, rec.id).apply { mkdirs() }
        val tmp = File(dir, "$META.tmp")
        tmp.writeText(toJson(rec).toString())
        tmp.renameTo(File(dir, META))
        _items.update { list -> (list.filterNot { it.id == rec.id } + rec).sortedByDescending { it.createdAtMs } }
    }

    suspend fun update(id: String, block: (Recording) -> Recording) {
        val cur = get(id) ?: return
        save(block(cur))
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(root, id).deleteRecursively()
        _items.update { list -> list.filterNot { it.id == id } }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    companion object {
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
                    })
                }
            })
            r.summary?.let { s ->
                put("summary", JSONObject().apply {
                    put("points", JSONArray().apply {
                        s.points.forEach { p -> put(JSONObject().put("text", p.text).put("refs", JSONArray(p.utteranceIndices))) }
                    })
                    put("actions", JSONArray().apply {
                        s.actions.forEach { a ->
                            put(JSONObject().put("text", a.text).put("speakerId", a.speakerId ?: JSONObject.NULL).put("ref", a.utteranceIndex))
                        }
                    })
                })
            }
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
                    )
                }
            } ?: emptyList()
            val summary = o.optJSONObject("summary")?.let { s ->
                MeetingSummary(
                    points = s.optJSONArray("points")?.let { a ->
                        (0 until a.length()).map { i ->
                            val p = a.getJSONObject(i)
                            val refs = p.getJSONArray("refs")
                            SummaryPoint(p.getString("text"), (0 until refs.length()).map { refs.getInt(it) })
                        }
                    } ?: emptyList(),
                    actions = s.optJSONArray("actions")?.let { a ->
                        (0 until a.length()).map { i ->
                            val x = a.getJSONObject(i)
                            ActionItem(x.getString("text"), if (x.isNull("speakerId")) null else x.getString("speakerId"), x.getInt("ref"))
                        }
                    } ?: emptyList(),
                )
            }
            val metrics = o.optJSONObject("metrics")?.let { m ->
                ProcessingMetrics(m.getLong("audioDurationMs"), m.getLong("decodeMs"), m.getLong("diarizationMs"), m.getLong("asrMs"), m.getLong("llmMs"))
            }
            return Recording(
                id = o.getString("id"), title = o.getString("title"), sourceUri = o.optString("sourceUri"),
                createdAtMs = o.getLong("createdAtMs"), durationMs = o.optLong("durationMs"),
                utterances = utts, speakerNames = names,
                state = runCatching { RecordingState.valueOf(o.getString("state")) }.getOrDefault(RecordingState.NEW),
                error = if (o.has("error")) o.getString("error") else null,
                summary = summary, metrics = metrics,
            )
        }
    }
}
