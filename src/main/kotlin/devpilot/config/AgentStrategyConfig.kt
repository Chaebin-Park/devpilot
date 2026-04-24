package devpilot.config

import devpilot.agent.QuestionType
import kotlinx.serialization.Serializable

@Serializable
data class AgentStrategyConfig(
    val strategy: RoutingStrategy = RoutingStrategy.PRIMARY,
    val primary: ModelConfig = Presets.CLOUD_GOOGLE.primary,
    val specialists: Map<String, ModelConfig> = emptyMap(), // QuestionType.name -> ModelConfig
    val critic: ModelConfig? = null,    // null = primary 모델로 Critic 실행
    val fallback: ModelConfig? = null,  // null = fallback 없음 (오류 메시지 반환)
) {
    fun specialistFor(type: QuestionType): ModelConfig =
        specialists[type.name] ?: primary
}

object Presets {
    val CLOUD_GOOGLE = AgentStrategyConfig(
        strategy = RoutingStrategy.AUTO,
        primary = ModelConfig(Provider.GOOGLE, "gemini-2.5-flash"),
        specialists = mapOf(
            QuestionType.LOCATE.name  to ModelConfig(Provider.GOOGLE, "gemini-2.5-flash"),
            QuestionType.ANALYZE.name to ModelConfig(Provider.GOOGLE, "gemini-2.5-pro"),
            QuestionType.REASON.name  to ModelConfig(Provider.GOOGLE, "gemini-2.5-pro"),
            QuestionType.DEBUG.name   to ModelConfig(Provider.GOOGLE, "gemini-2.5-flash"),
        ),
        critic = ModelConfig(Provider.GOOGLE, "gemini-2.5-flash"),
    )

    val CLOUD_CLAUDE = AgentStrategyConfig(
        strategy = RoutingStrategy.AUTO,
        primary = ModelConfig(Provider.ANTHROPIC, "claude-sonnet-4-6"),
        specialists = mapOf(
            QuestionType.LOCATE.name  to ModelConfig(Provider.ANTHROPIC, "claude-haiku-4-5"),
            QuestionType.ANALYZE.name to ModelConfig(Provider.ANTHROPIC, "claude-sonnet-4-6"),
            QuestionType.REASON.name  to ModelConfig(Provider.ANTHROPIC, "claude-opus-4"),
            QuestionType.DEBUG.name   to ModelConfig(Provider.ANTHROPIC, "claude-haiku-4-5"),
        ),
        critic = ModelConfig(Provider.ANTHROPIC, "claude-haiku-4-5"),
    )

    val LOCAL = AgentStrategyConfig(
        strategy = RoutingStrategy.AUTO,
        primary = ModelConfig(Provider.OLLAMA, "qwen3"),
        specialists = mapOf(
            QuestionType.LOCATE.name  to ModelConfig(Provider.OLLAMA, "qwen3-coder"),
            QuestionType.ANALYZE.name to ModelConfig(Provider.OLLAMA, "qwen3"),
            QuestionType.REASON.name  to ModelConfig(Provider.OLLAMA, "qwen3"),
            QuestionType.DEBUG.name   to ModelConfig(Provider.OLLAMA, "qwen3-coder"),
        ),
        critic   = ModelConfig(Provider.OLLAMA, "qwen3"),
        fallback = null,
    )

    val HYBRID = AgentStrategyConfig(
        strategy = RoutingStrategy.AUTO,
        primary = ModelConfig(Provider.GOOGLE, "gemini-2.5-flash"),
        specialists = mapOf(
            QuestionType.LOCATE.name  to ModelConfig(Provider.OLLAMA, "qwen3-coder"),
            QuestionType.ANALYZE.name to ModelConfig(Provider.GOOGLE, "gemini-2.5-flash"),
            QuestionType.REASON.name  to ModelConfig(Provider.GOOGLE, "gemini-2.5-pro"),
            QuestionType.DEBUG.name   to ModelConfig(Provider.OLLAMA, "qwen3-coder"),
        ),
        critic   = ModelConfig(Provider.GOOGLE, "gemini-2.5-flash"),
        fallback = null,
    )

    fun fromName(name: String): AgentStrategyConfig? = when (name.lowercase()) {
        "cloud-google", "google" -> CLOUD_GOOGLE
        "cloud-claude", "claude" -> CLOUD_CLAUDE
        "local"                  -> LOCAL
        "hybrid"                 -> HYBRID
        else                     -> null
    }
}
