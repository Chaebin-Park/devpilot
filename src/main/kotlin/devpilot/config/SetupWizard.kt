package devpilot.config

import devpilot.agent.QuestionType
import java.io.BufferedReader
import java.io.File

class SetupWizard(private val storage: StrategyConfigStorage) {

    fun needsSetup(): Boolean =
        !File(System.getProperty("user.home"), ".devpilot/config.json").exists()

    fun run(reader: BufferedReader): DevPilotConfig {
        println()
        println("  ╔══════════════════════════════════════╗")
        println("  ║      DevPilot 초기 설정 마법사        ║")
        println("  ╚══════════════════════════════════════╝")
        println()
        print("  사용 가능한 AI 모델을 감지하는 중...")

        val dotEnv = loadDotEnvKeys()
        fun apiKey(env: String) = System.getenv(env)?.takeIf { it.isNotBlank() } ?: dotEnv[env]
        val d = DetectionResult(
            googleKey    = apiKey("GOOGLE_API_KEY"),
            anthropicKey = apiKey("ANTHROPIC_API_KEY"),
            openaiKey    = apiKey("OPENAI_API_KEY"),
            ollamaRunning = OllamaDiscovery.isRunning(),
            ollamaModels  = OllamaDiscovery.listModels(),
        )
        println(" 완료")
        println()

        showDetection(d)

        if (!d.hasCloud() && !d.ollamaRunning) {
            showNoModelError()
            throw IllegalStateException(
                "설정 가능한 모델이 없습니다. API 키 또는 Ollama를 설정한 뒤 다시 실행하세요."
            )
        }

        // ── Step 1: Primary (필수) ─────────────────────────────────────────
        println("  ━━━━━ [1단계] Primary 모델 선택 (필수) ━━━━━━━━━━━━━━━━━━")
        println("  DevPilot이 기본으로 사용할 AI 모델입니다.")
        println()
        val primary = pickProvider(d, reader, required = true)!!

        // ── Step 2: Fallback (선택) ────────────────────────────────────────
        println()
        println("  ━━━━━ [2단계] Fallback 모델 선택 (선택사항) ━━━━━━━━━━━━━")
        println("  Primary 실패 시 대신 사용할 모델입니다. Enter로 건너뜁니다.")
        println()
        val fallback = pickProvider(d, reader, required = false, exclude = primary.provider)

        val config = buildConfig(primary, fallback, d)
        storage.save(config)

        println()
        println("  ✅ 설정이 저장되었습니다.")
        println("     ${File(System.getProperty("user.home"), ".devpilot/config.json").absolutePath}")
        println()
        return config
    }

    // ─── Provider picker ─────────────────────────────────────────────────────

    private fun pickProvider(
        d: DetectionResult,
        reader: BufferedReader,
        required: Boolean,
        exclude: Provider? = null,
    ): ModelSelection? {
        val opts = mutableListOf<ModelOption>()
        if (d.googleKey != null && exclude != Provider.GOOGLE)
            opts += ModelOption(Provider.GOOGLE, null, "Google Gemini   ✅  (Gemini 2.5 Flash — 분석 시 Gemini 2.5 Pro 자동 전환)")
        if (d.anthropicKey != null && exclude != Provider.ANTHROPIC)
            opts += ModelOption(Provider.ANTHROPIC, null, "Anthropic Claude ✅  (Claude Sonnet — 추론 시 Claude Opus 자동 전환)")
        if (d.ollamaRunning && d.ollamaModels.isNotEmpty() && exclude != Provider.OLLAMA)
            opts += ModelOption(Provider.OLLAMA, null, "Ollama 로컬      ✅  (모델 ${d.ollamaModels.size}개 설치됨, 무료·오프라인)")

        if (opts.isEmpty()) {
            println("  ℹ️  선택 가능한 모델이 없습니다.")
            return null
        }

        opts.forEachIndexed { i, o -> println("  [${i + 1}] ${o.label}") }
        if (!required) println("  [Enter] 없음")
        println()

        val choice = if (required) readChoiceRequired(reader, opts.size)
                     else readChoiceOptional(reader, opts.size) ?: return null

        val opt = opts[choice - 1]
        return if (opt.provider == Provider.OLLAMA) {
            val model = pickOllamaModel(d.ollamaModels, reader, required = required) ?: return null
            ModelSelection(Provider.OLLAMA, model)
        } else {
            ModelSelection(opt.provider, null)
        }
    }

    private fun pickOllamaModel(models: List<String>, reader: BufferedReader, required: Boolean): String? {
        println()
        println("  사용할 Ollama 모델을 선택하세요:")
        models.forEachIndexed { i, m -> println("  [${i + 1}] $m") }
        if (!required) println("  [Enter] 없음")
        println()
        val choice = if (required) readChoiceRequired(reader, models.size)
                     else readChoiceOptional(reader, models.size) ?: return null
        return models[choice - 1]
    }

    // ─── Config builder ───────────────────────────────────────────────────────

