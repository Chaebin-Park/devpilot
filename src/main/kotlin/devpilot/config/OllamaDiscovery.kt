package devpilot.config

import java.net.HttpURLConnection
import java.net.URL

object OllamaDiscovery {
    private const val BASE_URL = "http://localhost:11434"
    private const val TIMEOUT_MS = 2_000

    fun isRunning(): Boolean = runCatching {
        val conn = URL("$BASE_URL/api/tags").openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.responseCode == 200
    }.getOrDefault(false)

    fun listModels(): List<String> = runCatching {
        val conn = URL("$BASE_URL/api/tags").openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        if (conn.responseCode != 200) return emptyList()
        val text = conn.inputStream.bufferedReader().readText()
        // {"models":[{"name":"llama3.2:latest",...}]}
        Regex(""""name"\s*:\s*"([^"]+)"""")
            .findAll(text)
            .map { it.groupValues[1] }
            .toList()
    }.getOrDefault(emptyList())
}
