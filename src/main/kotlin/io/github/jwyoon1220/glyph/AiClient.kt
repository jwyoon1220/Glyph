package io.github.jwyoon1220.glyph

/** Common interface for AI suggestion clients (Gemini, Groq, …). */
interface AiClient {
    var apiKey: String
    suspend fun getSuggestion(contextText: String): String
}
