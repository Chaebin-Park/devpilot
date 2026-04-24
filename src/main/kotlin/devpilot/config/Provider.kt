package devpilot.config

import kotlinx.serialization.Serializable

@Serializable
enum class Provider { GOOGLE, ANTHROPIC, OPENAI, OLLAMA }
