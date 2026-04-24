# DevPilot v2 — 개선 기획서

> MVP(v1) 구현 완료 후 실사용 및 코드 리뷰를 통해 도출한 구조적 개선 계획.

---

## 현재 상태 (v1)

```
사용자 질문
    ↓
Gemini Primary (실패 시 → 경합 모드 고정)
    ↓
qwen3-coder vs gemma4:26b 병렬 실행 (동일 작업 2배 비용)
    ↓
qwen3 judge (텍스트 품질로 선택)
    ↓
JSONL 저장 → 텍스트 문자열로 systemPrompt에 붙임
```

---

## 문제 목록

| 분류 | 문제 |
|------|------|
| 에이전트 전략 | 경합 = 동일 작업 2배 비용, judge가 정확도가 아닌 글 품질로 선택 |
| 에이전트 전략 | 모든 질문에 동일 에이전트 투입, 전문화 없음 |
| 에이전트 전략 | 답변 품질 검증 수단 없음 (둘 다 틀려도 하나를 골라야 함) |
| 대화 메모리 | 이력을 텍스트로 systemPrompt에 주입 → LLM이 "과거 대화"가 아닌 "참고 문서"로 처리 |
| 대화 메모리 | `MAX_ENTRY_LENGTH=2000` 으로 응답 잘림 → 잘린 정보가 다음 턴 기억으로 들어감 |
| 대화 메모리 | 압축 시 파일 경로·함수명 등 구체적 정보 소실 |
| 범용성 | Google API 종속, 다른 provider 사용 불가 |
| 범용성 | 모델·전략을 코드 수정 없이 바꿀 수 없음 |

---

## 개선 영역 1 — 대화 메모리

### 1-1. 주입 방식 수정

현재 방식과 이상적 방식의 차이:

```
현재:
JSONL 읽기 → 텍스트 문자열 조합 → systemPrompt 끝에 붙임

이상적:
JSONL 읽기 → List<Message> 변환 → AIAgent에 메시지 배열로 전달
```

LLM은 시스템 프롬프트에 텍스트로 박힌 대화를 "참고 문서"로 처리한다.
실제 `user/assistant` 메시지 배열로 전달해야 대화 맥락을 네이티브하게 인식한다.
"그 파일", "아까 말한 것" 같은 참조 표현 정확도가 크게 달라진다.

**선행 조사 필요:** Koog 0.8.0 `AIAgent`가 `initialMessages` 류의 메시지 배열 파라미터를 지원하는지 확인 후 두 경로 중 하나 선택.

**Path A (Koog가 메시지 배열 지원):**
```kotlin
// ConversationHistory에 추가
fun toMessages(): List<KoogMessage> =
    getAllEntries().map { entry ->
        if (entry.role == "user") KoogMessage.User(entry.content)
        else KoogMessage.Assistant(entry.content)
    }

// buildAgent()에서
AIAgent(
    systemPrompt = buildSystemPrompt(...),  // historySection 제거
    initialMessages = conversationHistory.toMessages(),
    ...
)
```

**Path B (미지원 — 시스템 프롬프트 포맷 개선):**
```
현재 포맷:
사용자: 결제 흐름 설명해줘
DevPilot: ...

개선 포맷 (XML 태그):
<conversation_history>
  <turn role="user">결제 흐름 설명해줘</turn>
  <turn role="assistant">PaymentService.kt:87에서...</turn>
</conversation_history>
```
주요 LLM(Gemini, Claude, GPT, qwen)은 모두 XML 태그를 대화 구조로 명확히 인식한다.

### 1-2. 파라미터 조정

| 항목 | 현재 | 변경 | 이유 |
|------|------|------|------|
| `MAX_ENTRY_LENGTH` | 2000자 (전체) | user 4000 / assistant 제한 없음 | 긴 코드 분석 응답이 잘려 다음 턴 기억 손실 |
| `KEEP_RECENT` | 8개 (4턴) | 12개 (6턴) | 온보딩 세션은 맥락 연결이 길어짐 |
| `COMPRESS_THRESHOLD` | 20개 | 24개 | KEEP_RECENT 조정에 맞춤 |

### 1-3. WorkingMemory (압축 손실 해결)

기존 자유 서술 5줄 요약 → 구조화된 사실 누적으로 교체.

