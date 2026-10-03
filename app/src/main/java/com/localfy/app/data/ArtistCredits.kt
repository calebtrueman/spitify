package com.localfy.app.data

/** Commas and ampersands can belong to a band name; only split a comma at a known artist. */
object ArtistCredits {
    private val feature = Regex("(?i)\\s*\\(?\\b(?:feat\\.?|ft\\.?|featuring)\\s+")
    private val parenthesizedFeature = Regex("(?i)\\(\\s*(?:feat\\.?|ft\\.?|featuring)\\s+")
    fun names(credit: String, explicit: List<String>? = null, albumArtist: String? = null, known: List<String> = emptyList()): List<String> {
        if (!explicit.isNullOrEmpty()) return unique(explicit)
        if (';' !in credit && ", " !in credit && !credit.contains("feat", true) && !credit.contains("ft.", true) && !credit.contains("ft ", true)) return unique(listOf(credit))
        val candidates = unique(listOfNotNull(albumArtist) + known).filterNot { it.equals("Various Artists", true) }.sortedByDescending { it.length }
        val hasFeatureParenthesis = parenthesizedFeature.containsMatchIn(credit)
        val separated = credit.replace(feature, ";")
        return unique(separated.split(';').flatMap { raw ->
            val part = if (hasFeatureParenthesis) raw.trim().trimEnd(')').trim() else raw.trim()
            val main = candidates.firstOrNull { part.startsWith("$it, ", true) }
            if (main == null) listOf(part)
            else listOf(main) + names(part.substring(main.length + 2), known = candidates.filterNot { it.equals(main, true) })
        })
    }
    private fun unique(values: List<String>): List<String> = values.map(String::trim).filter(String::isNotEmpty).distinctBy(String::lowercase)
}
