package ru.replika.steno.core

/**
 * The summary prompt shows the model numbered utterances "[n] speaker_k: text" and asks for
 *   КРАТКО:      - пункт [n, m]
 *   РЕШЕНИЯ:     - решение [n]
 *   ВОПРОСЫ:     - открытый вопрос [n]
 *   ПОРУЧЕНИЯ:   - что сделать | кому | срок [n]
 * Every line must cite existing utterance numbers; lines without a valid citation are dropped, so a
 * point can never exist without a link to what was actually said. For actions, "кому" and "срок"
 * are kept only if the cited utterances really contain those words - otherwise they stay empty.
 */
object SummaryParser {

    /**
     * The bundled model has a 1280-token window, so a meeting is summarised in windows of numbered
     * lines. Numbering is global, so references stay valid for the whole transcript.
     */
    fun windows(utts: List<Utterance>, maxChars: Int = 1_200, maxWindows: Int = 10): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for ((i, u) in utts.withIndex()) {
            val line = "[${i + 1}] ${u.speakerId}: ${u.text.take(maxChars / 2)}\n"
            if (sb.length + line.length > maxChars && sb.isNotEmpty()) {
                out += sb.toString(); sb.clear()
                if (out.size == maxWindows) return out
            }
            sb.append(line)
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    private val REF = Regex("\\[(\\d+(?:\\s*[,;]\\s*\\d+)*)\\]")
    private val EMPTY = setOf("", "-", "—", "нет", "не указан", "не указано", "не прозвучал", "не прозвучало", "неизвестно", "никто")

    private enum class Section { NONE, SUMMARY, DECISION, QUESTION, ACTION }

    fun parse(answer: String, utts: List<Utterance>): MeetingSummary {
        val points = ArrayList<SummaryPoint>()
        val actions = ArrayList<ActionItem>()
        var section = Section.NONE
        for (rawLine in answer.lines()) {
            val line = rawLine.trim().trim('*', '#').trim()
            val upper = line.uppercase()
            val header = when {
                upper.startsWith("КРАТК") || upper.startsWith("ИТОГ") || upper.startsWith("SUMMARY") -> Section.SUMMARY
                upper.startsWith("РЕШЕН") || upper.startsWith("DECISION") -> Section.DECISION
                upper.startsWith("ВОПРОС") || upper.startsWith("ОТКРЫТ") || upper.startsWith("QUESTION") -> Section.QUESTION
                upper.startsWith("ПОРУЧ") || upper.startsWith("ЗАДАЧ") || upper.startsWith("ACTION") -> Section.ACTION
                else -> null
            }
            if (header != null && line.length < 30 && !REF.containsMatchIn(line)) { section = header; continue }
            if (section == Section.NONE || line.isEmpty()) continue
            val body = line.trimStart('-', '*', '•', ' ', '\t').replace(Regex("^\\d+[.)]\\s*"), "")
            val cited = REF.findAll(body).flatMap { m -> m.groupValues[1].split(',', ';').map { it.trim().toInt() } }
                .map { it - 1 }.filter { it in utts.indices }.distinct().toList()
            val text = REF.replace(body, "").trim().trimEnd(',', ';', '.').trim()
            // the small model sometimes forgets the numbers: then link the lines that really contain its words
            val refs = cited.ifEmpty { matchRefs(text.replace('|', ' '), utts) }
            if (refs.isEmpty() || text.length < 3) continue
            val citedText = refs.joinToString(" ") { utts[it].text }
            if (!grounded(text.substringBefore('|'), citedText)) continue
            when (section) {
                Section.SUMMARY -> points += SummaryPoint(text, refs, PointKind.SUMMARY)
                Section.DECISION -> points += SummaryPoint(text, refs, PointKind.DECISION)
                Section.QUESTION -> points += SummaryPoint(text, refs, PointKind.QUESTION)
                Section.ACTION -> {
                    val parts = text.split('|').map { it.trim() }
                    val what = parts[0]
                    if (what.length < 3) continue
                    val said = citedText
                    actions += ActionItem(
                        text = what,
                        speakerId = utts[refs.first()].speakerId,
                        utteranceIndex = refs.first(),
                        assignee = parts.getOrNull(1)?.let { spoken(it, said) },
                        due = parts.getOrNull(2)?.let { spoken(it, said) },
                    )
                }
                Section.NONE -> Unit
            }
        }
        return MeetingSummary(points.take(24), actions.take(12))
    }

    /**
     * A point must be about what the cited lines say: at least one of its content words (5+ letters,
     * compared by a 5-letter stem) has to occur there. This drops template echoes and invented points.
     */
    fun grounded(text: String, cited: String): Boolean {
        val content = TextGuard.words(text).filter { it.length >= 5 }
        if (content.isEmpty()) return false
        val saidStems = TextGuard.words(cited).filter { it.length >= 5 }.map { it.take(5) }.toSet()
        return content.any { it.take(5) in saidStems }
    }

    /**
     * Utterances that share at least two content words (5-letter stems) with [text], best first, at
     * most two. Used only when the model gave no reference numbers.
     */
    fun matchRefs(text: String, utts: List<Utterance>): List<Int> {
        val stems = TextGuard.words(text).filter { it.length >= 5 }.map { it.take(5) }.toSet()
        if (stems.size < 2) return emptyList()
        return utts.indices
            .map { i -> i to TextGuard.words(utts[i].text).filter { it.length >= 5 }.map { it.take(5) }.toSet().count { it in stems } }
            .filter { it.second >= 2 }
            .sortedByDescending { it.second }
            .take(2)
            .map { it.first }
            .sorted()
    }

    /** Keeps [value] only if every word of it (by a 4-letter stem) occurs in [said]. */
    fun spoken(value: String, said: String): String? {
        val v = value.trim().trim('.', ',', '"', '«', '»')
        if (v.lowercase() in EMPTY) return null
        val saidWords = TextGuard.words(said)
        val words = TextGuard.words(v)
        if (words.isEmpty()) return null
        val ok = words.all { w -> val stem = w.take(4); saidWords.any { it.startsWith(stem) } }
        return if (ok) v else null
    }
}
