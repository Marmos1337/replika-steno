package ru.replika.steno.core

/**
 * Keeps the LLM honest. The formatter may only change punctuation, capitalization and spacing
 * (plus spelling out of obvious fillers is NOT allowed either): the sequence of words, compared
 * letters-only and case-insensitively, must stay the same. Anything else is rejected and the raw
 * recognizer text is kept.
 */
object TextGuard {

    fun words(s: String): List<String> =
        s.lowercase()
            .replace('ё', 'е')
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() }

    /** True when [formatted] has exactly the same words as [raw]. */
    fun sameWords(raw: String, formatted: String): Boolean = words(raw) == words(formatted)

    /** Returns [formatted] if it passes the guard, otherwise [raw]. */
    fun accept(raw: String, formatted: String?): String {
        val f = formatted?.trim()?.trim('"', '«', '»')?.trim() ?: return raw
        if (f.isEmpty()) return raw
        return if (sameWords(raw, f)) f else raw
    }

    /** Upper-cases the first letter of the text and of every sentence after . ! ? … */
    fun capitalizeSentences(t: String): String {
        val sb = StringBuilder(t.length)
        var start = true
        for (c in t) {
            if (start && c.isLetter()) { sb.append(c.uppercaseChar()); start = false; continue }
            sb.append(c)
            if (c in ".!?…") start = true else if (!c.isWhitespace()) start = false
        }
        return sb.toString()
    }

    /** Cheap deterministic fallback used when the LLM is off or rejected: capitalize the first letter and end with a period. */
    fun tidy(raw: String): String {
        val t = raw.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return t
        val cap = capitalizeSentences(t)
        return if (cap.last() in ".!?…") cap else "$cap."
    }
}
