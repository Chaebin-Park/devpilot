package devpilot.command

import devpilot.config.ModelConfig
import devpilot.config.Provider

class ModelCommand(
    private val onModelChanged: (ModelConfig?) -> Unit,
) : Command {
    override val name = "model"
    override val description = "이번 세션에서 사용할 모델 고정 (재시작 시 초기화)"
    override val usage = "/model <gemini|claude|gpt4o|qwen3-coder|qwen3|reset>"

    override suspend fun execute(args: List<String>): CommandResult {
        if (args.isEmpty()) return CommandResult.Error("사용법: $usage")
        val alias = args[0].lowercase()
        val (mc, label) = when (alias) {
            "gemini"          -> ModelConfig(Provider.GOOGLE,    "gemini-2.5-flash") to "Gemini 2.5 Flash"
            "gemini-pro"      -> ModelConfig(Provider.GOOGLE,    "gemini-2.5-pro")   to "Gemini 2.5 Pro"
            "claude", "sonnet"-> ModelConfig(Provider.ANTHROPIC, "claude-sonnet-4-6") to "Claude Sonnet 4.6"
            "claude-haiku",
            "haiku"           -> ModelConfig(Provider.ANTHROPIC, "claude-haiku-4-5") to "Claude Haiku 4.5"
            "claude-opus",
            "opus"            -> ModelConfig(Provider.ANTHROPIC, "claude-opus-4")    to "Claude Opus 4"
            "gpt4o", "gpt-4o" -> ModelConfig(Provider.OPENAI,   "gpt-4o")           to "GPT-4o"
            "gpt4o-mini"      -> ModelConfig(Provider.OPENAI,    "gpt-4o-mini")      to "GPT-4o Mini"
            "qwen3-coder"     -> ModelConfig(Provider.OLLAMA,    "qwen3-coder")      to "qwen3-coder (local)"
            "qwen3"           -> ModelConfig(Provider.OLLAMA,    "qwen3")            to "qwen3 (local)"
            "reset", "clear"  -> { onModelChanged(null); return CommandResult.Success("세션 모델 오버라이드가 해제되었습니다. 설정 파일의 전략으로 복귀합니다.") }
            else -> {
                // 직접 "google/gemini-2.5-pro" 또는 "ollama/qwen3-coder" 형식도 허용
                val parts = alias.split("/")
                if (parts.size == 2) {
                    val provider = runCatching { Provider.valueOf(parts[0].uppercase()) }.getOrElse {
                        return CommandResult.Error("알 수 없는 provider: ${parts[0]}")
                    }
                    ModelConfig(provider, parts[1]) to "${parts[0]}/${parts[1]}"
                } else {
                    return CommandResult.Error("알 수 없는 모델: $alias\n사용 가능: gemini | claude | haiku | opus | gpt4o | qwen3-coder | qwen3 | reset\n또는 직접 지정: google/gemini-2.5-pro")
                }
            }
        }
        onModelChanged(mc)
        return CommandResult.Success("이번 세션 모델: $label (리셋하려면 /model reset)")
    }
}
