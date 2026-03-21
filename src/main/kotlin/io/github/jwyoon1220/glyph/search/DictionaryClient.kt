package io.github.jwyoon1220.glyph.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
data class DictResponse(val channel: DictChannel)

@Serializable
data class DictChannel(val total: Int, val item: List<DictItem> = emptyList())

@Serializable
data class DictItem(val word: String, val pos: String = "", val sense: DictSense)

@Serializable
data class DictSense(val definition: String)

class DictionaryClient {
    private val client = OkHttpClient()
    private val jsonBinding = Json { ignoreUnknownKeys = true; isLenient = true }
    private val apiKey = "015A1D4E54506B1C1ADE634E402469F0"

    suspend fun searchWord(word: String): List<DictItem> = withContext(Dispatchers.IO) {
        val url = "https://stdict.korean.go.kr/api/search.do?certkey_no=8871&key=$apiKey&type_search=search&req_type=json&q=${word}"
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            val body = response.body?.string() ?: return@withContext emptyList()
            try {
                val dictResponse = jsonBinding.decodeFromString<DictResponse>(body)
                dictResponse.channel.item
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
