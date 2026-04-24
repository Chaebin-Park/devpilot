package devpilot.memory

import java.io.File

class ProjectMemoryStorage(private val filePath: String) {
    private val file = File(filePath)

    fun add(memory: String) {
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.writeText("# DevPilot 프로젝트 메모리\n\n")
        }
        file.appendText("- $memory\n")
    }

    fun load(): List<String> {
        if (!file.exists()) return emptyList()
        return file.readLines()
            .filter { it.startsWith("- ") }
            .map { it.removePrefix("- ") }
    }

    fun delete(index: Int): Boolean {
        val memories = load()
        if (index < 1 || index > memories.size) return false
        val remaining = memories.filterIndexed { i, _ -> i != index - 1 }
        file.writeText("# DevPilot 프로젝트 메모리\n\n" + remaining.joinToString("\n") { "- $it" } + if (remaining.isNotEmpty()) "\n" else "")
        return true
    }
}
