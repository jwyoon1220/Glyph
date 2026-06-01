package io.github.jwyoon1220.glyph.search

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService

/**
 * Watches [watchRoot] for file creation and modification events and exposes them
 * as a reactive [SharedFlow] of [File]s.
 */
class FileWatcher(private val watchRoot: File) {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var watchService: WatchService

    private val _eventFlow = MutableSharedFlow<File>(extraBufferCapacity = 64)
    
    /**
     * A reactive stream of changed files.
     */
    val eventFlow: SharedFlow<File> = _eventFlow.asSharedFlow()

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
                    } else if (file.isFile) {
                        _eventFlow.tryEmit(file)
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

    fun stop() {
        scope.coroutineContext[Job]?.cancel()
    }
}
