package devpilot.config

import kotlinx.serialization.Serializable

@Serializable
enum class RoutingStrategy {
    AUTO,     // Router가 질문 유형에 맞는 specialist를 primary로 사용
    PRIMARY,  // 항상 primary 모델 사용, 실패 시 Ollama fallback
    MANUAL,   // /model 커맨드로 지정한 모델만 사용
}
