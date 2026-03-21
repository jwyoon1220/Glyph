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

        val systemPrompt = "당신은 소설 작가 보조 AI입니다. 다음 글의 자연스러운 다음 내용을 한국어로 이어서 작성해주세요. " +
            "한 문장 또는 그 이하의 짧은 텍스트만 제안해주세요. 기존 텍스트를 반복하지 마세요."

        val requestBody = json.encodeToString(
            GroqRequest(
                messages = listOf(
                    GroqMessage("system", systemPrompt),
                    GroqMessage("user", contextText)
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
