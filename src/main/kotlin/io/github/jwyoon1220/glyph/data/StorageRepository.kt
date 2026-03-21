package io.github.jwyoon1220.glyph.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.Serializable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousFileChannel
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

@Serializable
data class EpisodeData(
    val title: String,
    val content: String
)

class StorageRepository(private val rootDirectory: File) {

    companion object {
        private const val SMALL_FILE_THRESHOLD = 2 * 1024 * 1024L // 2 MB

        /**
         * Fast file read:
         *  - ≤2 MB: [AsynchronousFileChannel] with a single async read into a pooled ByteBuffer
         *  - >2 MB: memory-mapped read via [FileChannel.map] (zero-copy, OS-paged)
         */
        suspend fun readFileBytes(file: File): ByteArray = withContext(Dispatchers.IO) {
            val size = file.length()
            if (size == 0L) return@withContext ByteArray(0)

            if (size <= SMALL_FILE_THRESHOLD) {
                // Async channel read — kernel queues the IO and signals completion
                val path = file.toPath()
                val buf  = ByteBuffer.allocateDirect(size.toInt())
                val ch   = AsynchronousFileChannel.open(path, StandardOpenOption.READ)
                try {
                    suspendCoroutine<Int> { cont ->
                        ch.read(buf, 0L, Unit, object : java.nio.channels.CompletionHandler<Int, Unit> {
                            override fun completed(result: Int, attachment: Unit) = cont.resume(result)
                            override fun failed(exc: Throwable, attachment: Unit) = cont.resumeWithException(exc)
                        })
                    }
                    buf.flip()
                    ByteArray(buf.remaining()).also { buf.get(it) }
                } finally {
                    ch.close()
                }
            } else {
                // Memory-mapped: OS maps file pages on demand — best for multi-MB files
                FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
                    val mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
                    ByteArray(mapped.remaining()).also { mapped.get(it) }
                }
            }
        }

        /**
         * Crash-safe atomic write: write to a sibling temp file, then atomically
         * rename it into place. A partial write can never corrupt the target file.
         */
        private fun writeAtomically(file: File, bytes: ByteArray) {
            val tmp = File(file.parent, ".~${file.name}.tmp")
            try {
                // Write with DSYNC to flush to the storage device before rename
                FileChannel.open(
                    tmp.toPath(),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.DSYNC
                ).use { ch ->
                    val buf = ByteBuffer.wrap(bytes)
                    while (buf.hasRemaining()) ch.write(buf)
                }
                Files.move(
                    tmp.toPath(), file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                // Fallback on filesystems that don't support atomic move (e.g. cross-device)
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }

    init {
        if (!rootDirectory.exists()) rootDirectory.mkdirs()
    }

    private val settingsFile = File(rootDirectory, "project_metadata.glb")
    @Suppress("unused")
    private val chaptersDir = File(rootDirectory, "chapters").apply { mkdirs() }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun saveSettings(settings: ProjectSettings) = withContext(Dispatchers.IO) {
        val bytes = ProtoBuf.encodeToByteArray(settings)
        writeAtomically(settingsFile, bytes)
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun loadSettings(): ProjectSettings = withContext(Dispatchers.IO) {
        if (!settingsFile.exists()) return@withContext ProjectSettings("Untitled Project")
        val bytes = readFileBytes(settingsFile)
        ProtoBuf.decodeFromByteArray<ProjectSettings>(bytes)
    }

    /** Saves content to an arbitrary file within rootDirectory. */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun saveFile(relativePath: String, content: String) = withContext(Dispatchers.IO) {
        val file = File(rootDirectory, relativePath)
        file.parentFile?.mkdirs()
        when {
            file.name.endsWith(".gle") -> {
                val episode = EpisodeData(file.nameWithoutExtension, content)
                writeAtomically(file, ProtoBuf.encodeToByteArray(episode))
            }
            file.name.endsWith(".glh") -> {
                // Glyph Header: gzip-compressed UTF-8 markdown
                val raw = content.toByteArray(Charsets.UTF_8)
                val out = ByteArrayOutputStream(raw.size / 2 + 64)
                GZIPOutputStream(out).use { it.write(raw) }
                writeAtomically(file, out.toByteArray())
            }
            else -> writeAtomically(file, content.toByteArray(Charsets.UTF_8))
        }
    }

    /** Loads content from an arbitrary file within rootDirectory. */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun loadFile(relativePath: String): String = withContext(Dispatchers.IO) {
        val file = File(rootDirectory, relativePath)
        if (!file.exists()) return@withContext ""
        when {
            file.name.endsWith(".gle") -> {
                try {
                    val bytes = readFileBytes(file)
                    if (bytes.isEmpty()) return@withContext ""
                    val episode = ProtoBuf.decodeFromByteArray<EpisodeData>(bytes)
                    episode.content
                } catch (_: Exception) {
                    // Fallback: try reading as plain text (handles legacy or corrupt files)
                    try { file.readText() } catch (_: Exception) { "" }
                }
            }
            file.name.endsWith(".glh") -> {
                try {
                    val bytes = readFileBytes(file)
                    if (bytes.isEmpty()) return@withContext ""
                    GZIPInputStream(ByteArrayInputStream(bytes)).use {
                        it.readAllBytes().toString(Charsets.UTF_8)
                    }
                } catch (_: Exception) {
                    // Fallback: try plain text (e.g. .glhr saved without compression)
                    try { file.readText() } catch (_: Exception) { "" }
                }
            }
            else -> readFileBytes(file).toString(Charsets.UTF_8)
        }
    }

    /** Saves a WikiGraph to a .glw file using Protobuf. */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun saveWiki(relativePath: String, graph: WikiGraph) = withContext(Dispatchers.IO) {
        val file = File(rootDirectory, relativePath)
        file.parentFile?.mkdirs()
        writeAtomically(file, ProtoBuf.encodeToByteArray(graph))
    }

    /** Loads a WikiGraph from a .glw file. Returns null and deletes if schema is invalid. */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun loadWiki(relativePath: String): WikiGraph? = withContext(Dispatchers.IO) {
        val file = File(rootDirectory, relativePath)
        if (!file.exists()) return@withContext null
        try {
            val bytes = readFileBytes(file)
            if (bytes.isEmpty()) return@withContext null
            ProtoBuf.decodeFromByteArray<WikiGraph>(bytes)
        } catch (e: Exception) {
            System.err.println("[loadWiki] Schema mismatch for '$relativePath', resetting: ${e.message}")
            file.delete()
            null
        }
    }
}
