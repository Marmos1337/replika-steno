package ru.replika.steno.core

/**
 * The summary prompt shows the model numbered utterances "[n] speaker_k: text" and asks for
 *   ИТОГИ:
 *   - пункт [n, m]
 *   ПОРУЧЕНИЯ:
 *   - поручение [n]
 * Every line must cite existing utterance numbers; lines without a valid citation are dropped, so a
 * summary point can never exist without a link to what was actually said. The owner of an action is
 * taken from the cited utterance's speaker, never from the model's text.
 */
object SummaryParser {

    /** Keeps the prompt inside a small model's context: the head of a long meeting is used and that is reported. */
    const val MAX_CHARS = 7_000

    fun numbered(utts: List<Utterance>): String {
        val sb = StringBuilder()
        for ((i, u) in utts.withIndex()) {
            val line = "[${i + 1}] ${u.speakerId}: ${u.text}\n"
            if (sb.length + line.length > MAX_CHARS) break
            sb.append(line)
        }
        return sb.toString()
    }

    private val REF = Regex("\\[(\\d+(?:\\s*[,;]\\s*\\d+)*)\\]")

    fun parse(answer: String, utts: List<Utterance>): MeetingSummary {
        val points = ArrayList<SummaryPoint>()
        val actions = ArrayList<ActionItem>()
        var section = 0 // 1 = summary, 2 = actions
        for (rawLine in answer.lines()) {
            val line = rawLine.trim()
            val upper = line.uppercase()
            when {
                upper.startsWith("ИТОГ") || upper.startsWith("SUMMARY") -> { section = 1; continue }
                upper.startsWith("ПОРУЧ") || upper.startsWith("ЗАДАЧ") || upper.startsWith("ACTION") -> { section = 2; continue }
            }
            if (section == 0 || line.isEmpty()) continue
            val body = line.trimStart('-', '*', '•', ' ', '\t').let { it.replace(Regex("^\\d+[.)]\\s*"), "") }
            val refs = REF.findAll(body).flatMap { m -> m.groupValues[1].split(',', ';').map { it.trim().toInt() } }
                .map { it - 1 }.filter { it in utts.indices }.distinct().toList()
            val text = REF.replace(body, "").trim().trimEnd(',', ';').trim()
            if (refs.isEmpty() || text.length < 3) continue
            if (section == 1) points += SummaryPoint(text, refs)
            else actions += ActionItem(text, utts[refs.first()].speakerId, refs.first())
        }
        return MeetingSummary(points.take(8), actions.take(10))
    }
}
