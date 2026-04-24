package devpilot.command

class ClearCommand : Command {
    override val name = "clear"
    override val description = "현재 세션을 초기화합니다"

    override suspend fun execute(args: List<String>): CommandResult = CommandResult.ClearSession
}
