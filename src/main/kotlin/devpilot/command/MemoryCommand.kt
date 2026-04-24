package devpilot.command

import devpilot.memory.ProjectMemoryStorage

class MemoryCommand(private val storage: ProjectMemoryStorage) : Command {
    override val name = "memory"
    override val aliases = listOf("mem")
    override val description = "프로젝트 메모리를 관리합니다 (팀 컨벤션, 설계 결정 등)"
    override val usage = "/memory add [내용] | /memory list | /memory delete [번호]"

    override suspend fun execute(args: List<String>): CommandResult {
        if (args.isEmpty()) return CommandResult.Error("사용법: $usage")
        return when (args[0]) {
            "add" -> {
                val content = args.drop(1).joinToString(" ")
                if (content.isBlank()) return CommandResult.Error("저장할 내용을 입력하세요.\n사용법: /memory add User 삭제 금지, UserService.deactivate() 사용")
                storage.add(content)
                CommandResult.Success("저장됨: $content\n  (현재 세션에 즉시 반영하려면 /clear 를 실행하세요)")
            }
            "list" -> {
                val memories = storage.load()
                if (memories.isEmpty()) {
                    CommandResult.Success("저장된 프로젝트 메모리가 없습니다.\n/memory add [내용] 으로 추가하세요.")
                } else {
                    CommandResult.Success("프로젝트 메모리 (${memories.size}개):\n" +
                        memories.mapIndexed { i, m -> "  ${i + 1}. $m" }.joinToString("\n"))
                }
            }
            "delete" -> {
                val idx = args.getOrNull(1)?.toIntOrNull()
                    ?: return CommandResult.Error("사용법: /memory delete [번호]\n먼저 /memory list 로 번호를 확인하세요.")
                if (storage.delete(idx)) CommandResult.Success("메모리 항목 #$idx 삭제됨")
                else CommandResult.Error("잘못된 번호입니다. /memory list 로 확인하세요.")
            }
            else -> CommandResult.Error("알 수 없는 서브커맨드: '${args[0]}'\n사용법: $usage")
        }
    }
}
