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

    override suspend fun getSuggestion(contextText: String): String = withContext(Dispatchers.IO) {
        val key = apiKey
        if (key.isBlank()) return@withContext ""

        val wordCount = contextText.trim().split("\\s+".toRegex()).size
        val recentContext = if (contextText.length > 2000) contextText.takeLast(2000) else contextText
        val prompt = """당신은 창작 소설 및 문서 편집기 'Glyph'에 내장된 AI 작가 보조 시스템입니다.
다음 지침을 엄격히 따르세요:
1. 기존 텍스트의 문체, 어조, 시점(1인칭/3인칭 등)을 그대로 유지하세요.
2. 등장인물의 이름, 성격, 관계를 일관되게 유지하세요.
3. 한 문장 이하의 짧은 텍스트(최대 50 어절)만 제안하세요.
4. 기존 텍스트를 절대 반복하지 마세요. 오직 이어지는 내용만 작성하세요.
5. 현재까지 약 ${wordCount}개의 단어가 작성되었습니다. 흐름을 자연스럽게 이어주세요.

--- 현재까지의 글 ---
$recentContext"""

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
