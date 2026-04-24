package devpilot.context

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class WorkingMemory(
    val confirmedFacts: List<String> = emptyList(),
    val openQuestions: List<String> = emptyList(),
    val exploredFiles: List<String> = emptyList(),
)

class WorkingMemoryStorage(baseDir: String) {
    private val file = File(baseDir, ".devpilot/working_memory.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun load(): WorkingMemory {
        if (!file.exists()) return WorkingMemory()
        return runCatching { json.decodeFromString<WorkingMemory>(file.readText()) }
            .getOrDefault(WorkingMemory())
    }

    fun save(memory: WorkingMemory) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(memory))
    }

    fun clear() { file.delete() }

    fun isEmpty() = load().let {
        it.confirmedFacts.isEmpty() && it.openQuestions.isEmpty() && it.exploredFiles.isEmpty()
    }
}