```kotlin
// .devpilot/working_memory.json
data class WorkingMemory(
    val confirmedFacts: List<String>,  // "재시도: PaymentRetryService.kt:142"
    val openQuestions: List<String>,   // "실패 알림 채널 불명확"
    val exploredFiles: List<String>,   // 이미 읽은 파일 (중복 탐색 방지)
)
```

매 응답 후 경량 모델이 새 사실만 추출해 append. 덮어쓰기가 아닌 누적이므로
압축이 돌아도 파일 경로·함수명 같은 구체적 정보가 영구 보존된다.

systemPrompt 주입 형태:
```
## 탐색 컨텍스트
확인된 사실:
- 재시도 로직: PaymentRetryService.kt:142
- OrderService: 이벤트 기반 설계
미해결: 실패 알림 채널
탐색 완료: src/payment/, src/order/OrderService.kt
```

---

## 개선 영역 2 — 에이전트 전략

### 2-1. Router + Specialist (경합 대체)

```
사용자 질문
    ↓
[Router] 소형 빠른 모델 — 질문 유형 분류
    ↓
┌────────────────────────────────────────────┐
│ LOCATE       │ ANALYZE        │ REASON     │
│ 파일·함수    │ 흐름·구조      │ 설계 의도  │
│ 위치 탐색   │ 다중 파일 분석 │ 추론 중심  │
│ 소형 모델   │ 대형 모델      │ 추론형     │
└────────────────────────────────────────────┘
```

질문 유형 분류 기준:

| 유형 | 예시 | 최적 에이전트 |
|------|------|--------------|
| LOCATE | "재시도 로직 어디 있어?" | 소형 + codeSearch 집중 |
| ANALYZE | "주문 생성 전체 흐름 설명해줘" | 대형 + readFile 다수 |
| REASON | "왜 이렇게 설계됐어?" | 추론형 모델 |
| DEBUG | "이 에러 왜 나?" | 검색 집중형 |

**경합 대비 장점:**
- 동일 작업 2배 비용 제거
- 정확한 전문가에게 처음부터 맡김 → 품질·속도 동시 향상
- Gemini 실패 시 경합 전환이 아닌 Router가 Ollama 전문가로 라우팅

### 2-2. Critic 패턴 (품질 검증)

```
Specialist 답변 생성
    ↓
[Critic] 경량 모델 — 기준 기반 검증
  ✓ 파일 경로가 실제로 인용됐는가?
  ✓ 질문의 핵심에 답했는가?
  ✓ 추측이 아닌 코드 근거가 있는가?
    ↓
Pass → 반환   /   Fail → Specialist 재시도 (최대 2회)
```

judge(텍스트 품질 선택) → Critic(기준 기반 검증) 으로 전환.
둘 다 틀린 경우도 탐지 가능하며, 1.2배 비용으로 경합(2배)보다 높은 신뢰성.

### 2-3. Pipeline (복잡한 질문 선택적 적용)

ANALYZE 유형 중 "전체 흐름" 류의 대형 질문에만 적용:

```
질문
 ↓
[Explorer]   listFiles + codeSearch로 관련 파일 목록 작성
 ↓
[Analyzer]   Explorer 결과를 받아 파일 읽기 + 흐름 파악
 ↓
[Explainer]  신입 개발자 눈높이로 정리
```

단순 질문에는 오버엔지니어링이므로 Router가 복잡도를 판단해 적용 여부 결정.

---

## 개선 영역 3 — 범용성

### 설정 파일 구조

```
~/.devpilot/config.json              ← 전역 API 키 + 기본 전략 (gitignore)
{targetDir}/.devpilot/strategy.json  ← 프로젝트별 전략 오버라이드 (키 없음, 팀 공유 가능)
```

### 핵심 데이터 모델

```kotlin
enum class Provider { GOOGLE, ANTHROPIC, OPENAI, OLLAMA }

data class ModelConfig(
    val provider: Provider,
    val modelId: String,
    val apiKey: String?,   // Ollama는 null
)

data class AgentStrategyConfig(
    val router: ModelConfig,
    val specialists: Map<QuestionType, ModelConfig>,
    val critic: ModelConfig,
    val fallback: ModelConfig?,
)
```

### 프리셋 예시

