# DevPilot

AI 기반 코드베이스 온보딩 어시스턴트. 낯선 코드베이스에 대해 자연어로 질문하면 실제 소스 코드를 근거로 답변을 제공합니다.

[Koog](https://github.com/JetBrains/koog) 프레임워크 기반으로 구현되었습니다.

---

## 주요 기능

- 코드베이스에 대한 자연어 질의 (파일 읽기, 코드 검색, 디렉터리 탐색)
- 실시간 스트리밍을 지원하는 웹 UI (다중 프로젝트 탭)
- 터미널 기반 CLI 모드
- Google Gemini, Anthropic Claude, OpenAI, Ollama(로컬 모델) 지원
- Primary 모델과 선택적 Fallback 모델 구성
- `DEVPILOT.md`를 통한 프로젝트 메모리 (세션 간 유지)

---

## 요구사항

**Docker 모드**
- Docker Desktop 4.x 이상
- Docker에 최소 8 GB RAM 할당 (대형 로컬 모델 사용 시 16 GB 권장)

**JAR 모드**
- Java 17 이상
- Ollama(로컬 모델 사용 시): [https://ollama.com](https://ollama.com)

---

## 설치

### 방법 1: Docker Compose (권장)

Ollama 실행, 기본 모델(`llama3.2`) 다운로드, 웹 UI 시작을 한 번에 처리합니다.

```bash
curl -L https://raw.githubusercontent.com/Chaebin-Park/devpilot/main/docker-compose.yml -o docker-compose.yml
docker-compose up -d
```

브라우저에서 `http://localhost:8080`을 열고, 파일 브라우저로 프로젝트 폴더를 추가합니다.

**환경 변수**

| 변수 | 기본값 | 설명 |
|---|---|---|
| `PORT` | `8080` | 웹 UI 포트 |
| `OLLAMA_MODEL` | `llama3.2` | 최초 실행 시 다운로드할 Ollama 모델 |
| `GOOGLE_API_KEY` | — | Google Gemini API 키 |
| `ANTHROPIC_API_KEY` | — | Anthropic Claude API 키 |
| `OPENAI_API_KEY` | — | OpenAI API 키 |

`docker-compose.yml`과 같은 디렉터리에 `.env` 파일을 생성하거나 인라인으로 지정합니다.

```bash
GOOGLE_API_KEY=AIza... docker-compose up -d
```

**업데이트**

```bash
docker-compose down
docker-compose pull
docker-compose up -d
```

---

### 방법 2: 소스 빌드

JDK 17+와 Git이 필요합니다.

```bash
git clone https://github.com/Chaebin-Park/devpilot.git
cd devpilot
./gradlew shadowJar
```

빌드 결과물은 `build/libs/devpilot.jar`에 생성됩니다.

---

## 사용법

### 웹 모드

```bash
java -jar build/libs/devpilot.jar --web

# 포트 지정
java -jar build/libs/devpilot.jar --web --port=9090
```

`http://localhost:8080`을 열고, 헤더의 `+` 버튼으로 프로젝트 폴더를 추가합니다. 여러 프로젝트를 탭으로 전환할 수 있습니다.

### CLI 모드

```bash
# 현재 디렉터리 분석
java -jar build/libs/devpilot.jar

# 특정 디렉터리 분석
java -jar build/libs/devpilot.jar -d=/path/to/project

# 여러 디렉터리 동시 분석
java -jar build/libs/devpilot.jar -d=/path/to/project-a -d=/path/to/project-b
```

**CLI 명령어**

| 명령어 | 설명 |
|---|---|
| `/help` | 전체 명령어 목록 |
| `/config show` | 현재 설정 확인 |
| `/model <id>` | 현재 세션 모델 오버라이드 |
| `/mode primary \| fallback \| auto` | 라우팅 모드 전환 |
| `/memory list` | 프로젝트 메모리 목록 |
| `/memory add <내용>` | 메모리 항목 추가 |
| `/memory delete <번호>` | 메모리 항목 삭제 |
| `/setup` | 설정 마법사 재실행 |
| `/clear` | 대화 이력 초기화 |
| `/exit` | 종료 |

---

## 모델 설정

### 자동 감지

최초 실행 시 다음 순서로 사용 가능한 모델을 탐지합니다.

1. 로컬에서 실행 중인 Ollama (첫 번째 모델 사용)
2. 환경 변수 또는 `~/.devpilot/.env`의 API 키
3. 대화형 설정 마법사 (CLI 모드 전용)

### 수동 설정

**웹 UI**: 헤더의 설정 아이콘을 클릭하면 Primary 및 Fallback 모델을 선택할 수 있습니다. 설치된 Ollama 모델과 등록된 클라우드 제공자가 자동으로 표시됩니다.

**환경 변수 / `.env` 파일**

`~/.devpilot/.env` 또는 작업 디렉터리의 `.env`에 작성합니다.

```
GOOGLE_API_KEY=AIza...
ANTHROPIC_API_KEY=sk-ant-...
OPENAI_API_KEY=sk-...
```

### 지원 모델

| 제공자 | 모델 ID 예시 |
|---|---|
| Google Gemini | `gemini-2.5-flash`, `gemini-2.5-pro` |
| Anthropic Claude | `claude-haiku-4-5`, `claude-sonnet-4-6`, `claude-opus-4` |
| OpenAI | `gpt-4o`, `gpt-4o-mini` |
| Ollama | `ollama list`에 표시되는 모든 모델 (예: `llama3.2`, `qwen2.5:14b`) |

### 로컬 모델 메모리 요구사항

| 모델 | 최소 RAM |
|---|---|
| `llama3.2` (3B) | 4 GB |
| `qwen2.5:7b` | 8 GB |
| `qwen2.5:14b` | 16 GB |
| `gemma4:26b` | 20 GB |
| `qwen2.5:32b` | 32 GB |

---

## 로컬 모델 추가 (Ollama)

DevPilot 실행 중에도 모델을 추가로 다운로드할 수 있습니다.

```bash
# Docker 환경
docker exec -it devpilot-ollama-1 ollama pull qwen2.5:14b

# 네이티브 Ollama
ollama pull qwen2.5:14b
```

다운로드 후 설정 페이지에서 해당 모델을 선택합니다.

`docker-compose.yml`에 서비스를 추가하면 시작 시 자동으로 다운로드됩니다.

```yaml
ollama-pull-custom:
  image: ollama/ollama
  depends_on:
    ollama:
      condition: service_healthy
  environment:
    - OLLAMA_HOST=http://ollama:11434
  entrypoint: ["/bin/sh", "-c", "ollama pull qwen2.5:14b"]
  restart: "no"
```

`devpilot` 서비스의 `depends_on`에도 해당 서비스를 추가해야 합니다.

---

## 프로젝트 메모리

DevPilot은 프로젝트 루트의 `DEVPILOT.md`에 메모를 저장합니다. 저장된 메모는 시작 시 로드되어 매 요청의 컨텍스트로 활용됩니다.

```
/memory add "인증 처리는 src/auth/JwtFilter에서 담당"
/memory list
/memory delete 1
```

---

## ripgrep

코드 검색은 [ripgrep](https://github.com/BurntSushi/ripgrep)을 사용합니다. `rg`가 PATH에 없으면 `codeSearch` 기능이 비활성화되며 시작 시 경고가 출력됩니다.

```bash
# macOS
brew install ripgrep

# Debian / Ubuntu
apt-get install ripgrep
```

Docker 이미지에는 ripgrep이 기본 포함되어 있습니다.

---

## 라이선스

Apache 2.0
