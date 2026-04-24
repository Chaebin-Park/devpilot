package devpilot.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import devpilot.util.resolveFilePath
import devpilot.util.walkDirectory

class ListFilesTool(private val targetDir: String) : ToolSet {
    @Tool("listFiles")
    @LLMDescription("디렉터리를 재귀적으로 탐색하여 파일 목록을 반환합니다. 프로젝트 구조 파악 시 먼저 사용하세요. maxDepth로 깊이를 제한할 수 있습니다 (기본 5).")
    fun listFiles(
        @LLMDescription("탐색할 디렉터리 경로") path: String,
        @LLMDescription("탐색 깊이 제한 (기본값: 5, 최대: 10). 모노레포처럼 구조가 깊은 경우 줄이세요.") maxDepth: Int = 5,
    ): String {
        require(path.isNotBlank()) { "디렉터리 경로가 비어있습니다." }
        val basePath = resolveFilePath(path, targetDir)
        if (!basePath.exists()) return "오류: 경로를 찾을 수 없습니다: $path"
        if (!basePath.isDirectory) return "오류: 경로가 디렉터리가 아닙니다: $path"
        if (!basePath.canRead()) return "오류: 디렉터리 읽기 권한이 없습니다: $path"
        val files = walkDirectory(basePath, basePath, maxDepth.coerceIn(1, 10))
        return if (files.isEmpty()) {
            "디렉터리에서 파일을 찾을 수 없습니다: $path"
        } else {
            "Found ${files.size} items:\n" + files.sorted().joinToString("\n")
        }
    }
}
