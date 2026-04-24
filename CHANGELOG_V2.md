# DevPilot v2 — 구현 완료 내역

> 기획 문서: [PLAN_V2.md](PLAN_V2.md)
> 구현 기간: 2025년 4월

---

## 개요

MVP(v1) 이후 실사용에서 발견된 세 가지 문제를 해결한 릴리스.

| 영역 | v1 문제 | v2 해결 |
|------|---------|---------|
| 대화 메모리 | 이력을 텍스트로 시스템 프롬프트에 삽입 → LLM이 "참고 문서"로 처리 | Koog DSL 네이티브 메시지 배열로 주입 |
| 에이전트 전략 | 모든 질문에 동일 모델, 경합(2배 비용), 품질 검증 없음 | Router → Specialist → Critic 파이프라인 |
| 범용성 | Google API 종속, 코드 수정 없이 모델 변경 불가 | 4개 Provider 지원 + 설정 파일 + Web UI |

---

## 1. 대화 메모리 개선

### 1-1. 네이티브 메시지 배열 주입 (Path A)

**파일:** `context/ConversationHistory.kt`, `agent/DevPilotAgent.kt`

Koog `prompt {}` DSL 내부에서 `user()` / `assistant()` 함수를 직접 호출해
이력을 실제 메시지 턴으로 전달한다. LLM이 대화 맥락을 네이티브하게 인식하므로
"그 파일", "아까 말한 것" 같은 참조 표현 정확도가 크게 향상된다.

```kotlin
val agentPrompt = koogPrompt("devpilot-chat") {
    system(buildSystemPrompt(...))
    history.forEach { entry ->
        if (entry.role == "user") user(entry.content) else assistant(entry.content)
    }
}
```

### 1-2. 파라미터 조정

| 항목 | v1 | v2 |
|------|----|----|
| user 메시지 최대 길이 | 2,000자 | 4,000자 |
| assistant 메시지 최대 길이 | 2,000자 | 8,000자 (제한 없음에 가깝게) |
| 유지 메시지 수 (`KEEP_RECENT`) | 8개 (4턴) | 12개 (6턴) |
| 압축 임계값 (`COMPRESS_THRESHOLD`) | 20개 | 24개 |

### 1-3. WorkingMemory — 구조화된 사실 누적

**파일:** `context/WorkingMemory.kt`

자유 서술 요약 대신 세 가지 필드로 분리해 매 응답 후 append한다.
압축이 돌아도 파일 경로·함수명 같은 구체 정보가 보존된다.

```kotlin
@Serializable
data class WorkingMemory(
    val confirmedFacts: List<String>,  // "재시도: PaymentRetryService.kt:142"
    val openQuestions: List<String>,   // "실패 알림 채널 불명확"
    val exploredFiles: List<String>,   // 이미 읽은 파일 (중복 탐색 방지)
)
```

저장 위치: `{targetDir}/.devpilot/working_memory.json`  
최대 보존: confirmedFacts 30개, openQuestions 15개, exploredFiles 50개

---

## 2. 에이전트 전략 교체

### 2-1. QuestionType + Router

**파일:** `agent/QuestionType.kt`, `agent/Router.kt`

키워드 기반 분류로 LLM 비용 없이 질문 유형을 결정한다.

| 유형 | 트리거 키워드 | 기본 전문 모델 |
|------|-------------|--------------|
| `LOCATE` | 어디, 찾, 위치, 경로 | Gemini Flash / qwen3-coder |
| `REASON` | 왜, 이유, 의도, 설계 | Gemini Pro / qwen3 |
| `DEBUG` | 에러, 버그, 오류, 실패, 안되 | Gemini Flash / qwen3-coder |
| `ANALYZE` | 그 외 (기본값) | Gemini Pro / qwen3 |

### 2-2. Critic 품질 검증

**파일:** `agent/Critic.kt`

Specialist 답변을 경량 모델이 세 기준으로 검증한다.

- 파일 경로가 실제로 인용됐는가?
- 질문의 핵심에 답했는가?
- 추측이 아닌 코드 근거가 있는가?

실패 시 피드백을 질문에 첨부해 재시도 (최대 2회). 모두 통과하지 못하면 마지막 답변을 반환.

### 2-3. 라우팅 파이프라인

