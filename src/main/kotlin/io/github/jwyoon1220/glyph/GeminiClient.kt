package io.github.jwyoon1220.glyph

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@Serializable
private data class GeminiPart(val text: String)

@Serializable
private data class GeminiContent(val parts: List<GeminiPart>)

@Serializable
private data class GeminiRequest(val contents: List<GeminiContent>)

@Serializable
private data class GeminiCandidate(val content: GeminiContent)

@Serializable
private data class GeminiResponse(val candidates: List<GeminiCandidate> = emptyList())

class GeminiClient(override var apiKey: String) : AiClient {
    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val READ_TIMEOUT_SECONDS = 30L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun getSuggestion(contextText: String): String = withContext(Dispatchers.IO) {
        val key = apiKey
        if (key.isBlank()) return@withContext ""

        val prompt = "당신은 소설 작가 보조 AI입니다. 다음 글의 자연스러운 다음 내용을 한국어로 이어서 작성해주세요. " +
            "한 문장 또는 그 이하의 짧은 텍스트만 제안해주세요. 기존 텍스트를 반복하지 마세요.\n\n글:\n$contextText"

        val requestBody = json.encodeToString(
            GeminiRequest(listOf(GeminiContent(listOf(GeminiPart(prompt)))))
        )

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent")
            .header("X-Goog-Api-Key", key)
            .post(requestBody.toRequestBody(mediaType))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext ""
                val body = response.body?.string() ?: return@withContext ""
                val geminiResponse = json.decodeFromString<GeminiResponse>(body)
                geminiResponse.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text?.trim() ?: ""
            }
        } catch (e: Exception) {
            ""
        }
    }
}
