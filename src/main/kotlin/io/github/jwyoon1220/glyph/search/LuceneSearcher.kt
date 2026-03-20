package io.github.jwyoon1220.glyph.search

import org.apache.lucene.analysis.ko.KoreanAnalyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.TextField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.store.ByteBuffersDirectory

class LuceneSearcher {
    private val directory = ByteBuffersDirectory()
    private val analyzer = KoreanAnalyzer()

    fun indexDocument(id: String, content: String) {
        val config = IndexWriterConfig(analyzer)
        IndexWriter(directory, config).use { writer ->
            val doc = Document()
            doc.add(TextField("id", id, Field.Store.YES))
            doc.add(TextField("content", content, Field.Store.YES))
            writer.addDocument(doc)
        }
    }

    fun search(queryStr: String): List<String> {
        val results = mutableListOf<String>()
        try {
            DirectoryReader.open(directory).use { reader ->
                val searcher = IndexSearcher(reader)
                val parser = QueryParser("content", analyzer)
                val query = parser.parse(queryStr)
                val topDocs = searcher.search(query, 10)
                for (scoreDoc in topDocs.scoreDocs) {
                    val doc = searcher.storedFields().document(scoreDoc.doc)
                    results.add(doc.get("id"))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return results
    }
}
