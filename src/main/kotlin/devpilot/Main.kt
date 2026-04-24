package devpilot

import devpilot.agent.DevPilotAgent
import devpilot.command.ClearCommand
import devpilot.command.CommandRegistry
import devpilot.command.CommandResult
import devpilot.command.ConfigCommand
import devpilot.command.HelpCommand
import devpilot.command.MemoryCommand
import devpilot.command.ModeCommand
import devpilot.command.ModelCommand
import devpilot.command.SetupCommand
import devpilot.config.AgentStrategyConfig
import devpilot.config.DevPilotConfig
import devpilot.config.ModelConfig
import devpilot.config.OllamaDiscovery
import devpilot.config.Presets
import devpilot.config.Provider
import devpilot.config.RoutingStrategy
import devpilot.config.SetupWizard
import devpilot.config.StrategyConfigStorage
import devpilot.config.loadDotEnvKeys
import devpilot.memory.ProjectMemoryStorage
import devpilot.web.startWebServer
import java.awt.Desktop
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.URI
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) = runBlocking {
    val targetDirs = args.filter { it.startsWith("-d=") }.map { it.removePrefix("-d=") }
        .ifEmpty { listOf(System.getProperty("user.dir")) }

    val primaryDir = targetDirs.first()
    val configStorage = StrategyConfigStorage(primaryDir)
    val reader = BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8))
    val wizard = SetupWizard(configStorage)
    val webMode = args.any { it == "--web" }
    val globalConfig = when {
        !wizard.needsSetup() -> loadConfig(configStorage)
        OllamaDiscovery.isRunning() -> autoConfigureFromOllama(configStorage)
        hasAnyApiKey() -> autoConfigureFromEnv(configStorage)
        webMode -> {
            println("  ⚠️  설정된 모델이 없습니다.")
            println("  API 키 환경변수를 설정하거나 Ollama를 실행한 뒤 재시작하세요.")
            println("  예) docker-compose up   또는   GOOGLE_API_KEY=xxx ./devpilot.sh --web")
            return@runBlocking
        }
        else -> runCatching { wizard.run(reader) }.getOrElse { e ->
            println("  설정 오류: ${e.message}")
            return@runBlocking
        }
    }

    // 프로젝트별 agent 생성 (중복 이름은 -1, -2 suffix 처리)
    val agents = linkedMapOf<String, DevPilotAgent>()
    for (dir in targetDirs) {
        var name = File(dir).name
        var idx = 1
        while (agents.containsKey(name)) name = "${File(dir).name}-${idx++}"
        val projectConfig = if (dir == primaryDir) globalConfig else
            StrategyConfigStorage(dir).load().copy(apiKeys = globalConfig.apiKeys)
        agents[name] = DevPilotAgent(projectConfig, ProjectMemoryStorage("$dir/DEVPILOT.md"), dir)
    }
    val agent = agents.values.first()  // CLI 모드용

    val memoryStorage = ProjectMemoryStorage("$primaryDir/DEVPILOT.md")
    val registry = CommandRegistry()
    registry.registerAll(
        MemoryCommand(memoryStorage),
        HelpCommand { registry.getAllCommands() },
        ClearCommand(),
        ConfigCommand(configStorage) { newConfig -> agents.values.forEach { it.updateConfig(newConfig) } },
        ModelCommand { override ->
            if (override == null) agent.resetSessionOverride()
            else agent.sessionModelOverride = override
        },
        ModeCommand(agent),
        SetupCommand(wizard, reader) { newConfig -> agents.values.forEach { it.updateConfig(newConfig) } },
    )

    val webPort = args.firstOrNull { it.startsWith("--port=") }?.removePrefix("--port=")?.toIntOrNull() ?: 8080

    println()
    println("""
    ████████████████████████████████████████████████████████████████████████████
    █▌     ██████╗ ███████╗██╗   ██╗██████╗ ██╗██╗      ██████╗ ████████╗     ▐█
    █▌     ██╔══██╗██╔════╝██║   ██║██╔══██╗██║██║     ██╔═══██╗╚══██╔══╝     ▐█
    █▌     ██║  ██║█████╗  ██║   ██║██████╔╝██║██║     ██║   ██║   ██║        ▐█
    █▌     ██║  ██║██╔══╝  ╚██╗ ██╔╝██╔═══╝ ██║██║     ██║   ██║   ██║        ▐█
    █▌     ██████╔╝███████╗ ╚████╔╝ ██║     ██║███████╗╚██████╔╝   ██║        ▐█
    █▌     ╚═════╝ ╚══════╝  ╚═══╝  ╚═╝     ╚═╝╚══════╝ ╚═════╝    ╚═╝        ▐█
    ████████████████████████████████████████████████████████████████████████████
    """)
    println()
    val strategy = globalConfig.strategy
    println("  AI 기반 코드베이스 온보딩 어시스턴트  |  Powered by Koog")
    println("  ─────────────────────────────────────────────────────────────")
    println("  전략: ${strategy.strategy.name.lowercase()}  |  Primary: ${strategy.primary.provider.name.lowercase()}/${strategy.primary.modelId}")
    println("  ─────────────────────────────────────────────────────────────")
    if (agents.size > 1) {
        println("  프로젝트 (${agents.size}개):")
        agents.forEach { (name, a) -> println("    • $name  →  ${a.conversationHistory.getAllEntries().size / 2}턴 이력") }
    } else {
        println("  분석 대상: $primaryDir")
        val memoryCount = memoryStorage.load().size
        if (memoryCount > 0) println("  프로젝트 메모리: ${memoryCount}개 항목 로드됨")
        val historyCount = agent.conversationHistory.getAllEntries().size
        if (historyCount > 0) println("  대화 이력: ${historyCount / 2}개 턴 복원됨 (이전 세션)")
    }
    println()
    println("  낯선 코드베이스, 이런 질문들을 바로 물어보세요:")
    println("  → \"결제 실패 시 재시도 로직이 어디에 있어?\"")
    println("  → \"UserService가 왜 이렇게 설계됐어?\"")
    println("  → \"Order 생성 시 전체 흐름을 설명해줘\"")
    println()
    println("  /config show   설정 확인  |  /model gemini   모델 전환  |  /mode auto   라우팅 전환  |  /help  도움말  |  /exit  종료")
    println("  ─────────────────────────────────────────────────────────────")
    if (!isRipgrepAvailable()) {
        println()
        println("  ⚠️  ripgrep(rg) 미설치 — codeSearch 기능을 사용할 수 없습니다.")
        println("  설치: brew install ripgrep   또는   https://github.com/BurntSushi/ripgrep")
    }
    println()

    if (webMode) {
        val actualPort = startWebServer(agents, configStorage, webPort)
        val url = "http://localhost:$actualPort"
        println("  🌐 Web UI 시작: $url")
        if (agents.size > 1) println("  프로젝트 탭: ${agents.keys.joinToString(" | ")}")
        println("  Ctrl+C 로 종료합니다.")
        println()
        runCatching { if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(url)) }
        Thread.currentThread().join()
        return@runBlocking
    }

    while (true) {
        print("You > ")
        val input = reader.readLine()?.trim() ?: continue
        if (input.isEmpty()) continue
        if (input == "/exit") break

        if (input.startsWith("/")) {
            when (val result = registry.execute(input)) {
                is CommandResult.Success -> {
                    if (result.message.isNotEmpty()) println("\n${result.message}\n")
                }
                is CommandResult.Error -> println("\n오류: ${result.message}\n")
                is CommandResult.ClearSession -> {
                    agent.clear()
                    println("\n세션이 초기화되었습니다.\n")
                }
                is CommandResult.Exit -> break
                null -> println("\n알 수 없는 명령어입니다. /help 를 입력하세요.\n")
            }
            continue
        }

        println()
        val response = agent.chat(input)
        val sep = "─".repeat(60)
        println("\n$sep")
        println("  DevPilot  [${agent.lastUsedModel}]")
        println(sep)
        println(response)
        println("$sep\n")
    }

    println("안녕히 가세요!")
}

