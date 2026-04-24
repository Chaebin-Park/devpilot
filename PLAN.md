# DevPilot — AI 기반 신입 개발자 온보딩 CLI 도구

## 개요

신입 개발자가 낯선 코드베이스에서 자연어로 질문하면, AI 에이전트가 파일을 직접 탐색하며 코드 흐름과 설계 의도를 설명해주는 CLI 도구.

---

## 문제 정의

- 신입 개발자의 코드베이스 파악에 평균 3~6개월 소요
- 시니어 개발자는 반복 질문 응대로 생산성 30% 손실
- 조직 암묵지(설계 결정, 컨벤션)가 문서화되지 않고 사람 머릿속에만 존재

---

## MVP 범위

- 로컬 코드베이스 디렉터리를 대상으로 자연어 질문 → AI가 파일 직접 탐색 후 답변
- RAG, A2A 멀티에이전트 제외 (이후 단계에서 고려)

---

## 기술 스택

| 항목 | 선택 |
|---|---|
| 언어 | Kotlin |
| AI 프레임워크 | Koog (`agents-core`) |
| LLM | Google Gemini 2.5 Flash |
| 에이전트 전략 | ReAct Strategy |
| 빌드 | Gradle (`installDist`) |

---

## 에이전트 구조

```
사용자 입력 (자연어 질문)
        ↓
[DevPilotAgent] — ReAct Strategy
        ↓
[EventHandler] — 탐색 중인 파일명 실시간 출력
        ↓
[ToolRegistry]
  - readFile     : 파일 내용 읽기
  - listFiles    : 디렉터리 목록 탐색
  - codeSearch   : 키워드/함수명 검색
        ↓
답변 출력
```

---

## 핵심 기능

### 1. 코드 탐색 도구 3종

```kotlin
@Tool("readFile")
fun readFile(path: String): String

@Tool("listFiles")
fun listFiles(directory: String): String

@Tool("codeSearch")
fun codeSearch(keyword: String, directory: String): String
```

### 2. 실시간 탐색 시각화 (EventHandler)

```
🔍 listFiles 탐색 중...
🔍 readFile: src/payment/PaymentService.kt
🔍 readFile: src/payment/RetryPolicy.kt
✅ 분석 완료
```

### 3. 프로젝트 메모리 (Project Memory)

- `DEVPILOT.md` 파일에 팀 컨벤션·설계 결정 누적
- `/memory add [내용]` 명령으로 수동 등록
- 질문 응답 시 자동으로 컨텍스트에 포함

```
예시 항목:
- User 엔티티 직접 삭제 금지, UserService.deactivate() 사용 필수
- 결제 재시도는 최대 3회, RetryPolicy.kt 참조
- 이 프로젝트는 Spring Boot 3.2, Kotlin 1.9 사용
```

### 4. CLI 명령어

```
devpilot "결제 실패 시 어떻게 처리돼?"   — 코드 탐색 후 답변
devpilot /memory add [내용]              — 프로젝트 메모리 등록
devpilot /memory list                   — 등록된 메모리 목록
devpilot /clear                         — 세션 초기화
devpilot /help                          — 도움말
```

---

## 사용 예시

```
$ devpilot "User 엔티티가 어디 있고 어떤 관계야?"

🔍 listFiles: src/domain/
🔍 readFile: src/domain/User.kt
🔍 readFile: src/domain/Order.kt
✅ 분석 완료

User 엔티티는 src/domain/User.kt에 정의되어 있습니다.
Order와 1:N 관계이며, 소프트 딜리트 방식을 사용합니다.
⚠️ 프로젝트 규칙: 직접 삭제 금지, UserService.deactivate() 사용 필수
```

---

## 프로젝트 구조 (예정)

```
DevPilot/
├── src/main/kotlin/devpilot/
│   ├── Main.kt
│   ├── agent/
│   │   └── DevPilotAgent.kt
│   ├── tools/
│   │   ├── ReadFileTool.kt
│   │   ├── ListFilesTool.kt
│   │   └── CodeSearchTool.kt
│   ├── memory/
│   │   └── ProjectMemoryStorage.kt
│   └── command/
│       ├── CommandRegistry.kt
│       ├── MemoryCommand.kt
│       └── HelpCommand.kt
└── build.gradle.kts
```

---

## 배포

```bash
# 빌드
./gradlew installDist

# 전역 등록
ln -sf $(pwd)/build/install/devpilot/bin/devpilot ~/.local/bin/devpilot

# 실행
GOOGLE_API_KEY=your-key devpilot "질문 입력"
```

---

## 단계별 로드맵

| 단계 | 내용 |
|---|---|
| **MVP** | ReAct Agent + 도구 3종 + Project Memory + CLI 배포 |
| **v2** | JSONL 세션 기억 + 슬라이딩 윈도우 압축 |
| **v3** | A2A 멀티에이전트 (코드/아키텍처/프로세스 분리) |
| **v4** | RAG (Confluence/Notion 임베딩) + Slack Bot 연동 |
