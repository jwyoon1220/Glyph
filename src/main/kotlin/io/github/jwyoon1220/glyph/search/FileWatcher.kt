package io.github.jwyoon1220.glyph.search

import io.github.jwyoon1220.glyph.data.StorageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService

/**
 * Watches [watchRoot] for changes to Markdown (.md) and settings (.glb) files
 * and immediately (re-)indexes their content into [luceneSearcher].
 *
 * Indexing runs on [Dispatchers.IO] so it never blocks the Swing EDT.
 */
class FileWatcher(
    private val watchRoot: File,
    private val luceneSearcher: LuceneSearcher,
    private val storageRepository: StorageRepository
) {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var watchService: WatchService

    fun start() {
        scope.launch {
            watchService = FileSystems.getDefault().newWatchService()
            registerDirectory(watchRoot.toPath())

            // Register sub-directories that exist at startup
            watchRoot.walkTopDown()
                .filter { it.isDirectory && it != watchRoot }
                .forEach { registerDirectory(it.toPath()) }

            while (isActive) {
                val key = watchService.take() ?: break
                for (event in key.pollEvents()) {
                    val kind = event.kind()
                    if (kind == StandardWatchEventKinds.OVERFLOW) continue

                    @Suppress("UNCHECKED_CAST")
                    val changed = (event.context() as? Path) ?: continue
                    val file = (key.watchable() as Path).resolve(changed).toFile()

                    // If a new directory was created, register it so its files are watched too
                    if (kind == StandardWatchEventKinds.ENTRY_CREATE && file.isDirectory) {
                        registerDirectory(file.toPath())
                    } else {
                        indexFile(file)
                    }
                }
                if (!key.reset()) break
            }
            watchService.close()
        }
    }

    private fun registerDirectory(path: Path) {
        runCatching {
            path.register(
                watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY
            )
        }
    }

    private suspend fun indexFile(file: File) {
        when {
            file.name.endsWith(".md") -> {
                if (file.canRead()) {
                    val content = withContext(Dispatchers.IO) { file.readText() }
                    luceneSearcher.indexDocument(file.nameWithoutExtension, content)
                }
            }
            file.name.endsWith(".glb") -> {
                // Index metadata from .glb: use file name as identifier.
                // Full deserialization is done by StorageRepository on demand.
                luceneSearcher.indexDocument(file.nameWithoutExtension, file.name)
            }
        }
    }

    fun stop() {
        scope.coroutineContext[Job]?.cancel()
    }
}
