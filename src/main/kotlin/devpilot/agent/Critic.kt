package devpilot.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLModel

data class CriticResult(val passed: Boolean, val feedback: String = "")

class Critic(private val executor: MultiLLMPromptExecutor?, private val model: LLModel?) {

    suspend fun evaluate(question: String, answer: String): CriticResult {
        if (executor == null || model == null) return CriticResult(passed = true)

        val agent = AIAgent(
            promptExecutor = executor,
            llmModel = model,
            systemPrompt = """You are a code answer quality evaluator. Check ALL criteria:
1. Cites actual file paths or function names as evidence (not vague descriptions)
2. Directly answers the question (not tangential)
3. Based on code evidence, not speculation

Reply ONLY with one of:
PASS
FAIL: [specific issue in one sentence]""",
            maxIterations = 1,
        )
        val result = runCatching {
            agent.run("Question: $question\n\nAnswer: ${answer.take(3_000)}")
        }.getOrElse { return CriticResult(passed = true) }

        return if (result.trimStart().startsWith("FAIL", ignoreCase = true)) {
            CriticResult(passed = false, feedback = result.substringAfter(":").trim())
        } else {
            CriticResult(passed = true)
        }
    }
}
