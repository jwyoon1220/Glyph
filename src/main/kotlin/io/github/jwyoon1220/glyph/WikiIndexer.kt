package io.github.jwyoon1220.glyph

import io.github.jwyoon1220.glyph.data.WikiGraph
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory index of Wiki node titles and their content summaries.
 *
 * Populated from [WikiGraph] instances whenever a wiki file is opened or saved.
 * Provides prefix-based suggestions for IntelliJ-style autocomplete in [GlyphTextArea].
 *
 * Thread-safe: backed by [ConcurrentHashMap] so it can be written from IO threads
 * and read from the EDT without additional locking.
 */
class WikiIndexer {

    /** Maps lower-cased term to its display title (preserving original casing). */
    private val termMap = ConcurrentHashMap<String, String>()

    /** Maps display title to a short content excerpt (first 200 chars). */
    private val excerptMap = ConcurrentHashMap<String, String>()

    /** Indexes all node titles from the given [WikiGraph]. */
    fun indexGraph(graph: WikiGraph) {
        for (node in graph.nodes) {
            val title = node.title.trim()
            if (title.isNotBlank()) {
                termMap[title.lowercase()] = title
                if (node.content.isNotBlank()) {
                    excerptMap[title] = node.content.take(200)
                }
            }
        }
    }

    /**
     * Returns up to [limit] wiki titles that start with [prefix] (case-insensitive).
     * Returns an empty list if [prefix] is blank or shorter than [minPrefixLength].
     */
    fun getSuggestions(prefix: String, limit: Int = 8, minPrefixLength: Int = 2): List<String> {
        if (prefix.length < minPrefixLength) return emptyList()
        val lower = prefix.lowercase()
        return termMap.entries
            .filter { it.key.startsWith(lower) }
            .map { it.value }
            .sortedBy { it.length }
            .take(limit)
    }

    /** Returns a short excerpt for [title], or null if not indexed. */
    fun getExcerpt(title: String): String? = excerptMap[title]

    /** Removes all indexed terms. */
    fun clear() {
        termMap.clear()
        excerptMap.clear()
    }

    /** Total number of indexed terms. */
    val size: Int get() = termMap.size
}
