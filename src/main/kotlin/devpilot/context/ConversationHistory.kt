package devpilot.context

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class HistoryEntry(val role: String, val content: String)

class ConversationHistory(baseDir: String) {
    private val mutex = Mutex()
    private val dir = File(baseDir, ".devpilot").also { it.mkdirs() }
    private val jsonlFile = File(dir, "session.jsonl")
    private val summaryFile = File(dir, "summary.md")

    suspend fun add(role: String, content: String) = mutex.withLock {
        val limit = if (role == "user") MAX_USER_ENTRY_LENGTH else MAX_ASSISTANT_ENTRY_LENGTH
        val line = Json.encodeToString(HistoryEntry(role, content.take(limit)))
        jsonlFile.appendText("$line\n")
    }

    fun getAllEntries(): List<HistoryEntry> {
        if (!jsonlFile.exists()) return emptyList()
        return jsonlFile.readLines()
            .filter { it.isNotBlank() }
            .mapNotNull { runCatching { Json.decodeFromString<HistoryEntry>(it) }.getOrNull() }
    }

    suspend fun keepRecent(count: Int) = mutex.withLock {
        val recent = jsonlFile.readLines()
            .filter { it.isNotBlank() }
            .mapNotNull { runCatching { Json.decodeFromString<HistoryEntry>(it) }.getOrNull() }
            .takeLast(count)
        jsonlFile.writeText(recent.joinToString("\n") { Json.encodeToString(it) } + "\n")
    }

    fun getSummary(): String? =
        if (summaryFile.exists()) summaryFile.readText().trim().takeIf { it.isNotBlank() } else null

    suspend fun saveSummary(text: String) = mutex.withLock {
        summaryFile.writeText(text)
    }

    suspend fun clear() = mutex.withLock {
        jsonlFile.delete()
        summaryFile.delete()
    }

    companion object {
        const val MAX_USER_ENTRY_LENGTH      = 4_000
        const val MAX_ASSISTANT_ENTRY_LENGTH = 8_000
        const val KEEP_RECENT        = 12
        const val COMPRESS_THRESHOLD = 24
    }
}
