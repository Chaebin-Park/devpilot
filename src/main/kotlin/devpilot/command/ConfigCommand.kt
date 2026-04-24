package devpilot.command

import devpilot.config.AgentStrategyConfig
import devpilot.config.DevPilotConfig
import devpilot.config.LLMClientFactory
import devpilot.config.ModelConfig
import devpilot.config.Presets
import devpilot.config.Provider
import devpilot.config.RoutingStrategy
import devpilot.config.StrategyConfigStorage

class ConfigCommand(
    private val storage: StrategyConfigStorage,
    private val onConfigChanged: (DevPilotConfig) -> Unit,
) : Command {
    override val name = "config"
    override val description = "에이전트 설정 관리"
    override val usage = "/config show | set apikey <provider> <key> | set strategy <auto|primary> | set primary <provider> <modelId> | preset <name>"

    override suspend fun execute(args: List<String>): CommandResult {
        if (args.isEmpty()) return showConfig()
        return when (args[0]) {
            "show"   -> showConfig()
            "set"    -> handleSet(args.drop(1))
            "preset" -> handlePreset(args.drop(1))
            else     -> CommandResult.Error("알 수 없는 서브커맨드: ${args[0]}. 사용법: $usage")
        }
    }

    private fun showConfig(): CommandResult {
        val config = storage.load()
        val s = config.strategy
        val lines = buildString {
            appendLine("── 현재 설정 ──────────────────────────────")
            appendLine("라우팅 전략: ${s.strategy.name.lowercase()}")
            appendLine("Primary 모델: ${s.primary.provider.name.lowercase()} / ${s.primary.modelId}")
            if (s.specialists.isNotEmpty()) {
                appendLine("Specialist 매핑:")
                s.specialists.forEach { (type, mc) ->
                    appendLine("  $type → ${mc.provider.name.lowercase()}/${mc.modelId}")
                }
            }
            s.critic?.let { appendLine("Critic 모델: ${it.provider.name.lowercase()} / ${it.modelId}") }
            appendLine()
            appendLine("등록된 API 키:")
            if (config.apiKeys.isEmpty()) appendLine("  (없음)")
            else config.apiKeys.forEach { (p, k) -> appendLine("  $p: ${k.take(8)}…") }
            appendLine()
            appendLine("프리셋: cloud-google | cloud-claude | local | hybrid")
        }
        return CommandResult.Success(lines)
    }

    private fun handleSet(args: List<String>): CommandResult {
        if (args.isEmpty()) return CommandResult.Error("set 서브커맨드가 필요합니다: apikey | strategy | primary")
        val config = storage.load()
        return when (args[0]) {
            "apikey" -> {
                if (args.size < 3) return CommandResult.Error("사용법: /config set apikey <provider> <key>")
                val provider = args[1].lowercase()
                val key = args[2]
                val updated = config.copy(apiKeys = config.apiKeys + (provider to key))
                storage.save(updated)
                onConfigChanged(updated)
                CommandResult.Success("$provider API 키가 저장되었습니다.")
            }
            "strategy" -> {
                if (args.size < 2) return CommandResult.Error("사용법: /config set strategy <auto|primary|manual>")
                val strategy = runCatching { RoutingStrategy.valueOf(args[1].uppercase()) }
                    .getOrElse { return CommandResult.Error("유효하지 않은 전략: ${args[1]}. auto | primary | manual 중 선택하세요.") }
                val updated = config.copy(strategy = config.strategy.copy(strategy = strategy))
                storage.save(updated)
                onConfigChanged(updated)
                CommandResult.Success("라우팅 전략이 ${strategy.name.lowercase()}(으)로 변경되었습니다.")
            }
            "primary" -> {
                if (args.size < 3) return CommandResult.Error("사용법: /config set primary <provider> <modelId>")
                val provider = runCatching { Provider.valueOf(args[1].uppercase()) }
                    .getOrElse { return CommandResult.Error("유효하지 않은 provider: ${args[1]}. google | anthropic | openai | ollama 중 선택하세요.") }
                val mc = ModelConfig(provider, args[2])
                val updated = config.copy(strategy = config.strategy.copy(primary = mc))
                storage.save(updated)
                onConfigChanged(updated)
                CommandResult.Success("Primary 모델이 ${provider.name.lowercase()}/${args[2]}(으)로 변경되었습니다.")
            }
            else -> CommandResult.Error("알 수 없는 set 서브커맨드: ${args[0]}")
        }
    }

    private fun handlePreset(args: List<String>): CommandResult {
        if (args.isEmpty()) return CommandResult.Error("사용법: /config preset <cloud-google|cloud-claude|local|hybrid>")
        val preset = Presets.fromName(args[0])
            ?: return CommandResult.Error("알 수 없는 프리셋: ${args[0]}. cloud-google | cloud-claude | local | hybrid 중 선택하세요.")
        val config = storage.load()
        val updated = config.copy(strategy = preset)
        storage.save(updated)
        onConfigChanged(updated)
        return CommandResult.Success("프리셋 '${args[0]}'이(가) 적용되었습니다.\n${showConfig().let { (it as? CommandResult.Success)?.message ?: "" }}")
    }
}
