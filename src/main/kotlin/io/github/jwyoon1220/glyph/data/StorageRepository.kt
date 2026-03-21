package io.github.jwyoon1220.glyph.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.Serializable
import java.io.BufferedInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

@Serializable
data class EpisodeData(
    val title: String,
    val content: String
)

class StorageRepository(private val rootDirectory: File) {

    companion object {
        private const val SMALL_FILE_THRESHOLD = 2 * 1024 * 1024L // 2 MB

        /** Reads a file: BufferedInputStream for ≤2MB, mmap for larger. */
        fun readFileBytes(file: File): ByteArray {
            return if (file.length() <= SMALL_FILE_THRESHOLD) {
                BufferedInputStream(file.inputStream()).use { it.readAllBytes() }
            } else {
                RandomAccessFile(file, "r").use { raf ->
                    raf.channel.use { channel ->
                        val buf = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
                        ByteArray(buf.remaining()).also { buf.get(it) }
                    }
                }
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
        settingsFile.writeBytes(bytes)
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
        if (file.name.endsWith(".gle")) {
            val episode = EpisodeData(file.nameWithoutExtension, content)
            file.writeBytes(ProtoBuf.encodeToByteArray(episode))
        } else {
            file.writeText(content)
        }
    }

    /** Loads content from an arbitrary file within rootDirectory. */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun loadFile(relativePath: String): String = withContext(Dispatchers.IO) {
        val file = File(rootDirectory, relativePath)
        if (!file.exists()) return@withContext ""
        if (file.name.endsWith(".gle")) {
            try {
                val bytes = readFileBytes(file)
                if (bytes.isEmpty()) return@withContext ""
                val episode = ProtoBuf.decodeFromByteArray<EpisodeData>(bytes)
                episode.content
            } catch (e: Exception) {
                e.printStackTrace()
                ""
            }
        } else {
            file.readText()
        }
    }

    /** Saves a WikiGraph to a .glw file using Protobuf. */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun saveWiki(relativePath: String, graph: WikiGraph) = withContext(Dispatchers.IO) {
        val file = File(rootDirectory, relativePath)
        file.parentFile?.mkdirs()
        file.writeBytes(ProtoBuf.encodeToByteArray(graph))
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