| 프리셋 | router | specialist | critic |
|--------|--------|------------|--------|
| Cloud (기본) | Gemini Flash | Gemini Pro | Gemini Flash |
| Claude | Haiku | Opus | Haiku |
| Fully Local | qwen3 | qwen3-coder | qwen3 |
| Hybrid | Gemini Flash | Claude Opus | qwen3 |

### 설정 진입점

**CLI 커맨드:**
```
/config show                              현재 전략 출력
/config set primary google gemini-2.5-flash
/config set apikey anthropic sk-ant-...
/config preset local                      Ollama 전용 프리셋 적용
```

**Web UI `/settings` 페이지:**
```
[API Keys 탭]   provider별 키 입력·저장
[Strategy 탭]   router / specialist / critic 모델 선택
[Presets 탭]    프리셋 원클릭 적용
```

### Koog 지원 범위 (선행 조사 필요)

- `prompt-executor-anthropic-client-jvm` 존재 여부
- `prompt-executor-openai-client-jvm` 존재 여부
- 없으면 해당 provider는 직접 HTTP 구현 또는 제외

---

## v2 통합 흐름

```
사용자 질문
    ↓
[WorkingMemory + JSONL 이력 로드]
    ↓
[Router] 질문 유형 분류 (소형 모델)
    ↓
[Specialist] 유형에 맞는 전문 에이전트 실행
    ↓
[Critic] 품질 검증 → Fail 시 재시도 (최대 2회)
    ↓
[WorkingMemory 업데이트] 새 사실 추출 누적
    ↓
[JSONL 저장]
    ↓
반환
```

---

## 신규 파일 구조

```
DevPilot/
├── src/main/kotlin/devpilot/
│   ├── agent/
│   │   ├── DevPilotAgent.kt          ← AgentStrategyConfig 주입받도록 리팩터
│   │   ├── Router.kt                 ← 신규: 질문 유형 분류
│   │   └── Critic.kt                 ← 신규: 품질 검증
│   ├── config/
│   │   ├── ModelConfig.kt            ← 신규
│   │   ├── AgentStrategyConfig.kt    ← 신규
│   │   ├── StrategyConfigStorage.kt  ← 신규: JSON 읽기/쓰기
│   │   └── LLMClientFactory.kt       ← 신규: ModelConfig → Executor 변환
│   ├── context/
│   │   ├── ConversationHistory.kt    ← 파라미터 조정 + toMessages() 추가
│   │   └── WorkingMemory.kt          ← 신규: 구조화된 사실 누적
│   ├── command/
│   │   └── ConfigCommand.kt          ← 신규: /config CLI 커맨드
│   └── web/
│       └── WebServer.kt              ← /settings 페이지 추가
```

---

## 구현 순서

```
Week 1 — 메모리 안정화
  [1] Koog 메시지 배열 API 조사 → Path A/B 결정
  [2] 대화 주입 방식 수정 (Path A or B)
  [3] MAX_ENTRY_LENGTH / KEEP_RECENT 파라미터 조정
  [4] WorkingMemory 신규 구현

Week 2 — 에이전트 전략 교체
  [5] QuestionType enum + Router 구현
  [6] Specialist 매핑 (유형별 ModelConfig)
  [7] Critic 구현
  [8] 기존 경합 코드 제거

Week 3 — 범용성
  [9]  Koog provider 지원 범위 조사
  [10] ModelConfig + AgentStrategyConfig 데이터 모델
  [11] StrategyConfigStorage (JSON 읽기/쓰기)
  [12] LLMClientFactory
  [13] /config CLI 커맨드
  [14] Web UI /settings 페이지
```

---

## 단계별 로드맵 (전체)

| 단계 | 내용 |
|------|------|
| **MVP (완료)** | ReAct Agent + 도구 3종 + Project Memory + CLI |
| **v1.5 (완료)** | JSONL 세션 기억 + 슬라이딩 윈도우 압축 + 경합 에이전트 + Web UI |
| **v2 (이 문서)** | 메모리 주입 개선 + WorkingMemory + Router/Specialist/Critic + 범용 설정 |
| **v3** | A2A 멀티에이전트 (코드/아키텍처/프로세스 에이전트 분리) |
| **v4** | RAG (Confluence/Notion 임베딩) + Slack Bot 연동 |
