package io.github.zerro15.mystictranslate

// Изменено для MysticTranslate на основе подхода TranslationCache из overlay-translator.
internal val han = Regex("\\p{IsHan}")
private val whitespace = Regex("\\s+")

internal fun normalized(text: String): String = text.trim().replace(whitespace, " ")

internal data class Term(val chinese: String, val russian: String)
internal data class Segment(val text: String, val isTerm: Boolean)

internal fun segments(text: String, terms: List<Term>): List<Segment> {
    val bySource = terms.associate { it.chinese to it.russian }
    if (bySource.isEmpty()) return listOf(Segment(text, false))
    val pattern = bySource.keys.sortedByDescending { it.length }.joinToString("|", transform = Regex::escape)
    val result = mutableListOf<Segment>()
    var start = 0
    Regex(pattern).findAll(text).forEach { match ->
        if (match.range.first > start) result += Segment(text.substring(start, match.range.first), false)
        result += Segment(bySource.getValue(match.value), true)
        start = match.range.last + 1
    }
    if (start < text.length) result += Segment(text.substring(start), false)
    return result
}

internal fun parseTerms(text: String): List<Term> = text.lineSequence()
    .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    .map { line ->
        val chinese = line.substringBefore('=').trim()
        val russian = line.substringAfter('=', "").trim()
        require(han.containsMatchIn(chinese) && russian.isNotBlank()) {
            "В каждой строке укажите китайский термин и перевод через знак ="
        }
        Term(chinese, russian)
    }
    .distinctBy { it.chinese }.toList()

internal class TranslationCache<K>(private val capacity: Int = 256) :
    LinkedHashMap<K, String>(capacity, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, String>?): Boolean =
        size > capacity
}
