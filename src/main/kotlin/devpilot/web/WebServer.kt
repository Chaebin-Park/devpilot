package devpilot.web

import devpilot.agent.DevPilotAgent
import devpilot.config.ModelConfig
import devpilot.config.Presets
import devpilot.config.Provider
import devpilot.config.RoutingStrategy
import devpilot.config.StrategyConfigStorage
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

fun startWebServer(agents: LinkedHashMap<String, DevPilotAgent>, configStorage: StrategyConfigStorage, port: Int = 8080): Int {
    val actualPort = findFreePort(port)
    embeddedServer(CIO, port = actualPort) {
        install(WebSockets)
        routing {
            get("/") {
                val currentProjects = synchronized(agents) { agents.keys.toList() }
                val p = call.request.queryParameters["p"]
                val agent = if (p != null) agents[p] else null
                if (agent != null) {
                    call.respondText(chatHtml(p!!, currentProjects), ContentType.Text.Html)
                } else {
                    call.respondRedirect("/?p=${currentProjects.first()}")
                }
            }
            get("/settings") { call.respondText(settingsHtml(), ContentType.Text.Html) }
            webSocket("/ws") {
                val currentProjects = synchronized(agents) { agents.keys.toList() }
                val p = call.request.queryParameters["p"] ?: currentProjects.first()
                val agent = agents[p] ?: agents.values.first()
                handleSession(agent)
            }

            // ── REST API ──────────────────────────────────────────────
            get("/api/config") {
                val p = call.request.queryParameters["p"]
                val agent = (if (p != null) agents[p] else null) ?: agents.values.first()
                val cfg = configStorage.load()
                val s = cfg.strategy
                call.respondText(buildJsonObject {
                    put("strategy", s.strategy.name.lowercase())
                    put("primary", buildJsonObject {
                        put("provider", s.primary.provider.name.lowercase())
                        put("modelId",  s.primary.modelId)
                    })
                    put("specialists", buildJsonObject {
                        s.specialists.forEach { (type, mc) ->
                            put(type, buildJsonObject {
                                put("provider", mc.provider.name.lowercase())
                                put("modelId",  mc.modelId)
                            })
                        }
                    })
                    put("apiKeys", buildJsonObject {
                        cfg.apiKeys.forEach { (k, v) -> put(k, v.take(8) + "…") }
                    })
                    put("sessionModel", agent.sessionModelOverride?.let {
                        buildJsonObject { put("provider", it.provider.name.lowercase()); put("modelId", it.modelId) }
                    } ?: JsonNull)
                    put("projects", buildJsonArray { agents.keys.forEach { add(it) } })
                }.toString(), ContentType.Application.Json)
            }

            post("/api/config/apikey") {
                val body = call.receiveText().parseJson()
                val provider = body["provider"]?.jsonPrimitive?.content?.lowercase()
                    ?: return@post call.respondError("provider 필드가 필요합니다")
                val key = body["key"]?.jsonPrimitive?.content
                    ?: return@post call.respondError("key 필드가 필요합니다")
                val cfg = configStorage.load()
                val updated = cfg.copy(apiKeys = cfg.apiKeys + (provider to key))
                configStorage.save(updated)
                agents.values.forEach { it.updateConfig(updated) }
                call.respondOk("$provider API 키가 저장되었습니다.")
            }

            post("/api/config/strategy") {
                val body = call.receiveText().parseJson()
                val strategyStr = body["strategy"]?.jsonPrimitive?.content?.uppercase()
                    ?: return@post call.respondError("strategy 필드가 필요합니다")
                val strategy = runCatching { RoutingStrategy.valueOf(strategyStr) }
                    .getOrElse { return@post call.respondError("유효하지 않은 전략: $strategyStr") }
                val cfg = configStorage.load()
                val updated = cfg.copy(strategy = cfg.strategy.copy(strategy = strategy))
                configStorage.save(updated)
                agents.values.forEach { it.updateConfig(updated) }
                call.respondOk("전략이 ${strategy.name.lowercase()}(으)로 변경되었습니다.")
            }

            post("/api/config/primary") {
                val body = call.receiveText().parseJson()
                val providerStr = body["provider"]?.jsonPrimitive?.content?.uppercase()
                    ?: return@post call.respondError("provider 필드가 필요합니다")
                val modelId = body["modelId"]?.jsonPrimitive?.content
                    ?: return@post call.respondError("modelId 필드가 필요합니다")
                val provider = runCatching { Provider.valueOf(providerStr) }
                    .getOrElse { return@post call.respondError("유효하지 않은 provider: $providerStr") }
                val mc = ModelConfig(provider, modelId)
                val cfg = configStorage.load()
                val updated = cfg.copy(strategy = cfg.strategy.copy(primary = mc))
                configStorage.save(updated)
                agents.values.forEach { it.updateConfig(updated) }
                call.respondOk("Primary 모델이 ${providerStr.lowercase()}/$modelId(으)로 변경되었습니다.")
            }

            post("/api/config/preset") {
                val body = call.receiveText().parseJson()
                val name = body["name"]?.jsonPrimitive?.content
                    ?: return@post call.respondError("name 필드가 필요합니다")
                val preset = Presets.fromName(name)
                    ?: return@post call.respondError("알 수 없는 프리셋: $name")
                val cfg = configStorage.load()
                val updated = cfg.copy(strategy = preset)
                configStorage.save(updated)
                agents.values.forEach { it.updateConfig(updated) }
                call.respondOk("프리셋 '$name'이(가) 적용되었습니다.")
            }

            // ── 디렉터리 브라우저 ──────────────────────────────────────
            get("/api/browse") {
                val path = call.request.queryParameters["path"]
                    ?.takeIf { it.isNotBlank() }
                    ?: System.getProperty("user.home")
                val dir = java.io.File(path)
                if (!dir.exists() || !dir.isDirectory)
                    return@get call.respondError("유효하지 않은 경로: $path")
                val dirs = dir.listFiles()
                    ?.filter { it.isDirectory && !it.name.startsWith(".") }
                    ?.sortedBy { it.name.lowercase() }
                    ?.map { it.name }
                    ?: emptyList()
                call.respondText(buildJsonObject {
                    put("path", dir.absolutePath)
                    put("parent", dir.parent ?: "")
                    put("dirs", buildJsonArray { dirs.forEach { add(it) } })
                }.toString(), ContentType.Application.Json)
            }

            // ── 프로젝트 관리 ─────────────────────────────────────────
            post("/api/projects") {
                val body = call.receiveText().parseJson()
                val dir = body["dir"]?.jsonPrimitive?.content?.trim()
                    ?: return@post call.respondError("dir 필드가 필요합니다")
                val file = java.io.File(dir)
                if (!file.exists() || !file.isDirectory)
                    return@post call.respondError("존재하지 않는 디렉터리입니다: $dir")
                var name = file.name
                var idx = 1
                synchronized(agents) { while (agents.containsKey(name)) name = "${file.name}-${idx++}" }
                val cfg = configStorage.load()
                val newAgent = DevPilotAgent(cfg, devpilot.memory.ProjectMemoryStorage("$dir/DEVPILOT.md"), dir)
                synchronized(agents) { agents[name] = newAgent }
                call.respondText(buildJsonObject {
                    put("ok", true); put("name", name); put("message", "프로젝트 '$name' 추가됨")
                }.toString(), ContentType.Application.Json)
            }

            delete("/api/projects") {
                val name = call.request.queryParameters["name"]
                    ?: return@delete call.respondError("name 파라미터가 필요합니다")
                val removed = synchronized(agents) { agents.remove(name) }
                if (removed == null) return@delete call.respondError("프로젝트가 없습니다: $name")
                val next = synchronized(agents) { agents.keys.firstOrNull() } ?: ""
                call.respondText(buildJsonObject {
                    put("ok", true); put("next", next); put("message", "프로젝트 '$name' 제거됨")
                }.toString(), ContentType.Application.Json)
            }

            post("/api/model") {
                val body = call.receiveText().parseJson()
                val p = body["project"]?.jsonPrimitive?.content
                val targetAgents = if (p != null && agents.containsKey(p)) listOf(agents[p]!!) else agents.values.toList()
                val reset = body["reset"]?.jsonPrimitive?.booleanOrNull ?: false
                if (reset) {
                    targetAgents.forEach { it.resetSessionOverride() }
                    call.respondOk("세션 모델 오버라이드가 해제되었습니다.")
                } else {
                    val providerStr = body["provider"]?.jsonPrimitive?.content?.uppercase()
                        ?: return@post call.respondError("provider 필드가 필요합니다")
                    val modelId = body["modelId"]?.jsonPrimitive?.content
                        ?: return@post call.respondError("modelId 필드가 필요합니다")
                    val provider = runCatching { Provider.valueOf(providerStr) }
                        .getOrElse { return@post call.respondError("유효하지 않은 provider: $providerStr") }
                    targetAgents.forEach { it.sessionModelOverride = ModelConfig(provider, modelId) }
                    call.respondOk("세션 모델: ${providerStr.lowercase()}/$modelId")
                }
            }
        }
    }.start(wait = false)
    return actualPort
}