    private fun buildConfig(primary: ModelSelection, fallback: ModelSelection?, d: DetectionResult): DevPilotConfig {
        val fallbackModel = fallback?.let { sel ->
            when (sel.provider) {
                Provider.OLLAMA     -> ModelConfig(Provider.OLLAMA, sel.modelId!!)
                Provider.GOOGLE     -> ModelConfig(Provider.GOOGLE, "gemini-2.5-flash")
                Provider.ANTHROPIC  -> ModelConfig(Provider.ANTHROPIC, "claude-haiku-4-5")
                Provider.OPENAI     -> ModelConfig(Provider.OPENAI, "gpt-4o-mini")
            }
        }

        val strategy = when (primary.provider) {
            Provider.GOOGLE -> Presets.CLOUD_GOOGLE.copy(fallback = fallbackModel)
            Provider.ANTHROPIC -> Presets.CLOUD_CLAUDE.copy(fallback = fallbackModel)
            Provider.OLLAMA -> {
                val model = primary.modelId!!
                AgentStrategyConfig(
                    strategy = RoutingStrategy.PRIMARY,
                    primary  = ModelConfig(Provider.OLLAMA, model),
                    critic   = ModelConfig(Provider.OLLAMA, model),
                    fallback = fallbackModel,
                )
            }
            Provider.OPENAI -> Presets.CLOUD_GOOGLE.copy(
                primary = ModelConfig(Provider.OPENAI, "gpt-4o"),
                fallback = fallbackModel,
            )
        }

        val apiKeys = buildMap {
            d.googleKey?.let    { put("google",    it) }
            d.anthropicKey?.let { put("anthropic", it) }
            d.openaiKey?.let    { put("openai",    it) }
        }
        return DevPilotConfig(apiKeys = apiKeys, strategy = strategy)
    }

    // ─── Display helpers ─────────────────────────────────────────────────────

    private fun showDetection(d: DetectionResult) {
        fun icon(ok: Boolean) = if (ok) "✅" else "❌"
        println("  ┌─ 감지 결과 ─────────────────────────────────────────────")
        println("  │  Google Gemini    ${icon(d.googleKey != null)} ${if (d.googleKey != null) "API 키 감지됨" else "없음 (GOOGLE_API_KEY 미설정)"}")
        println("  │  Anthropic Claude ${icon(d.anthropicKey != null)} ${if (d.anthropicKey != null) "API 키 감지됨" else "없음 (ANTHROPIC_API_KEY 미설정)"}")
        println("  │  OpenAI           ${icon(d.openaiKey != null)} ${if (d.openaiKey != null) "API 키 감지됨" else "없음 (OPENAI_API_KEY 미설정)"}")
        if (d.ollamaRunning) {
            println("  │  Ollama 로컬      ✅ 실행 중 — 모델 ${d.ollamaModels.size}개")
            d.ollamaModels.forEach { println("  │    • $it") }
        } else {
            println("  │  Ollama 로컬      ❌ 실행 안 됨")
        }
        println("  └─────────────────────────────────────────────────────────")
        println()
    }

    private fun showNoModelError() {
        println()
        println("  ⚠️  사용 가능한 AI 모델이 없습니다.")
        println()
        println("  [ 방법 1 ] 환경변수 또는 .env 파일에 API 키를 추가하세요:")
        println("    GOOGLE_API_KEY=your-key      # https://aistudio.google.com/apikey")
        println("    ANTHROPIC_API_KEY=your-key   # https://console.anthropic.com")
        println()
        println("  [ 방법 2 ] Ollama 설치 후 모델을 받으세요:")
        println("    https://ollama.com  →  ollama pull llama3.2")
        println()
        println("  설정 후 다시 실행하거나 /setup 명령어로 재설정하세요.")
        println()
    }

    // ─── Input helpers ────────────────────────────────────────────────────────

    private fun readChoiceRequired(reader: BufferedReader, max: Int): Int {
        while (true) {
            print("  선택 [1-$max]: ")
            val raw = reader.readLine()?.trim() ?: continue
            val n = raw.toIntOrNull()
            if (n != null && n in 1..max) return n
            println("  1~$max 사이의 숫자를 입력하세요. (필수 항목)")
        }
    }

    private fun readChoiceOptional(reader: BufferedReader, max: Int): Int? {
        print("  선택 [1-$max] (Enter 건너뜀): ")
        val raw = reader.readLine()?.trim() ?: return null
        if (raw.isBlank()) return null
        val n = raw.toIntOrNull()
        if (n != null && n in 1..max) return n
        println("  1~$max 사이의 숫자 또는 Enter를 입력하세요.")
        return readChoiceOptional(reader, max)
    }
}

// ─── Internal models ─────────────────────────────────────────────────────────

private data class ModelOption(val provider: Provider, val modelId: String?, val label: String)
private data class ModelSelection(val provider: Provider, val modelId: String?)

private data class DetectionResult(
    val googleKey: String?,
    val anthropicKey: String?,
    val openaiKey: String?,
    val ollamaRunning: Boolean,
    val ollamaModels: List<String>,
) {
    fun hasCloud() = googleKey != null || anthropicKey != null || openaiKey != null
}

fun loadDotEnvKeys(): Map<String, String> = buildMap {
    listOf(
        File(System.getProperty("user.home"), ".devpilot/.env"),
        File(System.getProperty("user.dir"), ".env"),
    ).forEach { file ->
        if (!file.exists()) return@forEach
        file.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") && '=' in it }
            .forEach { line ->
                val eq = line.indexOf('=')
                val key   = line.substring(0, eq).trim()
                val value = line.substring(eq + 1).trim().removeSurrounding("\"").removeSurrounding("'")
                if (key.isNotBlank() && value.isNotBlank()) put(key, value)
            }
    }
}
