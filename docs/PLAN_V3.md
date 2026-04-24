# DevPilot v3 — 기획서

> 이전 문서: [CHANGELOG_V2.md](CHANGELOG_V2.md)
> 전체 로드맵: [PLAN.md](PLAN.md)

---

## 현재 상태 (v2)

```
사용자 질문
    ↓
Router (키워드 분류)  →  LOCATE / ANALYZE / REASON / DEBUG
    ↓
Specialist (단일 에이전트)  →  readFile / listFiles / codeSearch
    ↓
Critic (품질 검증, 최대 2회 재시도)
    ↓
WorkingMemory 업데이트
```

**남은 한계:**
- 단일 에이전트가 탐색·분석·설명을 혼자 담당 → 복잡한 질문에서 품질 저하
- 도구 안정성 이슈 (대용량 파일 OOM, 바이너리 garbage, rg 미설치 시 예외)
- UX 소도구 누락 (`/memory delete`, `listFiles` 깊이 제한 등)
- Cloud API 429/오류 후 primaryFailed가 세션 내내 고정

---

## 개선 목표

| 우선순위 | 영역 | 목표 |
|---------|------|------|
| 🔴 HIGH | 도구 안정성 | OOM·바이너리·rg 미설치 방어 |
| 🔴 HIGH | A2A 멀티에이전트 | Explorer → Analyzer → Explainer 파이프라인 |
| 🟡 MEDIUM | 자동 복구 | Cloud 일시 오류 후 primaryFailed 자동 해제 |
| 🟡 MEDIUM | UX 소도구 | `/memory delete`, `listFiles maxDepth` |
| 🟢 LOW | 준비 | v4 RAG를 위한 파일 인덱스 기초 |

---

## 개선 영역 1 — 도구 안정성 🔴

### 1-1. `readFile` 크기 제한 + 바이너리 감지

**파일:** `tools/ReadFileTool.kt`

```kotlin
private val MAX_BYTES = 500 * 1024L  // 500 KB

override fun execute(path: String): String {
    val file = File(resolvedPath)
    if (file.length() > MAX_BYTES)
        return "⚠️ 파일이 너무 큽니다 (${file.length() / 1024}KB). 특정 섹션을 검색하려면 codeSearch를 사용하세요."
    val bytes = file.readBytes(512)
    if (bytes.any { it == 0.toByte() })
        return "⚠️ 바이너리 파일은 읽을 수 없습니다: $path"
    return file.readText()
}
```

**검증:** 10MB 이상 파일 경로 질의 → 오류 메시지 확인, .class/.jar 경로 → 바이너리 메시지 확인

### 1-2. `ripgrep` 설치 여부 시작 시 체크

**파일:** `tools/CodeSearchTool.kt` 또는 `Main.kt`

```kotlin
// Main.kt 시작 시
val rgAvailable = runCatching {
    ProcessBuilder("rg", "--version").start().waitFor() == 0
}.getOrDefault(false)

if (!rgAvailable) println("  ⚠️  ripgrep(rg) 미설치 — codeSearch 비활성화. brew install ripgrep")
```

rg 미설치 시 `codeSearch` 도구를 ToolRegistry에서 제외하거나 즉시 안내 메시지 반환.

### 1-3. `listFiles` 깊이 제한

**파일:** `tools/ListFilesTool.kt`

`maxDepth` 파라미터 추가 (기본값 5). 모노레포처럼 깊은 구조에서 토큰 낭비 방지.

```kotlin
@Tool("listFiles")
fun listFiles(path: String, maxDepth: Int = 5): String
```

---

## 개선 영역 2 — A2A 멀티에이전트 🔴

### 2-1. 파이프라인 구조

단일 에이전트 대신 역할 분리된 3단계 파이프라인을 복잡한 질문에 적용.

