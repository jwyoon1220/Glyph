package io.github.jwyoon1220.glyph.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

class StorageRepository(private val rootDirectory: File) {

    init {
        if (!rootDirectory.exists()) {
            rootDirectory.mkdirs()
        }
    }

    private val settingsFile = File(rootDirectory, "project_metadata.glb")
    private val chaptersDir = File(rootDirectory, "chapters").apply { mkdirs() }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun saveSettings(settings: ProjectSettings) = withContext(Dispatchers.IO) {
        val bytes = ProtoBuf.encodeToByteArray(settings)
        settingsFile.writeBytes(bytes)
    }

    /**
     * Loads project settings from the .glb binary file using a memory-mapped NIO FileChannel
     * to minimise parsing overhead for large settings files.
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun loadSettings(): ProjectSettings = withContext(Dispatchers.IO) {
        if (!settingsFile.exists()) {
            return@withContext ProjectSettings("Untitled Project")
        }
        val bytes = RandomAccessFile(settingsFile, "r").use { raf ->
            raf.channel.use { channel ->
                val mappedBuffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
                ByteArray(mappedBuffer.remaining()).also { mappedBuffer.get(it) }
            }
        }
        ProtoBuf.decodeFromByteArray<ProjectSettings>(bytes)
    }

    /** Saves a chapter as a Markdown (.md) file. */
    suspend fun saveChapter(chapterId: String, content: String) = withContext(Dispatchers.IO) {
        val file = File(chaptersDir, "$chapterId.md")
        file.writeText(content)
    }

    /** Loads a chapter from its Markdown (.md) file. */
    suspend fun loadChapter(chapterId: String): String = withContext(Dispatchers.IO) {
        val file = File(chaptersDir, "$chapterId.md")
        if (file.exists()) file.readText() else ""
    }

    suspend fun listChapters(): List<String> = withContext(Dispatchers.IO) {
        chaptersDir.listFiles { _, name -> name.endsWith(".md") }
            ?.map { it.nameWithoutExtension }
            ?: emptyList()
    }
}
