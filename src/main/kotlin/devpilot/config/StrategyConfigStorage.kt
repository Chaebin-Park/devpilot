package devpilot.config

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class StrategyConfigStorage(private val targetDir: String) {
    private val globalFile = File(System.getProperty("user.home"), ".devpilot/config.json")
    private val projectFile = File(targetDir, ".devpilot/strategy.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): DevPilotConfig {
        val global = globalFile.readConfig<DevPilotConfig>() ?: DevPilotConfig()
        val projectStrategy = projectFile.readConfig<AgentStrategyConfig>()
        return if (projectStrategy != null) global.copy(strategy = projectStrategy) else global
    }

    fun save(config: DevPilotConfig) {
        globalFile.parentFile?.mkdirs()
        globalFile.writeText(json.encodeToString(config))
    }

    fun saveProjectStrategy(strategy: AgentStrategyConfig) {
        projectFile.parentFile?.mkdirs()
        projectFile.writeText(json.encodeToString(strategy))
    }

    private inline fun <reified T> File.readConfig(): T? {
        if (!exists()) return null
        return runCatching { json.decodeFromString<T>(readText()) }.getOrNull()
    }
}