```
ANALYZE / REASON 유형의 복잡한 질문
    ↓
[Explorer Agent]
  - listFiles로 관련 디렉터리 파악
  - codeSearch로 핵심 심볼 위치 수집
  - 출력: 탐색한 파일 목록 + 핵심 경로
    ↓
[Analyzer Agent]
  - Explorer 결과를 받아 readFile로 코드 상세 분석
  - 흐름·의존관계·설계 패턴 파악
  - 출력: 구조화된 분석 결과
    ↓
[Explainer Agent]
  - Analyzer 결과를 받아 신입 개발자 눈높이로 정리
  - 파일 경로·코드 증거 포함
  - 출력: 최종 답변
```

단순 LOCATE / DEBUG 질문은 기존 단일 에이전트 유지 (오버엔지니어링 방지).

### 2-2. Koog A2A 연동 방식

Koog 0.8.0의 `koog-a2a-server` / `koog-a2a-client` 모듈 사용 가능 여부 선행 조사 필요.

**옵션 A (Koog A2A 지원 시):**
```kotlin
// 각 Agent를 A2A 서버로 등록
val explorerServer = A2AServer(explorerAgent, port = 9001)
val analyzerServer = A2AServer(analyzerAgent, port = 9002)

// 오케스트레이터가 순서대로 호출
val explorerResult = A2AClient.call("http://localhost:9001", question)
val analyzerResult = A2AClient.call("http://localhost:9002", explorerResult)
```

**옵션 B (A2A 미지원 시 — 코루틴 체이닝):**
```kotlin
// 동일 프로세스 내에서 순차 실행
val explorerResult = explorerAgent.run(question)
val analyzerResult = analyzerAgent.run("$question\n\n탐색 결과:\n$explorerResult")
val finalAnswer  = explainerAgent.run("$question\n\n분석:\n$analyzerResult")
```

### 2-3. 모델 매핑

| Agent | 역할 | 권장 모델 |
|-------|------|---------|
| Explorer | 빠른 탐색, 도구 집중 | Gemini Flash / qwen3-coder |
| Analyzer | 깊은 이해, 다중 파일 | Gemini Pro / Claude Opus |
| Explainer | 명확한 설명 | Gemini Flash / Claude Sonnet |

AgentStrategyConfig에 `pipeline` 키 추가로 설정 파일에서 제어 가능하게 구현.

---

## 개선 영역 3 — 자동 복구 🟡

### 3-1. `primaryFailed` 자동 해제

현재 Cloud API 오류 시 `primaryFailed = true`로 고정되어 세션 내내 Ollama 사용.
일시적 429 / 네트워크 오류인 경우 자동 재시도가 더 적합.

**구현 방안:**
- `primaryFailed` 플래그 대신 `failedAt: Long?` (타임스탬프) 저장
- 다음 질문 시 `failedAt`이 5분 이상 지난 경우 자동으로 Cloud 재시도
- 재시도 성공 시 `failedAt = null` 리셋, 실패 시 타임스탬프 갱신

```kotlin
private var primaryFailedAt: Long? = null
private val RETRY_INTERVAL_MS = 5 * 60 * 1000L

private fun isPrimaryAvailable(): Boolean {
    val t = primaryFailedAt ?: return true
    return System.currentTimeMillis() - t > RETRY_INTERVAL_MS
}
```

### 3-2. `/memory delete` 커맨드

**파일:** `command/MemoryCommand.kt`, `memory/ProjectMemoryStorage.kt`

```
/memory list          → 번호 포함 목록 출력
/memory delete 2      → 2번 항목 삭제
/memory delete 1,3,5  → 복수 삭제
```

---

## 개선 영역 4 — v4 RAG 기초 준비 🟢

v4의 RAG 연동을 위한 기반 작업. 실제 임베딩은 v4에서 구현.

### 4-1. 파일 인덱스 캐시

```
{targetDir}/.devpilot/file_index.json
{
  "generatedAt": "2025-04-23T...",
  "files": [
    { "path": "src/payment/PaymentService.kt", "size": 4820, "lastModified": "..." },
    ...
  ]
}
```

