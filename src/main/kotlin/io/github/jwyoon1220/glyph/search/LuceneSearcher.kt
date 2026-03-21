package io.github.jwyoon1220.glyph.search

import org.apache.lucene.analysis.ko.KoreanAnalyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.store.ByteBuffersDirectory

/**
 * Lucene-backed full-text searcher optimised for throughput.
 *
 * Key improvements over the naïve version:
 *  - A **single** [IndexWriter] is kept open for the lifetime of the instance
 *    (re-opening a writer on every call was the dominant cost).
 *  - [updateDocument] replaces an existing document by its "id" term, so
 *    repeated saves don't bloat the index.
 *  - A **Near Real-Time** (NRT) reader is obtained from the writer, avoiding
 *    a full directory scan on every search.
 *  - The NRT reader is cached and refreshed lazily (only when the writer has
 *    made new commits since the last search).
 */
class LuceneSearcher : AutoCloseable {
    private val directory = ByteBuffersDirectory()
    private val analyzer  = KoreanAnalyzer()
    private val writer: IndexWriter = IndexWriter(
        directory,
        IndexWriterConfig(analyzer).apply {
            openMode = IndexWriterConfig.OpenMode.CREATE_OR_APPEND
            // Tune RAM buffer: larger buffer → fewer flushes → better throughput
            ramBufferSizeMB = 32.0
        }
    )

    // NRT reader/searcher cache — guarded by [searchLock].
    private val searchLock = Any()
    private var nrtReader: DirectoryReader? = null

    /** Index (or replace) a document identified by [id]. Thread-safe. */
    fun indexDocument(id: String, content: String) {
        val doc = Document().apply {
            add(StringField("id",      id,      Field.Store.YES))
            add(TextField("content",  content, Field.Store.YES))
        }
        // updateDocument atomically deletes any existing doc with this id
        // and adds the new one — no duplicates, no manual delete needed.
        writer.updateDocument(Term("id", id), doc)
        // Soft-commit: makes the change visible to NRT readers without an
        // expensive fsync.
        writer.flush()
        invalidateNrtReader()
    }

    /** Full-text search; returns up to 10 matching document ids. */
    fun search(queryStr: String): List<String> {
        val results = mutableListOf<String>()
        try {
            val reader   = nrtReader()  ?: return results
            val searcher = IndexSearcher(reader)
            val parser   = QueryParser("content", analyzer)
            val query    = parser.parse(queryStr)
            val topDocs  = searcher.search(query, 10)
            for (scoreDoc in topDocs.scoreDocs) {
                results.add(searcher.storedFields().document(scoreDoc.doc).get("id"))
            }
        } catch (_: Exception) {}
        return results
    }

    // ----------------------------------------------------------------- NRT

    private fun invalidateNrtReader() = synchronized(searchLock) { nrtReader = null }

    /** Returns a live (possibly refreshed) DirectoryReader for NRT searching. */
    private fun nrtReader(): DirectoryReader? = synchronized(searchLock) {
        try {
            val existing = nrtReader
            nrtReader = if (existing == null) {
                DirectoryReader.open(writer)
            } else {
                DirectoryReader.openIfChanged(existing, writer) ?: existing
            }
            nrtReader
        } catch (_: Exception) { null }
    }

    override fun close() {
        synchronized(searchLock) { nrtReader?.close(); nrtReader = null }
        writer.close()
        directory.close()
    }
}