private fun String.parseJson() = runCatching { Json.parseToJsonElement(this).jsonObject }.getOrElse { JsonObject(emptyMap()) }

private suspend fun ApplicationCall.respondOk(message: String) =
    respondText(buildJsonObject { put("ok", true); put("message", message) }.toString(), ContentType.Application.Json)

private suspend fun ApplicationCall.respondError(message: String) =
    respondText(buildJsonObject { put("ok", false); put("message", message) }.toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)

private fun findFreePort(startPort: Int): Int {
    for (port in startPort until startPort + 10) {
        try {
            java.net.ServerSocket(port).use { return port }
        } catch (_: java.net.BindException) { }
    }
    error("포트 $startPort~${startPort + 9} 범위에서 사용 가능한 포트를 찾을 수 없습니다.")
}

private suspend fun DefaultWebSocketServerSession.handleSession(agent: DevPilotAgent) {
    // Send existing conversation history so the browser can restore it on reconnect.
    val history = agent.conversationHistory.getAllEntries()
    if (history.isNotEmpty()) {
        val historyJson = buildJsonObject {
            put("type", "history")
            put("entries", buildJsonArray {
                history.forEach { entry ->
                    add(buildJsonObject {
                        put("role", entry.role)
                        put("content", entry.content)
                    })
                }
            })
            put("model", agent.lastUsedModel)
        }
        send(Frame.Text(Json.encodeToString(historyJson)))
    }

    // Route agent progress to this WebSocket.
    // Use outgoing.trySend (non-blocking, thread-safe) so EventHandler callbacks
    // running inside the agent coroutine don't get dropped.
    agent.progressSink = { msg ->
        outgoing.trySend(Frame.Text(Json.encodeToString(buildJsonObject {
            put("type", "progress")
            put("text", msg)
        })))
    }

    var currentJob: Job? = null

    fun sendFrame(obj: JsonObject) {
        outgoing.trySend(Frame.Text(Json.encodeToString(obj)))
    }

    try {
        for (frame in incoming) {
            if (frame !is Frame.Text) continue
            val text = frame.readText()
            val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: continue
            val msgType = json["type"]?.jsonPrimitive?.content
            if (msgType != "message" && msgType != "cancel") continue
            val userMsg = json["text"]?.jsonPrimitive?.content?.trim() ?: ""

            if (msgType == "cancel") {
                currentJob?.cancel()
                currentJob = null
                sendFrame(buildJsonObject { put("type", "cancelled"); put("text", "Agent가 중단되었습니다.") })
                continue
            }
            if (userMsg.isEmpty()) continue

            when (userMsg) {
                "/clear" -> {
                    agent.clear()
                    sendFrame(buildJsonObject { put("type", "info"); put("text", "세션이 초기화되었습니다.") })
                }
                else -> {
                    currentJob = launch {
                        runCatching {
                            val response = agent.chat(userMsg)
                            sendFrame(buildJsonObject {
                                put("type", "response")
                                put("text", response)
                                put("model", agent.lastUsedModel)
                            })
                        }.onFailure { e ->
                            if (e is kotlinx.coroutines.CancellationException) return@onFailure
                            sendFrame(buildJsonObject {
                                put("type", "error")
                                put("text", e.message ?: "알 수 없는 오류")
                            })
                        }
                        currentJob = null
                    }
                }
            }
        }
    } catch (_: ClosedSendChannelException) {
        // client disconnected
    } finally {
        currentJob?.cancel()
        agent.progressSink = ::println
    }
}

