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
private data class GroqMessage(val role: String, val content: String)

@Serializable
private data class GroqRequest(
    val messages: List<GroqMessage>,
    val model: String,
    val temperature: Double = 0.6,
    val max_completion_tokens: Int = 128,
    val top_p: Double = 0.95,
    val stream: Boolean = false,
    val stop: String? = null
)

@Serializable
private data class GroqChoice(val message: GroqMessage)

@Serializable
private data class GroqResponse(val choices: List<GroqChoice> = emptyList())

class GroqClient(
    override var apiKey: String,
    var model: String = "qwen/qwen3-32b"
) : AiClient {
    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val READ_TIMEOUT_SECONDS = 30L
        const val API_URL = "https://api.groq.com/openai/v1/chat/completions"
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
        val systemPrompt = """당신은 창작 소설 및 문서 편집기 'Glyph'에 내장된 AI 작가 보조 시스템입니다.
다음 지침을 엄격히 따르세요:
1. 기존 텍스트의 문체, 어조, 시점(1인칭/3인칭 등)을 그대로 유지하세요.
2. 등장인물의 이름, 성격, 관계를 일관되게 유지하세요.
3. 한 문장 이하의 짧은 텍스트(최대 50 어절)만 제안하세요.
4. 기존 텍스트를 절대 반복하지 마세요. 오직 이어지는 내용만 작성하세요.
5. 현재까지 약 ${wordCount}개의 단어가 작성되었습니다. 흐름을 자연스럽게 이어주세요."""

        val requestBody = json.encodeToString(
            GroqRequest(
                messages = listOf(
                    GroqMessage("system", systemPrompt),
                    GroqMessage("user", recentContext)
                ),
                model = model
            )
        )

        val request = Request.Builder()
            .url(API_URL)
            .header("Authorization", "Bearer $key")
            .post(requestBody.toRequestBody(mediaType))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext ""
                val body = response.body?.string() ?: return@withContext ""
                val groqResponse = json.decodeFromString<GroqResponse>(body)
                groqResponse.choices.firstOrNull()?.message?.content?.trim() ?: ""
            }
        } catch (e: Exception) {
            ""
        }
    }
}
