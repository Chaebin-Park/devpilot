package devpilot.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import devpilot.util.resolveFilePath

class ReadFileTool(private val targetDir: String) : ToolSet {
    @Tool("readFile")
    @LLMDescription("파일 내용을 읽습니다. 소스 코드, 설정 파일, 문서 등을 읽을 때 사용하세요.")
    fun readFile(
        @LLMDescription("읽을 파일의 절대 경로 또는 상대 경로") path: String
    ): String {
        require(path.isNotBlank()) { "파일 경로가 비어있습니다." }
        val file = resolveFilePath(path, targetDir)
        if (!file.exists()) return "오류: 파일을 찾을 수 없습니다: $path"
        if (!file.isFile) return "오류: 경로가 파일이 아닙니다: $path"
        if (!file.canRead()) return "오류: 파일 읽기 권한이 없습니다: $path"
        if (file.length() > MAX_FILE_SIZE) return "오류: 파일이 너무 큽니다 (${file.length() / 1024}KB). 최대 ${MAX_FILE_SIZE / 1024}KB까지 읽을 수 있습니다."
        if (isBinary(file)) return "오류: 바이너리 파일은 읽을 수 없습니다: $path"
        return file.readText()
    }

    private fun isBinary(file: java.io.File): Boolean =
        file.inputStream().use { it.readNBytes(512).any { b -> b == 0.toByte() } }

    companion object {
        private const val MAX_FILE_SIZE = 500 * 1024L  // 500KB
    }
}