private fun isRipgrepAvailable(): Boolean =
    runCatching { ProcessBuilder("rg", "--version").start().waitFor() == 0 }.getOrDefault(false)

private fun hasAnyApiKey(): Boolean {
    val dotEnv = loadDotEnvKeys()
    return listOf("GOOGLE_API_KEY", "ANTHROPIC_API_KEY", "OPENAI_API_KEY").any { key ->
        !System.getenv(key).isNullOrBlank() || !dotEnv[key].isNullOrBlank()
    }
}

private fun autoConfigureFromOllama(storage: StrategyConfigStorage): DevPilotConfig {
    val models = OllamaDiscovery.listModels()
    val primary = models.firstOrNull() ?: "llama3.2"
    println("  🤖 Ollama 자동 감지: $primary" + if (models.size > 1) " (외 ${models.size - 1}개)" else "")
    val config = DevPilotConfig(
        strategy = AgentStrategyConfig(
            strategy = RoutingStrategy.PRIMARY,
            primary  = ModelConfig(Provider.OLLAMA, primary),
            critic   = ModelConfig(Provider.OLLAMA, primary),
        )
    )
    storage.save(config)
    println("  ✅ 자동 설정 완료 (/setup 으로 변경 가능)")
    return config
}

private fun autoConfigureFromEnv(storage: StrategyConfigStorage): DevPilotConfig {
    val dotEnv = loadDotEnvKeys()
    fun key(env: String) = System.getenv(env)?.takeIf { it.isNotBlank() } ?: dotEnv[env]
    val googleKey    = key("GOOGLE_API_KEY")
    val anthropicKey = key("ANTHROPIC_API_KEY")
    val openaiKey    = key("OPENAI_API_KEY")

    val (strategy, providerName) = when {
        googleKey    != null -> Presets.CLOUD_GOOGLE to "Google Gemini"
        anthropicKey != null -> Presets.CLOUD_CLAUDE to "Anthropic Claude"
        else                 -> Presets.CLOUD_GOOGLE.copy(
            primary = ModelConfig(Provider.OPENAI, "gpt-4o")
        ) to "OpenAI"
    }
    val apiKeys = buildMap {
        googleKey?.let    { put("google",    it) }
        anthropicKey?.let { put("anthropic", it) }
        openaiKey?.let    { put("openai",    it) }
    }
    println("  🔑 API 키 자동 감지: $providerName")
    val config = DevPilotConfig(apiKeys = apiKeys, strategy = strategy)
    storage.save(config)
    println("  ✅ 자동 설정 완료 (/setup 으로 변경 가능)")
    return config
}

private fun loadConfig(storage: StrategyConfigStorage): DevPilotConfig {
    var config = storage.load()
    val dotEnv = loadDotEnvKeys()
    val envKeys = mapOf(
        "google"    to (System.getenv("GOOGLE_API_KEY") ?: dotEnv["GOOGLE_API_KEY"]),
        "anthropic" to (System.getenv("ANTHROPIC_API_KEY") ?: dotEnv["ANTHROPIC_API_KEY"]),
        "openai"    to (System.getenv("OPENAI_API_KEY") ?: dotEnv["OPENAI_API_KEY"]),
    ).filterValues { it != null }.mapValues { it.value!! }

    val merged = config.apiKeys + envKeys.filterKeys { it !in config.apiKeys }
    if (merged != config.apiKeys) config = config.copy(apiKeys = merged)
    return config
}
