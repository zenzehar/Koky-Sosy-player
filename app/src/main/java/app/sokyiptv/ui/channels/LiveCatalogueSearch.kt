/*
 * Soky IPTV Pro — Live catalogue search shares Movies/Series orthography rules.
 * GPL-3.0-or-later.
 */
package app.sokyiptv.ui.channels

import app.sokyiptv.data.parser.ArabicSearch

/** Search semantics are identical to the existing Movies/Series browser. */
internal object LiveCatalogueSearch {
    fun matches(title: String, query: String): Boolean {
        val trimmed = query.trim()
        return trimmed.isBlank() ||
            if (ArabicSearch.hasArabic(trimmed)) ArabicSearch.matches(title, trimmed)
            else title.contains(trimmed, ignoreCase = true)
    }
}