```
chat(userMessage)
  → Router.classify()          # 질문 유형 결정 (LLM 없음)
  → runWithCritic()            # Critic 루프 (최대 3회)
    → runPrimary()             # 전략에 따라 모델 선택
      → sessionModelOverride   # /model 지정 시 최우선
      → RoutingStrategy.AUTO   # 유형별 Specialist
      → RoutingStrategy.PRIMARY# 항상 primary
      → RoutingStrategy.MANUAL # override 없으면 오류 안내
      → Ollama fallback        # cloud 실패 시
  → updateWorkingMemory()      # 새 사실 추출·누적
```

---

## 3. 범용성 — 멀티 Provider 설정

### 3-1. 데이터 모델

**파일:** `config/Provider.kt`, `config/ModelConfig.kt`, `config/RoutingStrategy.kt`, `config/AgentStrategyConfig.kt`, `config/DevPilotConfig.kt`

```kotlin
enum class Provider { GOOGLE, ANTHROPIC, OPENAI, OLLAMA }

data class ModelConfig(val provider: Provider, val modelId: String)

data class AgentStrategyConfig(
    val strategy: RoutingStrategy,           // AUTO | PRIMARY | MANUAL
    val primary: ModelConfig,
    val specialists: Map<String, ModelConfig>, // QuestionType.name → ModelConfig
    val critic: ModelConfig?,
)
```

### 3-2. 내장 프리셋

| 프리셋 | 전략 | Primary | Specialist (ANALYZE) |
|--------|------|---------|----------------------|
| `cloud-google` | AUTO | Gemini Flash | Gemini Pro |
| `cloud-claude` | AUTO | Claude Sonnet 4.6 | Claude Opus 4 |
| `local` | AUTO | qwen3 | qwen3 |
| `hybrid` | AUTO | Gemini Flash | Gemini Flash + qwen3-coder |

### 3-3. 설정 저장소

**파일:** `config/StrategyConfigStorage.kt`

| 파일 | 용도 |
|------|------|
| `~/.devpilot/config.json` | 전역 API 키 + 기본 전략 (gitignore) |
| `{targetDir}/.devpilot/strategy.json` | 프로젝트별 전략 오버라이드 (키 없음, 팀 공유 가능) |

프로젝트 설정이 존재하면 전략만 오버라이드, API 키는 전역에서만 관리.

### 3-4. LLMClientFactory

**파일:** `config/LLMClientFactory.kt`

`ModelConfig → MultiLLMPromptExecutor` 변환을 담당한다.

- **Google**: `GoogleLLMClient` + `GoogleModels.*` 상수
- **Anthropic**: `AnthropicLLMClient` + `AnthropicModels.*` 상수
- **OpenAI**: `OpenAILLMClient` + `LLModel(LLMProvider.OpenAI, id, ...)`
- **Ollama**: `OllamaClient` + `LLModel(LLMProvider.Ollama, id, ...)` (로컬, 키 없음)

API 키는 설정 파일 → 환경변수 순으로 탐색 (`GOOGLE_API_KEY`, `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`).

---

## 4. CLI 커맨드 추가

### /config

**파일:** `command/ConfigCommand.kt`

```
/config show                            현재 설정 출력
/config set apikey <provider> <key>     API 키 저장
/config set strategy <auto|primary|manual>
/config set primary <provider> <modelId>
/config preset <cloud-google|cloud-claude|local|hybrid>
```

### /model

**파일:** `command/ModelCommand.kt`

세션 내 모델 일시 오버라이드. 서버 재시작 시 초기화.

```
/model gemini          → google/gemini-2.5-flash
/model claude          → anthropic/claude-sonnet-4-6
/model haiku           → anthropic/claude-haiku-4-5
/model opus            → anthropic/claude-opus-4
/model gpt4o           → openai/gpt-4o
/model qwen3-coder     → ollama/qwen3-coder
/model qwen3           → ollama/qwen3
/model reset           → 오버라이드 해제
/model <provider>/<id> → 직접 지정
```

---

## 5. Web UI 개선

### 5-1. /settings 페이지

**파일:** `web/WebServer.kt`

채팅 헤더의 "⚙ 설정" 링크로 접근. 4개 탭으로 구성.

| 탭 | 기능 |
|----|------|
| API 키 | Google / Anthropic / OpenAI 키 입력·저장 |
| 전략 & 모델 | 라우팅 전략 선택 + Primary 모델 변경 |
| 프리셋 | 카드 클릭으로 원클릭 적용 |
| 세션 모델 | 이번 세션만 특정 모델 고정 / 해제 |

REST API 엔드포인트:

```
GET  /api/config              현재 설정 조회
POST /api/config/apikey       API 키 저장
POST /api/config/strategy     전략 변경
POST /api/config/primary      Primary 모델 변경
POST /api/config/preset       프리셋 적용
POST /api/model               세션 모델 오버라이드
```

### 5-2. 진행 상황 표시

| 시점 | 표시 내용 |
|------|---------|
| 전송 직후 | 파란 점 3개 튀는 thinking 애니메이션 |
| 첫 progress 수신 | 애니메이션 → progress 버블 전환, `▌` 커서 깜빡임 |
| 응답 완료 | 커서 사라지고 최종 답변 표시 |

progress 메시지 내용:

```
🔍  [analyze]  auto 전략        ← 질문 분류 결과 + 현재 전략
🤖  google/gemini-2.5-pro  (analyze)  ← 실행 모델 + 선택 이유
  📁 listFiles  →  src/payment   ← 도구 호출
  ✔ listFiles  (12개 항목)       ← 도구 완료
  📄 readFile  →  PaymentService.kt
  ✔ readFile  (203줄)
  🧐  Critic 평가 중...           ← 품질 검증 시작
  📝  작업 메모리 업데이트 중...   ← 사실 추출
```

### 5-3. 포트 충돌 방지

`findFreePort(startPort)`: 8080부터 8089까지 순차 탐색해 사용 가능한 포트를 자동 선택.
이전 프로세스가 포트를 점유 중이어도 `BindException` 없이 시작.

---

## 6. 신규·변경 파일 목록

| 상태 | 파일 |
|------|------|
| 신규 | `agent/QuestionType.kt` |
| 신규 | `agent/Router.kt` |
| 신규 | `agent/Critic.kt` |
| 신규 | `context/WorkingMemory.kt` |
| 신규 | `config/Provider.kt` |
| 신규 | `config/ModelConfig.kt` |
| 신규 | `config/RoutingStrategy.kt` |
| 신규 | `config/AgentStrategyConfig.kt` |
| 신규 | `config/DevPilotConfig.kt` |
| 신규 | `config/StrategyConfigStorage.kt` |
| 신규 | `config/LLMClientFactory.kt` |
| 신규 | `command/ConfigCommand.kt` |
| 신규 | `command/ModelCommand.kt` |
| 수정 | `agent/DevPilotAgent.kt` — 전체 재작성 |
| 수정 | `context/ConversationHistory.kt` — 파라미터 조정 |
| 수정 | `Main.kt` — 설정 로딩 + 커맨드 등록 |
| 수정 | `web/WebServer.kt` — /settings + REST API + 애니메이션 |
| 수정 | `build.gradle.kts` — anthropic, openai 의존성 추가 |
| 수정 | `gradle/libs.versions.toml` — koog-anthropic, koog-openai 추가 |

---

## 7. 후속 버그픽스 & UX 개선

초기 v2 배포 이후 실사용에서 발견한 문제들을 추가로 수정.

### 7-1. 재연결 시 채팅 이력 복원

WebSocket 재연결(설정 페이지 이동 후 복귀 등) 시 서버가 즉시 `history` 타입 메시지로
전체 대화 이력을 전송 → 브라우저가 user/assistant 버블로 복원.

### 7-2. 버그 수정 3종

| 버그 | 원인 | 수정 |
|------|------|------|
| 작업 중 이탈 시 질문 사라짐 | user 메시지를 처리 완료 후 저장 | 처리 시작 전에 먼저 저장 |
| 세션 모델 변경 미적용 | `sessionModelOverride` `@Volatile` 누락 | `@Volatile` 추가 (+ `primaryFailed` 동일) |
| 모델 오류 시 조용한 fallback | 실패하면 qwen3-coder로 대체 | 명확한 오류 메시지 반환 + `/model reset` 안내 |

### 7-3. Agent 중단 기능 + 안전 모달

| 항목 | 내용 |
|------|------|
| "■ 중단" 버튼 | 작업 중에만 표시. 클릭 시 WebSocket `cancel` 메시지 → 서버 코루틴 취소 |
| 설정 이동 경고 모달 | 작업 중 "⚙ 설정" 클릭 시 "계속 기다리기 / 중단하고 이동" 선택 |
| 브라우저 이탈 경고 | `beforeunload` 이벤트로 기본 이탈 경고 표시 |
| 서버 취소 처리 | chat Job을 `launch {}` 로 분리해 `Job.cancel()` 가능하게 변경 |

---

## 8. 다음 단계 (v3)

계획 문서: [PLAN_V3.md](PLAN_V3.md)
