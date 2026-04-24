package devpilot.config

import java.net.HttpURLConnection
import java.net.URL

object OllamaDiscovery {
    // OLLAMA_HOST 환경변수로 재정의 가능 (Docker: http://ollama:11434)
    private val baseUrl: String
        get() = System.getenv("OLLAMA_HOST")?.trimEnd('/') ?: "http://localhost:11434"
    private const val TIMEOUT_MS = 3_000

    fun isRunning(): Boolean = runCatching {
        val conn = URL("$baseUrl/api/tags").openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.responseCode == 200
    }.getOrDefault(false)

    fun listModels(): List<String> = runCatching {
        val conn = URL("$baseUrl/api/tags").openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        if (conn.responseCode != 200) return emptyList()
        val text = conn.inputStream.bufferedReader().readText()
        Regex(""""name"\s*:\s*"([^"]+)"""")
            .findAll(text)
            .map { it.groupValues[1] }
            .toList()
    }.getOrDefault(emptyList())
}
