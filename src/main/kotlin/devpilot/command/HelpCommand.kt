package devpilot.command

class HelpCommand(private val getCommands: () -> List<Command>) : Command {
    override val name = "help"
    override val description = "사용 가능한 명령어를 표시합니다"

    override suspend fun execute(args: List<String>): CommandResult {
        val sb = StringBuilder("사용 가능한 명령어:\n\n")
        getCommands().forEach { cmd ->
            sb.appendLine("  ${cmd.usage.padEnd(35)} ${cmd.description}")
        }
        sb.appendLine("  /exit                               프로그램 종료")
        return CommandResult.Success(sb.toString().trimEnd())
    }
}
