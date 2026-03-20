package io.github.jwyoon1220.glyph.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.File

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

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun loadSettings(): ProjectSettings = withContext(Dispatchers.IO) {
        if (!settingsFile.exists()) {
            return@withContext ProjectSettings("Untitled Project")
        }
        val bytes = settingsFile.readBytes()
        ProtoBuf.decodeFromByteArray<ProjectSettings>(bytes)
    }

    suspend fun saveChapter(chapterId: String, content: String) = withContext(Dispatchers.IO) {
        val file = File(chaptersDir, "$chapterId.txt")
        file.writeText(content)
    }

    suspend fun loadChapter(chapterId: String): String = withContext(Dispatchers.IO) {
        val file = File(chaptersDir, "$chapterId.txt")
        if (file.exists()) file.readText() else ""
    }

    suspend fun listChapters(): List<String> = withContext(Dispatchers.IO) {
        chaptersDir.listFiles { _, name -> name.endsWith(".txt") }
            ?.map { it.nameWithoutExtension }
            ?: emptyList()
    }
}
