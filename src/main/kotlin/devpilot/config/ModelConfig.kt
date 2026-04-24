package devpilot.config

import kotlinx.serialization.Serializable

@Serializable
data class ModelConfig(
    val provider: Provider,
    val modelId: String,
    val apiKey: String? = null,   // null이면 DevPilotConfig.apiKeys에서 조회
    val baseUrl: String? = null,  // Ollama 전용 (기본값: http://localhost:11434)
)