private fun chatHtml(currentProject: String, allProjects: List<String>) = """
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>DevPilot</title>
<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/github-dark.min.css">
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body { background: #0d1117; color: #e6edf3; font-family: 'Segoe UI', system-ui, sans-serif; height: 100vh; display: flex; flex-direction: column; }
  header { padding: 14px 20px; border-bottom: 1px solid #21262d; display: flex; align-items: center; gap: 12px; background: #161b22; flex-shrink: 0; }
  header h1 { font-size: 16px; font-weight: 600; letter-spacing: .5px; }
  #model-badge { font-size: 11px; padding: 2px 8px; background: #1f6feb22; border: 1px solid #1f6feb55; border-radius: 20px; color: #58a6ff; }
  #messages { flex: 1; overflow-y: auto; padding: 20px; display: flex; flex-direction: column; gap: 16px; }
  .msg { display: flex; flex-direction: column; gap: 6px; max-width: 860px; width: 100%; }
  .msg.user { align-self: flex-end; align-items: flex-end; max-width: 640px; }
  .msg.bot  { align-self: flex-start; }
  .bubble { padding: 12px 16px; border-radius: 12px; line-height: 1.7; font-size: 14px; word-break: break-word; }
  .msg.user .bubble { background: #1f6feb; color: #fff; border-bottom-right-radius: 3px; white-space: pre-wrap; }
  .msg.bot  .bubble { background: #161b22; border: 1px solid #30363d; border-bottom-left-radius: 3px; }
  .msg.bot  .bubble.progress { color: #6e7681; font-size: 12px; font-family: 'JetBrains Mono', monospace; border-color: #21262d; background: #0d1117; padding: 6px 12px; white-space: pre-wrap; }
  .msg.bot  .bubble.progress.active::after { content: '▌'; animation: cur-blink 1s step-end infinite; color: #388bfd; margin-left: 2px; }
  @keyframes cur-blink { 0%,100% { opacity:1; } 50% { opacity:0; } }
  .thinking { display: flex; align-items: center; gap: 5px; padding: 10px 14px; }
  .thinking span { width: 7px; height: 7px; background: #388bfd; border-radius: 50%; animation: tdot 1.3s ease-in-out infinite; opacity: 0.5; }
  .thinking span:nth-child(2) { animation-delay: .2s; }
  .thinking span:nth-child(3) { animation-delay: .4s; }
  @keyframes tdot { 0%,80%,100% { transform:translateY(0); opacity:.4; } 40% { transform:translateY(-7px); opacity:1; } }
  .msg.bot  .bubble.error  { border-color: #da3633; color: #ff7b72; white-space: pre-wrap; }
  .msg.bot  .bubble.info   { border-color: #1f6feb55; color: #79c0ff; white-space: pre-wrap; }
  .meta { font-size: 11px; color: #6e7681; padding: 0 4px; }
  .model-tag { color: #3fb950; font-family: monospace; }

  /* ── Markdown 렌더링 ── */
  .md h1, .md h2, .md h3, .md h4 { color: #e6edf3; font-weight: 600; margin: 1em 0 .4em; line-height: 1.3; }
  .md h1 { font-size: 1.35em; border-bottom: 1px solid #30363d; padding-bottom: .3em; }
  .md h2 { font-size: 1.15em; border-bottom: 1px solid #21262d; padding-bottom: .2em; }
  .md h3 { font-size: 1em; }
  .md h4 { font-size: .95em; color: #8b949e; }
  .md p  { margin: .5em 0; }
  .md ul, .md ol { margin: .5em 0 .5em 1.4em; }
  .md li { margin: .2em 0; }
  .md li > p { margin: 0; }
  .md code { font-family: 'JetBrains Mono', 'Fira Code', monospace; font-size: .85em; background: #21262d; color: #e6edf3; padding: .15em .4em; border-radius: 4px; }
  .md pre { margin: .75em 0; border-radius: 8px; overflow: hidden; border: 1px solid #30363d; }
  .md pre code { background: transparent; padding: 0; border-radius: 0; font-size: .83em; }
  .md pre .hljs { padding: 14px 16px; border-radius: 8px; }
  .md blockquote { border-left: 3px solid #388bfd; margin: .6em 0; padding: .3em 0 .3em 1em; color: #8b949e; background: #161b22; border-radius: 0 6px 6px 0; }
  .md blockquote p { margin: 0; }
  .md table { border-collapse: collapse; width: 100%; margin: .75em 0; font-size: .9em; }
  .md th { background: #21262d; color: #e6edf3; font-weight: 600; padding: 6px 12px; border: 1px solid #30363d; text-align: left; }
  .md td { padding: 6px 12px; border: 1px solid #30363d; }
  .md tr:nth-child(even) { background: #161b22; }
  .md a { color: #58a6ff; text-decoration: none; }
  .md a:hover { text-decoration: underline; }
  .md hr { border: none; border-top: 1px solid #30363d; margin: 1em 0; }
  .md strong { color: #e6edf3; }
  .md em { color: #c9d1d9; }
  /* 파일 경로 강조 */
  .md code:not(pre code) { color: #ffa657; }

  #input-row { padding: 14px 20px; border-top: 1px solid #21262d; display: flex; gap: 10px; background: #161b22; flex-shrink: 0; }
  #input { flex: 1; background: #0d1117; border: 1px solid #30363d; border-radius: 8px; color: #e6edf3; font-size: 14px; padding: 10px 14px; resize: none; outline: none; min-height: 42px; max-height: 160px; line-height: 1.5; font-family: inherit; }
  #input:focus { border-color: #1f6feb; }
  #send { background: #1f6feb; color: #fff; border: none; border-radius: 8px; padding: 10px 22px; font-size: 14px; font-weight: 500; cursor: pointer; white-space: nowrap; transition: background .15s; }
  #send:hover { background: #388bfd; }
  #send:disabled { background: #21262d; color: #6e7681; cursor: not-allowed; }
  #cancel-btn { background: #da3633; color: #fff; border: none; border-radius: 8px; padding: 10px 16px; font-size: 14px; font-weight: 500; cursor: pointer; white-space: nowrap; transition: background .15s; display: none; }
  #cancel-btn:hover { background: #f85149; }
  /* 경고 모달 */
  .modal-backdrop { display: none; position: fixed; inset: 0; background: rgba(0,0,0,.6); z-index: 200; align-items: center; justify-content: center; }
  .modal-backdrop.show { display: flex; }
  .modal { background: #161b22; border: 1px solid #30363d; border-radius: 12px; padding: 28px 32px; max-width: 400px; width: 90%; }
  .modal h2 { font-size: 16px; font-weight: 600; margin-bottom: 10px; }
  .modal p { font-size: 13px; color: #8b949e; margin-bottom: 24px; line-height: 1.6; }
  .modal-actions { display: flex; gap: 10px; justify-content: flex-end; }
  .btn { padding: 8px 18px; border-radius: 6px; font-size: 13px; font-weight: 500; cursor: pointer; border: none; transition: background .15s; }
  .btn-secondary { background: #21262d; color: #e6edf3; border: 1px solid #30363d; }
  .btn-secondary:hover { background: #30363d; }
  .btn-primary { background: #1f6feb; color: #fff; }
  .btn-primary:hover { background: #388bfd; }
  .btn-danger { background: #da3633; color: #fff; }
  .btn-danger:hover { background: #f85149; }
  ::-webkit-scrollbar { width: 5px; } ::-webkit-scrollbar-track { background: transparent; } ::-webkit-scrollbar-thumb { background: #30363d; border-radius: 3px; }
</style>
</head>
<body>
<header>
  <h1>⚡ DevPilot</h1>
  ${if (allProjects.size > 1) allProjects.joinToString("") { name ->
      val active = if (name == currentProject) "color:#e6edf3;border-bottom:2px solid #1f6feb;" else "color:#8b949e;border-bottom:2px solid transparent;"
      """<span style="display:flex;align-items:center"><a href="/?p=${name}" style="font-size:13px;padding:4px 10px;text-decoration:none;$active">$name</a><button onclick="removeProject('${name}')" style="background:none;border:none;color:#6e7681;font-size:12px;cursor:pointer;padding:0 4px;line-height:1" title="프로젝트 제거">×</button></span>"""
  } else """<span style="font-size:12px;color:#8b949e;padding:2px 8px;background:#1f6feb22;border:1px solid #1f6feb55;border-radius:20px;">$currentProject</span>"""}
  <button onclick="openAddProject()" title="프로젝트 추가" style="background:none;border:1px solid #30363d;color:#8b949e;padding:2px 9px;border-radius:6px;cursor:pointer;font-size:14px;line-height:1.2">+</button>
  <span id="model-badge">연결 중...</span>
  <span style="flex:1"></span>
  <button onclick="clearSession()" style="background:none;border:1px solid #30363d;color:#8b949e;padding:4px 12px;border-radius:6px;cursor:pointer;font-size:12px;">/clear</button>
  <a href="/settings" target="_blank" style="background:none;border:1px solid #30363d;color:#8b949e;padding:4px 12px;border-radius:6px;cursor:pointer;font-size:12px;text-decoration:none;margin-left:6px;">⚙ 설정</a>
</header>
<div id="messages"></div>
<div id="input-row">
  <textarea id="input" placeholder="코드베이스에 대해 질문하세요... (Shift+Enter 줄바꿈, Enter 전송)" rows="1"></textarea>
  <button id="cancel-btn" onclick="cancelAgent()">■ 중단</button>
  <button id="send" onclick="sendMessage()">전송</button>
</div>

<!-- 프로젝트 추가 모달 -->
<div class="modal-backdrop" id="add-project-modal">
  <div class="modal" style="max-width:520px">
    <h2>프로젝트 폴더 선택</h2>
    <div id="browser-path" style="font-size:12px;font-family:monospace;color:#58a6ff;margin-bottom:10px;word-break:break-all"></div>
    <div id="browser-list" style="background:#0d1117;border:1px solid #30363d;border-radius:6px;max-height:280px;overflow-y:auto;margin-bottom:12px"></div>
    <div id="add-project-error" style="color:#ff7b72;font-size:12px;margin-bottom:10px;display:none"></div>
    <div class="modal-actions">
      <button class="btn btn-secondary" onclick="closeAddProject()">취소</button>
      <button class="btn btn-primary" onclick="confirmAddProject()">이 폴더 선택</button>
    </div>
  </div>
</div>

<!-- 설정 이동 경고 모달 -->
<div class="modal-backdrop" id="nav-modal">
  <div class="modal">
    <h2>⚠️ Agent 작업 중</h2>
    <p>Agent가 현재 응답을 생성하고 있습니다.<br>지금 이동하면 진행 중인 작업이 중단됩니다.</p>
    <div class="modal-actions">
      <button class="btn btn-secondary" onclick="closeNavModal()">계속 기다리기</button>
      <button class="btn btn-danger" onclick="confirmNav()">중단하고 이동</button>
    </div>
  </div>
</div>
<script src="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/highlight.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/marked/marked.min.js"></script>
<script>
// marked + highlight.js 설정
marked.setOptions({
  highlight: (code, lang) => {
    if (lang && hljs.getLanguage(lang)) {
      return hljs.highlight(code, { language: lang }).value;
    }
    return hljs.highlightAuto(code).value;
  },
  breaks: true,
  gfm: true,
});

const projectName = new URLSearchParams(location.search).get('p') || '';
const ws = new WebSocket('ws://' + location.host + '/ws' + (projectName ? '?p=' + encodeURIComponent(projectName) : ''));
const msgs = document.getElementById('messages');
const input = document.getElementById('input');
const sendBtn = document.getElementById('send');
const badge = document.getElementById('model-badge');

let currentProgressGroup = null;
let thinkingEl = null;
let isWorking = false;
let pendingNavUrl = null;
const cancelBtn = document.getElementById('cancel-btn');

function showThinking() {
  if (thinkingEl) return;
  const wrap = document.createElement('div');
  wrap.className = 'msg bot';
  const bubble = document.createElement('div');
  bubble.className = 'bubble progress';
  bubble.innerHTML = '<div class="thinking"><span></span><span></span><span></span></div>';
  wrap.appendChild(bubble);
  msgs.appendChild(wrap);
  thinkingEl = wrap;
}

function hideThinking() {
  if (thinkingEl) { thinkingEl.remove(); thinkingEl = null; }
}

ws.onopen = () => {
  badge.textContent = '연결됨';
  addInfo('DevPilot에 연결되었습니다. 코드베이스에 대해 질문해보세요!');
};
ws.onclose = () => { badge.textContent = '연결 끊김'; badge.style.color = '#f85149'; };
ws.onerror  = () => { badge.textContent = '오류'; badge.style.color = '#f85149'; };

ws.onmessage = (e) => {
  const data = JSON.parse(e.data);
  if (data.type === 'history') {
    data.entries.forEach(entry => {
      if (entry.role === 'user') addUserMessage(entry.content);
      else addBotMessage(entry.content, data.model);
    });
    msgs.scrollTop = msgs.scrollHeight;
  } else if (data.type === 'progress') {
    hideThinking();
    appendProgress(data.text);
  } else if (data.type === 'response') {
    hideThinking();
    finalizeProgress();
    addBotMessage(data.text, data.model);
    badge.textContent = data.model;
    setLoading(false);
  } else if (data.type === 'error') {
    hideThinking();
    finalizeProgress();
    addError(data.text);
    setLoading(false);
  } else if (data.type === 'info') {
    hideThinking();
    addInfo(data.text);
    setLoading(false);
  } else if (data.type === 'cancelled') {
    hideThinking();
    finalizeProgress();
    addInfo(data.text);
    setLoading(false);
  }
  msgs.scrollTop = msgs.scrollHeight;
};

function appendProgress(text) {
  if (!currentProgressGroup) {
    const wrap = document.createElement('div');
    wrap.className = 'msg bot';
    const bubble = document.createElement('div');
    bubble.className = 'bubble progress active';
    wrap.appendChild(bubble);
    msgs.appendChild(wrap);
    currentProgressGroup = bubble;
  }
  currentProgressGroup.textContent += (currentProgressGroup.textContent ? '\n' : '') + text;
  msgs.scrollTop = msgs.scrollHeight;
}

function finalizeProgress() {
  if (currentProgressGroup) {
    currentProgressGroup.classList.remove('active');
    currentProgressGroup = null;
  }
}

function addUserMessage(text) {
  const wrap = document.createElement('div');
  wrap.className = 'msg user';
  const bubble = document.createElement('div');
  bubble.className = 'bubble';
  bubble.textContent = text;
  wrap.appendChild(bubble);
  msgs.appendChild(wrap);
}

function addBotMessage(text, model) {
  const wrap = document.createElement('div');
  wrap.className = 'msg bot';
  const bubble = document.createElement('div');
  bubble.className = 'bubble md';
  bubble.innerHTML = marked.parse(text);
  const meta = document.createElement('div');
  meta.className = 'meta';
  meta.innerHTML = 'DevPilot &nbsp;<span class="model-tag">[' + model + ']</span>';
  wrap.appendChild(bubble);
  wrap.appendChild(meta);
  msgs.appendChild(wrap);
  bubble.querySelectorAll('pre code').forEach(el => hljs.highlightElement(el));
}

function addError(text) {
  const wrap = document.createElement('div');
  wrap.className = 'msg bot';
  const bubble = document.createElement('div');
  bubble.className = 'bubble error';
  bubble.textContent = '⚠️ ' + text;
  wrap.appendChild(bubble);
  msgs.appendChild(wrap);
}

function addInfo(text) {
  const wrap = document.createElement('div');
  wrap.className = 'msg bot';
  const bubble = document.createElement('div');
  bubble.className = 'bubble info';
  bubble.textContent = text;
  wrap.appendChild(bubble);
  msgs.appendChild(wrap);
}

function setLoading(v) {
  isWorking = v;
  sendBtn.disabled = v;
  sendBtn.textContent = v ? '처리 중...' : '전송';
  input.disabled = v;
  cancelBtn.style.display = v ? 'block' : 'none';
}

function cancelAgent() {
  ws.send(JSON.stringify({ type: 'cancel' }));
  setLoading(false);
  hideThinking();
  finalizeProgress();
}

async function post(url, body) {
  const res = await fetch(url, { method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify(body) });
  return res.json();
}

let browserCurrentPath = '';

async function browseTo(path) {
  const res = await fetch('/api/browse?path=' + encodeURIComponent(path));
  const data = await res.json();
  if (!data.ok && data.message) {
    document.getElementById('add-project-error').textContent = data.message;
    document.getElementById('add-project-error').style.display = 'block';
    return;
  }
  browserCurrentPath = data.path;
  document.getElementById('browser-path').textContent = data.path;
  document.getElementById('add-project-error').style.display = 'none';
  const list = document.getElementById('browser-list');
  list.innerHTML = '';
  const itemStyle = 'display:flex;align-items:center;gap:8px;padding:7px 12px;cursor:pointer;font-size:13px;border-bottom:1px solid #21262d;color:#e6edf3;';
  const hoverStyle = 'background:#1f2937;';
  if (data.parent) {
    const up = document.createElement('div');
    up.style.cssText = itemStyle + 'color:#8b949e;';
    up.innerHTML = '<span style="font-size:16px">↑</span> 상위 폴더';
    up.onmouseenter = () => up.style.background = '#1f2937';
    up.onmouseleave = () => up.style.background = '';
    up.onclick = () => browseTo(data.parent);
    list.appendChild(up);
  }
  if (data.dirs.length === 0) {
    const empty = document.createElement('div');
    empty.style.cssText = 'padding:12px;color:#6e7681;font-size:12px;text-align:center;';
    empty.textContent = '하위 폴더 없음';
    list.appendChild(empty);
  }
  data.dirs.forEach(name => {
    const row = document.createElement('div');
    row.style.cssText = itemStyle;
    row.innerHTML = '<span style="color:#ffa657">📁</span>' + name;
    row.onmouseenter = () => row.style.background = '#1f2937';
    row.onmouseleave = () => row.style.background = '';
    row.onclick = () => browseTo(data.path + '/' + name);
    list.appendChild(row);
  });
}

async function openAddProject() {
  document.getElementById('add-project-modal').classList.add('show');
  if (!browserCurrentPath) await browseTo('');
}

function closeAddProject() {
  document.getElementById('add-project-modal').classList.remove('show');
}

async function confirmAddProject() {
  if (!browserCurrentPath) return;
  const errEl = document.getElementById('add-project-error');
  errEl.style.display = 'none';
  const res = await post('/api/projects', { dir: browserCurrentPath });
  if (res.ok) {
    closeAddProject();
    window.location.href = '/?p=' + encodeURIComponent(res.name);
  } else {
    errEl.textContent = res.message;
    errEl.style.display = 'block';
  }
}

async function removeProject(name) {
  if (!confirm("'" + name + "' 프로젝트를 목록에서 제거할까요?\n(파일은 삭제되지 않습니다)")) return;
  const res = await fetch('/api/projects?name=' + encodeURIComponent(name), { method: 'DELETE' });
  const data = await res.json();
  if (data.ok) {
    window.location.href = data.next ? '/?p=' + encodeURIComponent(data.next) : '/';
  } else {
    alert(data.message);
  }
}

function openNavModal(url) {
  pendingNavUrl = url;
  document.getElementById('nav-modal').classList.add('show');
}

function closeNavModal() {
  pendingNavUrl = null;
  document.getElementById('nav-modal').classList.remove('show');
}

function confirmNav() {
  cancelAgent();
  const url = pendingNavUrl;
  closeNavModal();
  window.open(url, '_blank');
}

// 설정 링크 클릭 가로채기
document.querySelector('a[href="/settings"]').addEventListener('click', e => {
  if (!isWorking) return;
  e.preventDefault();
  openNavModal('/settings');
});

// 다른 페이지로 이탈 시 경고
window.addEventListener('beforeunload', e => {
  if (!isWorking) return;
  e.preventDefault();
  e.returnValue = '';
});

function sendMessage() {
  const text = input.value.trim();
  if (!text || sendBtn.disabled) return;
  addUserMessage(text);
  currentProgressGroup = null;
  ws.send(JSON.stringify({ type: 'message', text }));
  input.value = '';
  input.style.height = 'auto';
  setLoading(true);
  showThinking();
  msgs.scrollTop = msgs.scrollHeight;
}

function clearSession() {
  ws.send(JSON.stringify({ type: 'message', text: '/clear' }));
}

input.addEventListener('keydown', e => {
  if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendMessage(); }
});
input.addEventListener('input', () => {
  input.style.height = 'auto';
  input.style.height = Math.min(input.scrollHeight, 160) + 'px';
});
</script>
</body>
</html>
""".trimIndent()

