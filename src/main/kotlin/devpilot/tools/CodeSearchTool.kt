package devpilot.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import devpilot.util.resolveFilePath
import java.util.concurrent.TimeUnit

class CodeSearchTool(private val targetDir: String) : ToolSet {
    @Tool("codeSearch")
    @LLMDescription(
        """
        ripgrep으로 코드에서 패턴을 검색합니다. 함수명, 클래스명, 변수명 등을 검색할 때 사용하세요.
        정규식을 지원하며, 파일 경로와 라인 번호를 함께 반환합니다.
        타임아웃: 30초 / 결과 최대 50개
        """
    )
    fun codeSearch(
        @LLMDescription("검색할 패턴 (정규식 지원, 예: 'fun pay', 'class User', 'TODO')") pattern: String,
        @LLMDescription("검색할 디렉터리 또는 파일 경로 (생략 시 분석 대상 프로젝트 전체 검색)") path: String = ".",
        @LLMDescription("대소문자 구분 여부 (기본값: false)") caseSensitive: Boolean = false,
        @LLMDescription("파일 타입 필터 (예: kt, java, py). 생략하면 전체 파일 검색") fileType: String? = null,
    ): String {
        val resolvedPath = if (path == ".") targetDir else resolveFilePath(path, targetDir).path
        val cmdArgs = buildList {
            add("rg")
            add("--line-number")
            add("--with-filename")
            add("--color=never")
            add("--no-heading")
            if (!caseSensitive) add("--ignore-case")
            if (fileType != null) { add("--type"); add(fileType) }
            add(pattern)
            add(resolvedPath)
        }

        val process = ProcessBuilder(cmdArgs)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        val finished = process.waitFor(30, TimeUnit.SECONDS)

        if (!finished) {
            process.destroyForcibly()
            return "타임아웃: 검색이 30초를 초과했습니다."
        }

        return when (process.exitValue()) {
            0 -> {
                val lines = output.trim().lines()
                if (lines.size > 50) {
                    "${lines.size}개 결과 발견 (처음 50개만 표시):\n\n${lines.take(50).joinToString("\n")}\n\n... ${lines.size - 50}개 생략"
                } else {
                    "${lines.size}개 결과 발견:\n\n${output.trim()}"
                }
            }
            1 -> "검색 결과 없음: '$pattern'"
            else -> "ripgrep 오류 (exit ${process.exitValue()}): $output"
        }
    }
}
