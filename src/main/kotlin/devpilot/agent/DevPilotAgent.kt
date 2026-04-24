package devpilot.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.prompt.dsl.prompt as koogPrompt
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.serialization.JSONPrimitive
import devpilot.config.DevPilotConfig
import devpilot.config.LLMClientFactory
import devpilot.config.ModelConfig
import devpilot.config.Provider
import devpilot.config.RoutingStrategy
import devpilot.context.ConversationHistory
import devpilot.context.WorkingMemoryStorage
import devpilot.memory.ProjectMemoryStorage
import devpilot.tools.CodeSearchTool
import devpilot.tools.ListFilesTool
import devpilot.tools.ReadFileTool

class DevPilotAgent(
    @Volatile private var config: DevPilotConfig,
    private val memoryStorage: ProjectMemoryStorage,
    private val targetDir: String = System.getProperty("user.dir"),
) {
    val conversationHistory = ConversationHistory(targetDir)
    private val workingMemoryStorage = WorkingMemoryStorage(targetDir)
    private val toolRegistry = ToolRegistry {
        tools(ReadFileTool(targetDir))
        tools(ListFilesTool(targetDir))
        tools(CodeSearchTool(targetDir))
    }

    private val router = Router()

    // Session-level model override set by /model command
    @Volatile var sessionModelOverride: ModelConfig? = null
    @Volatile private var primaryFailed = false
    @Volatile private var consecutiveFallbackSuccesses = 0

    var lastUsedModel: String = config.strategy.primary.modelId
        private set

    @Volatile var progressSink: (String) -> Unit = ::println
    private fun progress(msg: String) = progressSink(msg)

    fun updateConfig(newConfig: DevPilotConfig) {
        config = newConfig
        primaryFailed = false
        consecutiveFallbackSuccesses = 0
    }

    fun resetSessionOverride() {
        sessionModelOverride = null
        primaryFailed = false
        consecutiveFallbackSuccesses = 0
    }

    fun forcePrimary() {
        primaryFailed = false
        consecutiveFallbackSuccesses = 0
    }

    fun forceFallback() {
        primaryFailed = true
        consecutiveFallbackSuccesses = 0
    }

    suspend fun clear() {
        primaryFailed = false
        consecutiveFallbackSuccesses = 0
        sessionModelOverride = null
        lastUsedModel = config.strategy.primary.modelId
        conversationHistory.clear()
        workingMemoryStorage.clear()
    }

    suspend fun chat(userMessage: String): String {
        // Save user message first — so navigation mid-processing doesn't erase the question.
        conversationHistory.add("user", userMessage)
        val type = router.classify(userMessage)
        progress("🔍  [${type.name.lowercase()}]  ${config.strategy.strategy.name.lowercase()} 전략")
        val response = runCatching { runWithCritic(userMessage, type) }
            .getOrElse { e ->
                if (e is IllegalStateException) return e.message ?: "설정 오류"
                throw e
            }
        conversationHistory.add("assistant", response)
        trimHistoryIfNeeded()
        updateWorkingMemory(userMessage, response)
        return response
    }

    private suspend fun trimHistoryIfNeeded() {
        if (conversationHistory.getAllEntries().size > ConversationHistory.COMPRESS_THRESHOLD)
            conversationHistory.keepRecent(ConversationHistory.KEEP_RECENT)
    }

    private suspend fun runWithCritic(question: String, type: QuestionType): String {
        val critic = buildCritic()
        var feedback: String? = null
        var lastAnswer = ""
        for (attempt in 1..MAX_CRITIC_RETRIES) {
            val augmented = if (feedback != null) "$question\n\n[이전 답변 개선 필요: $feedback]" else question
            lastAnswer = runPrimary(augmented, type)
            if (attempt == MAX_CRITIC_RETRIES) break
            progress("  🧐  Critic 평가 중...")
            val result = critic.evaluate(question, lastAnswer)
            if (result.passed) return lastAnswer
            feedback = result.feedback
            progress("  🔄 Critic 재시도 ($attempt/${MAX_CRITIC_RETRIES - 1}): ${result.feedback.take(60)}")
        }
        return lastAnswer
    }

    private fun buildCritic(): Critic {
        val criticModel = config.strategy.critic ?: config.strategy.primary
        return runCatching {
            Critic(LLMClientFactory.createExecutor(criticModel, config.apiKeys),
                   LLMClientFactory.createLLModel(criticModel))
        }.getOrElse { e ->
            val fb = config.strategy.fallback
            if (fb != null) {
                runCatching {
                    progress("  ⚠️  Critic 초기화 실패 → fallback (${fb.modelId})")
                    Critic(LLMClientFactory.createExecutor(fb, config.apiKeys),
                           LLMClientFactory.createLLModel(fb))
                }.getOrElse {
                    progress("  ⚠️  Critic fallback도 실패 — 검증 건너뜀")
                    Critic(null, null)
                }
            } else {
                progress("  ⚠️  Critic 초기화 실패 (${criticModel.modelId}): ${e.message?.take(60)} — 검증 건너뜀")
                Critic(null, null)
            }
        }
    }

    private suspend fun runPrimary(question: String, type: QuestionType): String {
        // /model 세션 오버라이드가 최우선
        sessionModelOverride?.let { override ->
            progress("🤖  ${override.provider.name.lowercase()}/${override.modelId}  (세션 고정)")
            return runCatching {
                buildAgent(LLMClientFactory.createExecutor(override, config.apiKeys),
                           LLMClientFactory.createLLModel(override)).run(question)
                    .also { lastUsedModel = override.modelId }
            }.getOrElse { e ->
                val msg = "세션 모델 오류 (${override.provider.name.lowercase()}/${override.modelId}): ${e.message?.take(120)}\n" +
                          "/model reset 으로 오버라이드를 해제하거나 다른 모델을 지정하세요."
                progress("  ❌  $msg")
                msg
            }
        }

        if (primaryFailed) return runFallback(question)

        return when (config.strategy.strategy) {
            RoutingStrategy.AUTO    -> runAutoRouted(question, type)
            RoutingStrategy.PRIMARY -> runWithPrimary(question, type)
            RoutingStrategy.MANUAL  -> {
                // MANUAL without a session override is a misconfiguration — tell the user
                throw IllegalStateException("MANUAL 전략: /model <모델명> 으로 사용할 모델을 먼저 지정하세요. (예: /model gemini, /model claude)")
            }
        }
    }

    private suspend fun runAutoRouted(question: String, type: QuestionType): String {
        val specialist = config.strategy.specialistFor(type)
        progress("🤖  ${specialist.provider.name.lowercase()}/${specialist.modelId}  (${type.name.lowercase()})")
        return try {
            buildAgent(LLMClientFactory.createExecutor(specialist, config.apiKeys),
                       LLMClientFactory.createLLModel(specialist)).run(question)
                .also { lastUsedModel = specialist.modelId }
        } catch (e: Exception) {
            progress("\n  ⚠️  ${specialist.provider.name} 오류 (${specialist.modelId}): ${e.message?.take(80)}")
            primaryFailed = true
            runFallback(question)
        }
    }

    private suspend fun runWithPrimary(question: String, type: QuestionType): String {
        val primary = config.strategy.primary
        progress("🤖  ${primary.provider.name.lowercase()}/${primary.modelId}  (primary)")
        return try {
            buildAgent(LLMClientFactory.createExecutor(primary, config.apiKeys),
                       LLMClientFactory.createLLModel(primary)).run(question)
                .also { lastUsedModel = primary.modelId }
        } catch (e: Exception) {
            progress("\n  ⚠️  ${primary.provider.name} 오류: ${e.message?.take(80)}")
            primaryFailed = true
            runFallback(question)
        }
    }

    private suspend fun runFallback(question: String): String {
        val fb = config.strategy.fallback
            ?: return "Cloud API 오류가 발생했습니다. /config 에서 fallback 모델을 설정하거나 API 키를 확인하세요.\n" +
                      "예) /config set fallback ollama qwen3"
        progress("  🔀  Fallback (${fb.provider.name.lowercase()}/${fb.modelId})\n")
        return runCatching {
            buildAgent(LLMClientFactory.createExecutor(fb, config.apiKeys),
                       LLMClientFactory.createLLModel(fb)).run(question)
                .also {
                    lastUsedModel = fb.modelId
                    consecutiveFallbackSuccesses++
                    if (consecutiveFallbackSuccesses >= AUTO_RECOVERY_THRESHOLD) {
                        primaryFailed = false
                        consecutiveFallbackSuccesses = 0
                        progress("  ✅  Primary 모델 자동 복구 활성화 (다음 질문부터 재시도)")
                    }
                }
        }.getOrElse { e ->
            consecutiveFallbackSuccesses = 0
            "Fallback 모델도 실패했습니다 (${fb.modelId}): ${e.message?.take(100)}"
        }
    }

    private suspend fun updateWorkingMemory(question: String, answer: String) {
        progress("  📝  작업 메모리 업데이트 중...")
        val current = workingMemoryStorage.load()
        val updateModel = config.strategy.critic ?: config.strategy.primary
        val (updateExec, updateLLModel) = runCatching {
            LLMClientFactory.createExecutor(updateModel, config.apiKeys) to
            LLMClientFactory.createLLModel(updateModel)
        }.getOrElse { return }

        val existing = buildString {
            append("Existing confirmedFacts: ${current.confirmedFacts.takeLast(20).joinToString("; ")}\n")
            append("Existing openQuestions: ${current.openQuestions.takeLast(10).joinToString("; ")}\n")
            append("Existing exploredFiles: ${current.exploredFiles.takeLast(30).joinToString(", ")}")
        }
        val extractAgent = AIAgent(
            promptExecutor = updateExec,
            llmModel       = updateLLModel,
            systemPrompt   = """Extract facts from a codebase Q&A. Return ONLY a JSON object:
{"confirmedFacts":["..."],"openQuestions":["..."],"exploredFiles":["..."]}
Each item under 100 chars. Include only new information not in Existing.""",
            maxIterations  = 1,
        )
        val extracted = runCatching {
            extractAgent.run("$existing\n\nQ: $question\nA: ${answer.take(2_000)}")
        }.getOrElse { return }

        fun parseList(field: String): List<String> =
            Regex(""""$field"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
                .find(extracted)?.groupValues?.get(1)
                ?.let { Regex(""""([^"]+)"""").findAll(it).map { m -> m.groupValues[1] }.toList() }
                ?: emptyList()

        workingMemoryStorage.save(
            current.copy(
                confirmedFacts = (current.confirmedFacts + parseList("confirmedFacts")).distinct().takeLast(30),
                openQuestions  = (current.openQuestions  + parseList("openQuestions")).distinct().takeLast(15),
                exploredFiles  = (current.exploredFiles  + parseList("exploredFiles")).distinct().takeLast(50),
            )
        )
    }

    private fun buildAgent(exec: MultiLLMPromptExecutor, model: LLModel): AIAgent<String, String> {
        val memories = memoryStorage.load()
        val memorySection = if (memories.isEmpty()) "" else buildString {
            append("\n## 프로젝트 메모리 (팀 컨벤션 & 설계 결정 — 반드시 참고)\n")
            memories.forEach { append("- $it\n") }
        }
        val workingMem = workingMemoryStorage.load()
        val workingMemSection = if (workingMemoryStorage.isEmpty()) "" else buildString {
            append("\n## 탐색 이력 (이미 확인된 정보)\n")
            if (workingMem.exploredFiles.isNotEmpty())
                append("탐색한 파일: ${workingMem.exploredFiles.joinToString(", ")}\n")
            if (workingMem.confirmedFacts.isNotEmpty()) {
                append("확인된 사실:\n"); workingMem.confirmedFacts.forEach { append("- $it\n") }
            }
            if (workingMem.openQuestions.isNotEmpty()) {
                append("미해결 질문:\n"); workingMem.openQuestions.forEach { append("- $it\n") }
            }
        }
        val history = conversationHistory.getAllEntries().takeLast(ConversationHistory.KEEP_RECENT)
        val agentPrompt = koogPrompt("devpilot-chat") {
            system(buildSystemPrompt(targetDir, memorySection, workingMemSection))
            history.forEach { entry ->
                if (entry.role == "user") user(entry.content) else assistant(entry.content)
            }
        }
        return AIAgent.builder()
            .promptExecutor(exec)
            .llmModel(model)
            .prompt(agentPrompt)
            .toolRegistry(toolRegistry)
            .maxIterations(30)
            .install(EventHandler) { cfg ->
                cfg.onToolCallStarting { ctx ->
                    fun arg(key: String) = (ctx.toolArgs.entries[key] as? JSONPrimitive)?.content ?: ""
                    val (icon, detail) = when (ctx.toolName) {
                        "listFiles"  -> "📁" to arg("path")
                        "readFile"   -> "📄" to arg("path")
                        "codeSearch" -> "🔍" to ("\"${arg("pattern")}\"" +
                            arg("path").takeIf { p -> p.isNotBlank() }?.let { p -> " in $p" }.orEmpty())
                        else         -> "⚙️" to ""
                    }
                    progress(if (detail.isNotBlank()) "  $icon ${ctx.toolName}  →  $detail" else "  $icon ${ctx.toolName}")
                }
                cfg.onToolCallCompleted { ctx ->
                    val text = (ctx.toolResult as? JSONPrimitive)?.content ?: ""
                    val summary = when (ctx.toolName) {
                        "readFile"   -> text.lines().size.let { n -> " (${n}줄)" }
                        "listFiles"  -> text.lines().count { l -> l.isNotBlank() }.let { n -> " (${n}개 항목)" }
                        "codeSearch" -> text.lines().count { l -> l.isNotBlank() }.let { n -> " (${n}개 매칭)" }
                        else         -> ""
                    }
                    progress("  ✔ ${ctx.toolName}$summary")
                }
                cfg.onToolCallFailed { ctx ->
                    progress("  ⚠️  도구 실패: ${ctx.toolName}")
                }
            }
            .build()
    }

    private fun buildSystemPrompt(targetDir: String, memorySection: String, workingMemSection: String = "") = """
# DevPilot — AI 코드베이스 온보딩 어시스턴트

## 역할
신입 개발자가 낯선 코드베이스를 빠르게 파악할 수 있도록 돕는 온보딩 전문가입니다.
코드를 직접 탐색하여 코드 흐름, 설계 의도, 팀 컨벤션을 명확하고 친절하게 설명합니다.
추측하지 말고, 반드시 파일을 직접 읽고 확인한 후 답변합니다.

## 분석 대상 프로젝트
$targetDir

## 사용 가능한 도구
- listFiles(path): 디렉터리 구조를 재귀 탐색합니다. 프로젝트 파악 시 가장 먼저 사용하세요.
- readFile(path): 파일 내용을 읽습니다.
- codeSearch(pattern, path): ripgrep으로 함수명·클래스명·키워드를 검색합니다.

## 답변 절차
1. 질문에서 관련 파일이나 모듈을 추론합니다.
2. listFiles로 프로젝트 구조를 파악합니다.
3. 관련 파일을 readFile로 읽습니다.
4. 필요 시 codeSearch로 함수·클래스 사용처를 추적합니다.
5. 코드 증거를 바탕으로 설명합니다.

## 답변 형식
- 코드의 **어디에** 있는지 (파일 경로) 와 **왜** 그렇게 설계됐는지를 함께 설명합니다.
- 신입 개발자 눈높이에 맞게 기술 용어를 풀어서 설명합니다.
- 프로젝트 메모리에 관련 항목이 있으면 ⚠️ 로 강조하여 반드시 언급합니다.
- 이전 대화 내용을 참고하여 맥락을 이어서 답변합니다.
- 한국어로 응답합니다.
$memorySection$workingMemSection
""".trimIndent()

    companion object {
        private const val MAX_CRITIC_RETRIES = 3
        private const val AUTO_RECOVERY_THRESHOLD = 3
    }
}
