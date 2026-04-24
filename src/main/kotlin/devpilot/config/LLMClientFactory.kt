package devpilot.config

import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel

object LLMClientFactory {

    fun createExecutor(config: ModelConfig, apiKeys: Map<String, String>): MultiLLMPromptExecutor {
        val key = config.apiKey ?: apiKeys[config.provider.name.lowercase()]
        return when (config.provider) {
            Provider.GOOGLE    -> MultiLLMPromptExecutor(GoogleLLMClient(key ?: envKey("GOOGLE_API_KEY")))
            Provider.ANTHROPIC -> MultiLLMPromptExecutor(AnthropicLLMClient(key ?: envKey("ANTHROPIC_API_KEY")))
            Provider.OPENAI    -> MultiLLMPromptExecutor(OpenAILLMClient(key ?: envKey("OPENAI_API_KEY")))
            Provider.OLLAMA    -> MultiLLMPromptExecutor(OllamaClient(
                config.baseUrl
                    ?: System.getenv("OLLAMA_HOST")?.trimEnd('/')
                    ?: "http://localhost:11434"
            ))
        }
    }

    fun createLLModel(config: ModelConfig): LLModel = when (config.provider) {
        Provider.GOOGLE    -> googleModel(config.modelId)
        Provider.ANTHROPIC -> anthropicModel(config.modelId)
        Provider.OPENAI    -> openAiModel(config.modelId)
        Provider.OLLAMA    -> ollamaModel(config.modelId, config.baseUrl)
    }

    private fun envKey(envVar: String): String =
        System.getenv(envVar)
            ?: error("API key not found. Set it via /config set apikey ${envVar.substringBefore("_API_KEY").lowercase()} <key>")

    private fun googleModel(id: String): LLModel = when (id) {
        "gemini-2.5-flash", "gemini-2.5-flash-preview-05-20" -> GoogleModels.Gemini2_5Flash
        "gemini-2.5-pro"                                      -> GoogleModels.Gemini2_5Pro
        else -> GoogleModels.Gemini2_5Flash
    }

    private fun anthropicModel(id: String): LLModel = when (id) {
        "claude-haiku-3"                               -> AnthropicModels.Haiku_3
        "claude-haiku-4-5"                             -> AnthropicModels.Haiku_4_5
        "claude-sonnet-4", "claude-sonnet-4-0"         -> AnthropicModels.Sonnet_4
        "claude-sonnet-4-5"                            -> AnthropicModels.Sonnet_4_5
        "claude-sonnet-4-6"                            -> AnthropicModels.Sonnet_4_6
        "claude-opus-4", "claude-opus-4-0"             -> AnthropicModels.Opus_4
        "claude-opus-4-1"                              -> AnthropicModels.Opus_4_1
        "claude-opus-4-5"                              -> AnthropicModels.Opus_4_5
        "claude-opus-4-6"                              -> AnthropicModels.Opus_4_6
        else -> AnthropicModels.Sonnet_4_6
    }

    private fun openAiModel(id: String): LLModel = LLModel(
        provider = LLMProvider.OpenAI,
        id = id,
        capabilities = listOf(
            LLMCapability.Temperature,
            LLMCapability.Schema.JSON.Basic,
            LLMCapability.Tools,
        ),
        contextLength = 128_000,
    )

    private fun ollamaModel(id: String, baseUrl: String?): LLModel = LLModel(
        provider = LLMProvider.Ollama,
        id = id,
        capabilities = listOf(
            LLMCapability.Temperature,
            LLMCapability.Schema.JSON.Basic,
            LLMCapability.Tools,
        ),
        contextLength = 131_072,
    )
}