private fun settingsHtml() = """
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>DevPilot 설정</title>
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body { background: #0d1117; color: #e6edf3; font-family: 'Segoe UI', system-ui, sans-serif; min-height: 100vh; }
  header { padding: 14px 24px; border-bottom: 1px solid #21262d; background: #161b22; display: flex; align-items: center; gap: 14px; }
  header h1 { font-size: 16px; font-weight: 600; }
  header a { color: #58a6ff; font-size: 13px; text-decoration: none; }
  header a:hover { text-decoration: underline; }
  .container { max-width: 760px; margin: 32px auto; padding: 0 24px 60px; }
  .tabs { display: flex; gap: 2px; border-bottom: 1px solid #21262d; margin-bottom: 28px; }
  .tab { padding: 8px 18px; font-size: 13px; color: #8b949e; cursor: pointer; border-bottom: 2px solid transparent; background: none; border-top: none; border-left: none; border-right: none; transition: color .15s; }
  .tab:hover { color: #e6edf3; }
  .tab.active { color: #e6edf3; border-bottom-color: #1f6feb; }
  .panel { display: none; }
  .panel.active { display: block; }
  .section-title { font-size: 14px; font-weight: 600; color: #e6edf3; margin-bottom: 16px; }
  .field { margin-bottom: 18px; }
  .field label { display: block; font-size: 12px; color: #8b949e; margin-bottom: 6px; font-weight: 500; text-transform: uppercase; letter-spacing: .5px; }
  .field input, .field select { width: 100%; background: #0d1117; border: 1px solid #30363d; border-radius: 6px; color: #e6edf3; font-size: 13px; padding: 8px 12px; outline: none; transition: border-color .15s; }
  .field input:focus, .field select:focus { border-color: #1f6feb; }
  .field select option { background: #161b22; }
  .row { display: flex; gap: 10px; }
  .row .field { flex: 1; }
  .btn { padding: 8px 18px; border-radius: 6px; font-size: 13px; font-weight: 500; cursor: pointer; border: none; transition: background .15s; }
  .btn-primary { background: #1f6feb; color: #fff; }
  .btn-primary:hover { background: #388bfd; }
  .btn-secondary { background: #21262d; color: #e6edf3; border: 1px solid #30363d; }
  .btn-secondary:hover { background: #30363d; }
  .btn-danger { background: #da3633; color: #fff; }
  .btn-danger:hover { background: #f85149; }
  .card-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(160px, 1fr)); gap: 12px; margin-bottom: 20px; }
  .preset-card { background: #161b22; border: 1px solid #30363d; border-radius: 8px; padding: 14px 16px; cursor: pointer; transition: border-color .15s, background .15s; }
  .preset-card:hover { border-color: #1f6feb; background: #1f2937; }
  .preset-card.active { border-color: #1f6feb; background: #1f2937; }
  .preset-card h3 { font-size: 13px; font-weight: 600; margin-bottom: 6px; }
  .preset-card p { font-size: 11px; color: #8b949e; line-height: 1.5; }
  .status-bar { background: #161b22; border: 1px solid #30363d; border-radius: 8px; padding: 14px 16px; margin-bottom: 24px; font-size: 12px; line-height: 1.8; }
  .status-bar .key { color: #8b949e; }
  .status-bar .val { color: #3fb950; font-family: monospace; }
  .toast { position: fixed; bottom: 24px; right: 24px; background: #1f6feb; color: #fff; padding: 10px 18px; border-radius: 8px; font-size: 13px; opacity: 0; transition: opacity .3s; pointer-events: none; z-index: 100; }
  .toast.show { opacity: 1; }
  .toast.error { background: #da3633; }
  .divider { border: none; border-top: 1px solid #21262d; margin: 24px 0; }
  .hint { font-size: 11px; color: #6e7681; margin-top: 5px; }
  .radio-group { display: flex; gap: 12px; flex-wrap: wrap; }
  .radio-label { display: flex; align-items: center; gap: 7px; cursor: pointer; font-size: 13px; padding: 7px 14px; border: 1px solid #30363d; border-radius: 6px; transition: border-color .15s; }
  .radio-label:hover { border-color: #58a6ff; }
  .radio-label input[type=radio] { accent-color: #1f6feb; }
  .radio-label.selected { border-color: #1f6feb; background: #1f2937; }
  ::-webkit-scrollbar { width: 5px; } ::-webkit-scrollbar-track { background: transparent; } ::-webkit-scrollbar-thumb { background: #30363d; border-radius: 3px; }
</style>
</head>
<body>
<header>
  <h1>⚡ DevPilot 설정</h1>
  <span style="flex:1"></span>
  <a href="/">← 채팅으로 돌아가기</a>
</header>
<div class="container">

  <div id="status-bar" class="status-bar">로딩 중...</div>

  <div class="tabs">
    <button class="tab active" onclick="switchTab('apikeys')">API 키</button>
    <button class="tab" onclick="switchTab('strategy')">전략 & 모델</button>
    <button class="tab" onclick="switchTab('presets')">프리셋</button>
    <button class="tab" onclick="switchTab('session')">세션 모델</button>
  </div>

  <!-- ── API 키 탭 ── -->
  <div id="tab-apikeys" class="panel active">
    <p class="hint" style="margin-bottom:20px">API 키는 <code>~/.devpilot/config.json</code>에 로컬 저장됩니다. 외부로 전송되지 않습니다.</p>
    <div class="field">
      <label>Google API Key (Gemini)</label>
      <div class="row" style="align-items:flex-end">
        <div class="field" style="margin:0"><input id="key-google" type="password" placeholder="AIza..." autocomplete="off"></div>
        <button class="btn btn-primary" onclick="saveKey('google')">저장</button>
      </div>
    </div>
    <div class="field">
      <label>Anthropic API Key (Claude)</label>
      <div class="row" style="align-items:flex-end">
        <div class="field" style="margin:0"><input id="key-anthropic" type="password" placeholder="sk-ant-..." autocomplete="off"></div>
        <button class="btn btn-primary" onclick="saveKey('anthropic')">저장</button>
      </div>
    </div>
    <div class="field">
      <label>OpenAI API Key (GPT)</label>
      <div class="row" style="align-items:flex-end">
        <div class="field" style="margin:0"><input id="key-openai" type="password" placeholder="sk-..." autocomplete="off"></div>
        <button class="btn btn-primary" onclick="saveKey('openai')">저장</button>
      </div>
    </div>
  </div>

  <!-- ── 전략 탭 ── -->
  <div id="tab-strategy" class="panel">
    <div class="field">
      <label>라우팅 전략</label>
      <div class="radio-group" id="strategy-group">
        <label class="radio-label" id="radio-auto">
          <input type="radio" name="strategy" value="auto" onchange="saveStrategy('auto')">
          auto <span style="color:#6e7681;font-size:11px">— 질문 유형별 전문 에이전트</span>
        </label>
        <label class="radio-label" id="radio-primary">
          <input type="radio" name="strategy" value="primary" onchange="saveStrategy('primary')">
          primary <span style="color:#6e7681;font-size:11px">— 항상 primary 먼저</span>
        </label>
        <label class="radio-label" id="radio-manual">
          <input type="radio" name="strategy" value="manual" onchange="saveStrategy('manual')">
          manual <span style="color:#6e7681;font-size:11px">— /model로 지정한 모델만</span>
        </label>
      </div>
    </div>
    <hr class="divider">
    <div class="section-title">Primary 모델</div>
    <div class="row">
      <div class="field">
        <label>Provider</label>
        <select id="primary-provider" onchange="updateModelHint()">
          <option value="google">Google (Gemini)</option>
          <option value="anthropic">Anthropic (Claude)</option>
          <option value="openai">OpenAI (GPT)</option>
          <option value="ollama">Ollama (Local)</option>
        </select>
      </div>
      <div class="field">
        <label>Model ID</label>
        <input id="primary-model" type="text" placeholder="gemini-2.5-flash">
        <div id="model-hint" class="hint"></div>
      </div>
    </div>
    <button class="btn btn-primary" onclick="savePrimary()">Primary 모델 저장</button>
  </div>

  <!-- ── 프리셋 탭 ── -->
  <div id="tab-presets" class="panel">
    <p style="font-size:13px;color:#8b949e;margin-bottom:20px">프리셋을 선택하면 전략과 모델 매핑이 한 번에 적용됩니다.</p>
    <div class="card-grid">
      <div class="preset-card" id="preset-cloud-google" onclick="applyPreset('cloud-google')">
        <h3>☁ Cloud Google</h3>
        <p>Gemini Flash/Pro<br>auto 전략<br>API 키 필요</p>
      </div>
      <div class="preset-card" id="preset-cloud-claude" onclick="applyPreset('cloud-claude')">
        <h3>☁ Cloud Claude</h3>
        <p>Claude Haiku/Sonnet/Opus<br>auto 전략<br>API 키 필요</p>
      </div>
      <div class="preset-card" id="preset-local" onclick="applyPreset('local')">
        <h3>💻 Fully Local</h3>
        <p>qwen3 / qwen3-coder<br>auto 전략<br>Ollama 필요</p>
      </div>
      <div class="preset-card" id="preset-hybrid" onclick="applyPreset('hybrid')">
        <h3>⚡ Hybrid</h3>
        <p>Gemini + qwen3-coder<br>auto 전략<br>Gemini + Ollama</p>
      </div>
    </div>
  </div>

  <!-- ── 세션 모델 탭 ── -->
  <div id="tab-session" class="panel">
    <p style="font-size:13px;color:#8b949e;margin-bottom:20px">이번 세션에만 특정 모델을 고정합니다. 서버 재시작 시 초기화됩니다.</p>
    <div class="row">
      <div class="field">
        <label>Provider</label>
        <select id="session-provider">
          <option value="google">Google (Gemini)</option>
          <option value="anthropic">Anthropic (Claude)</option>
          <option value="openai">OpenAI (GPT)</option>
          <option value="ollama">Ollama (Local)</option>
        </select>
      </div>
      <div class="field">
        <label>Model ID</label>
        <input id="session-model" type="text" placeholder="gemini-2.5-flash">
      </div>
    </div>
    <div style="display:flex;gap:10px">
      <button class="btn btn-primary" onclick="setSessionModel()">이 모델로 고정</button>
      <button class="btn btn-danger" onclick="resetSessionModel()">고정 해제</button>
    </div>
    <div id="session-current" style="margin-top:16px;font-size:12px;color:#6e7681"></div>
  </div>

</div>

<div class="toast" id="toast"></div>

<script>
let cfg = {};

async function loadConfig() {
  const res = await fetch('/api/config');
  cfg = await res.json();
  renderStatus();
  renderStrategyTab();
  renderSessionTab();
}

function renderStatus() {
  const s = cfg;
  const keys = Object.keys(s.apiKeys||{});
  document.getElementById('status-bar').innerHTML =
    '<span class="key">전략:</span> <span class="val">' + (s.strategy||'—') + '</span> &nbsp;|&nbsp; ' +
    '<span class="key">Primary:</span> <span class="val">' + (s.primary ? s.primary.provider+'/'+s.primary.modelId : '—') + '</span> &nbsp;|&nbsp; ' +
    '<span class="key">API 키:</span> <span class="val">' + (keys.length ? keys.join(', ') : '없음') + '</span>' +
    (s.sessionModel ? ' &nbsp;|&nbsp; <span class="key">세션 모델:</span> <span class="val" style="color:#ffa657">' + s.sessionModel.provider+'/'+s.sessionModel.modelId + '</span>' : '');
}

function renderStrategyTab() {
  ['auto','primary','manual'].forEach(v => {
    const el = document.getElementById('radio-'+v);
    const radio = el.querySelector('input');
    radio.checked = cfg.strategy === v;
    el.classList.toggle('selected', cfg.strategy === v);
  });
  if (cfg.primary) {
    document.getElementById('primary-provider').value = cfg.primary.provider;
    document.getElementById('primary-model').value = cfg.primary.modelId;
    updateModelHint();
  }
}

function renderSessionTab() {
  const el = document.getElementById('session-current');
  if (cfg.sessionModel) {
    el.innerHTML = '현재 고정 모델: <span style="color:#ffa657;font-family:monospace">' + cfg.sessionModel.provider+'/'+cfg.sessionModel.modelId + '</span>';
    document.getElementById('session-provider').value = cfg.sessionModel.provider;
    document.getElementById('session-model').value = cfg.sessionModel.modelId;
  } else {
    el.textContent = '세션 모델 고정 없음 (설정 파일의 전략 사용)';
  }
}

function updateModelHint() {
  const p = document.getElementById('primary-provider').value;
  const hints = {
    google: 'gemini-2.5-flash | gemini-2.5-pro',
    anthropic: 'claude-haiku-4-5 | claude-sonnet-4-6 | claude-opus-4',
    openai: 'gpt-4o | gpt-4o-mini | o3',
    ollama: 'qwen3 | qwen3-coder | gemma4:26b',
  };
  document.getElementById('model-hint').textContent = hints[p] || '';
}

function switchTab(name) {
  document.querySelectorAll('.tab').forEach((t,i) => {
    const names = ['apikeys','strategy','presets','session'];
    t.classList.toggle('active', names[i] === name);
  });
  document.querySelectorAll('.panel').forEach(p => p.classList.remove('active'));
  document.getElementById('tab-'+name).classList.add('active');
}

async function saveKey(provider) {
  const key = document.getElementById('key-'+provider).value.trim();
  if (!key) return showToast('API 키를 입력하세요.', true);
  const res = await post('/api/config/apikey', { provider, key });
  if (res.ok) { showToast(res.message); document.getElementById('key-'+provider).value=''; loadConfig(); }
  else showToast(res.message, true);
}

async function saveStrategy(strategy) {
  ['auto','primary','manual'].forEach(v => {
    document.getElementById('radio-'+v).classList.toggle('selected', v === strategy);
  });
  const res = await post('/api/config/strategy', { strategy });
  if (res.ok) { showToast(res.message); loadConfig(); }
  else showToast(res.message, true);
}

async function savePrimary() {
  const provider = document.getElementById('primary-provider').value;
  const modelId  = document.getElementById('primary-model').value.trim();
  if (!modelId) return showToast('Model ID를 입력하세요.', true);
  const res = await post('/api/config/primary', { provider, modelId });
  if (res.ok) { showToast(res.message); loadConfig(); }
  else showToast(res.message, true);
}

async function applyPreset(name) {
  document.querySelectorAll('.preset-card').forEach(c => c.classList.remove('active'));
  document.getElementById('preset-'+name).classList.add('active');
  const res = await post('/api/config/preset', { name });
  if (res.ok) { showToast(res.message); loadConfig(); renderStrategyTab(); }
  else showToast(res.message, true);
}

async function setSessionModel() {
  const provider = document.getElementById('session-provider').value;
  const modelId  = document.getElementById('session-model').value.trim();
  if (!modelId) return showToast('Model ID를 입력하세요.', true);
  const res = await post('/api/model', { provider, modelId });
  if (res.ok) { showToast(res.message); loadConfig(); }
  else showToast(res.message, true);
}

async function resetSessionModel() {
  const res = await post('/api/model', { reset: true });
  if (res.ok) { showToast(res.message); loadConfig(); }
  else showToast(res.message, true);
}

async function post(url, body) {
  const res = await fetch(url, { method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify(body) });
  return res.json();
}

function showToast(msg, error=false) {
  const t = document.getElementById('toast');
  t.textContent = msg;
  t.className = 'toast show' + (error ? ' error' : '');
  setTimeout(() => t.className = 'toast', 2800);
}

loadConfig();
</script>
</body>
</html>
""".trimIndent()
