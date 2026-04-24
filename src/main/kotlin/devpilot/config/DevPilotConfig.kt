package devpilot.config

import kotlinx.serialization.Serializable

@Serializable
data class DevPilotConfig(
    val apiKeys: Map<String, String> = emptyMap(), // "google" | "anthropic" | "openai" -> key
    val strategy: AgentStrategyConfig = AgentStrategyConfig(),
) {
    fun apiKey(provider: Provider): String? =
        apiKeys[provider.name.lowercase()]
}
