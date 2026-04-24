package devpilot.agent

class Router {
    fun classify(question: String): QuestionType {
        val q = question.lowercase()
        return when {
            q.contains("어디") || q.contains("찾") || q.contains("위치") || q.contains("경로") -> QuestionType.LOCATE
            q.contains("왜") || q.contains("이유") || q.contains("의도") || q.contains("설계") -> QuestionType.REASON
            q.contains("에러") || q.contains("버그") || q.contains("오류") || q.contains("실패") || q.contains("안되") -> QuestionType.DEBUG
            else -> QuestionType.ANALYZE
        }
    }
}
