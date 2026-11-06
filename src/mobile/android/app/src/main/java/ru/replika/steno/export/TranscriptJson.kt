package ru.replika.steno.export

import org.json.JSONArray
import org.json.JSONObject
import ru.replika.steno.core.Recording
import ru.replika.steno.core.Utterance

/**
 * Export in the exact schema the organizers specified:
 * {"transcript":[{"speaker_id":"speaker_1","start_ms":1250,"end_ms":4380,"text":"Добрый день!"}]}
 *
 * Times are whole milliseconds. Keys and their order are fixed; display names are deliberately
 * NOT exported - only the stable speaker_id.
 */
object TranscriptJson {

    fun build(utterances: List<Utterance>): JSONObject {
        val arr = JSONArray()
        for (u in utterances) {
            val o = JSONObject()
            o.put("speaker_id", u.speakerId)
            o.put("start_ms", u.startMs)
            o.put("end_ms", u.endMs)
            o.put("text", u.text)
            arr.put(o)
        }
        return JSONObject().put("transcript", arr)
    }

    fun encode(utterances: List<Utterance>): String = build(utterances).toString(2)

    fun encode(recording: Recording): String = encode(recording.utterances)

    /**
     * Guards the invariants the acceptance check looks at. Returns the problems found;
     * an empty list means the payload is valid.
     */
    fun validate(utterances: List<Utterance>, audioDurationMs: Long?): List<String> {
        val problems = mutableListOf<String>()
        utterances.forEachIndexed { i, u ->
            if (u.startMs < 0) problems += "[$i] start_ms < 0 (${u.startMs})"
            if (u.endMs <= u.startMs) problems += "[$i] end_ms <= start_ms (${u.startMs}..${u.endMs})"
            if (audioDurationMs != null && u.endMs > audioDurationMs) {
                problems += "[$i] end_ms ${u.endMs} is past the audio duration $audioDurationMs"
            }
            if (!SPEAKER_ID.matches(u.speakerId)) problems += "[$i] speaker_id '${u.speakerId}' is not speaker_N"
        }
        return problems
    }

    private val SPEAKER_ID = Regex("^speaker_\\d+$")
}