프로젝트 시작 시 한 번 생성, 이후 변경된 파일만 갱신.
Agent가 `listFiles` 대신 인덱스를 참조하면 토큰 절약 + 탐색 속도 향상.

### 4-2. 구조 요약 캐시

```
{targetDir}/.devpilot/structure_summary.md
```

최초 실행 시 또는 `/index` 커맨드로 전체 프로젝트 구조를 요약해 저장.
이후 Agent의 시스템 프롬프트에 요약을 포함해 첫 탐색 단계 단축.

---

## 신규 파일 구조 (예상)

```
DevPilot/
├── src/main/kotlin/devpilot/
│   ├── agent/
│   │   ├── DevPilotAgent.kt     ← primaryFailedAt 타임스탬프 교체
│   │   └── pipeline/            ← 신규
│   │       ├── ExplorerAgent.kt
│   │       ├── AnalyzerAgent.kt
│   │       └── ExplainerAgent.kt
│   ├── tools/
│   │   ├── ReadFileTool.kt      ← 크기 제한 + 바이너리 감지
│   │   ├── ListFilesTool.kt     ← maxDepth 파라미터
│   │   └── CodeSearchTool.kt   ← rg 가용 여부 체크
│   ├── command/
│   │   └── MemoryCommand.kt    ← delete 기능 추가
│   └── index/                  ← 신규 (v4 준비)
│       ├── FileIndex.kt
│       └── StructureSummary.kt
```

---

## 구현 순서

```
Week 1 — 도구 안정성 (빠르고 효과 즉시 체감)
  [1] readFile 크기 제한 + 바이너리 감지
  [2] ripgrep 설치 여부 체크
  [3] listFiles maxDepth 파라미터
  [4] /memory delete 커맨드

Week 2 — 자동 복구
  [5] primaryFailed → failedAt 타임스탬프 교체
  [6] 5분 후 Cloud 자동 재시도

Week 3~4 — A2A 멀티에이전트
  [7] Koog A2A 지원 여부 조사
  [8] ExplorerAgent / AnalyzerAgent / ExplainerAgent 구현
  [9] Router에서 복잡도 판단 → 파이프라인 vs 단일 에이전트 분기
  [10] AgentStrategyConfig에 pipeline 설정 추가

Week 5 — v4 기초
  [11] FileIndex 캐시 생성
  [12] /index 커맨드 + StructureSummary 생성
```

---

## 검증 방법

| 항목 | 검증 |
|------|------|
| readFile 크기 제한 | 10MB 파일 경로 질의 → "파일이 너무 큽니다" 메시지 확인 |
| 바이너리 감지 | `.class` / `.jar` 경로 질의 → 바이너리 안내 메시지 확인 |
| ripgrep 체크 | `rg`를 PATH에서 제거 후 시작 → 경고 출력 확인 |
| /memory delete | `/memory add X` → `/memory list` → `/memory delete 1` → 목록 재확인 |
| primaryFailed 복구 | Cloud 오류 유발 → 5분 대기 → 다음 질문 Cloud 재시도 확인 |
| 멀티에이전트 파이프라인 | "주문 생성 전체 흐름 설명해줘" → progress에 Explorer/Analyzer/Explainer 순서 표시 |

---

## 단계별 로드맵 (전체)

| 단계 | 내용 | 상태 |
|------|------|------|
| MVP | ReAct Agent + 도구 3종 + Project Memory + CLI | ✅ 완료 |
| v1.5 | JSONL 세션 기억 + 슬라이딩 윈도우 + Web UI | ✅ 완료 |
| v2 | 메모리 주입 개선 + Router/Specialist/Critic + 범용 설정 + Web 설정 UI | ✅ 완료 |
| **v3** | **도구 안정성 + A2A 멀티에이전트 + 자동 복구** | 🔲 이 문서 |
| v4 | RAG (코드베이스 임베딩) + Confluence/Notion 문서 통합 | 🔲 예정 |
