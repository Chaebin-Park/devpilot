package devpilot.command

import devpilot.agent.DevPilotAgent

class ModeCommand(private val agent: DevPilotAgent?) : Command {
    override val name = "mode"
    override val aliases = emptyList<String>()
    override val description = "라우팅 모드를 전환합니다 (primary / fallback / auto)"
    override val usage = "/mode [primary|fallback|auto|status]"

    override suspend fun execute(args: List<String>): CommandResult {
        if (agent == null) return CommandResult.Error("CLI 모드에서만 사용 가능합니다.")
        return when (args.firstOrNull()?.lowercase()) {
            "primary" -> {
                agent.forcePrimary()
                CommandResult.Success("Primary 모드로 전환됨 — 다음 질문부터 Primary 모델을 우선 사용합니다.")
            }
            "fallback" -> {
                agent.forceFallback()
                CommandResult.Success("Fallback 모드로 전환됨 — Primary를 건너뛰고 Fallback 모델을 사용합니다.")
            }
            "auto", "reset" -> {
                agent.forcePrimary()
                CommandResult.Success("Auto 모드로 복귀 — Primary 재시도 활성화.")
            }
            "status", null -> {
                val model = agent.lastUsedModel
                CommandResult.Success("마지막 사용 모델: $model\n/mode primary | /mode fallback | /mode auto 로 전환하세요.")
            }
            else -> CommandResult.Error("알 수 없는 모드: '${args[0]}'\n사용법: $usage")
        }
    }
}
