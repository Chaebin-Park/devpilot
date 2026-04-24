package devpilot.command

import devpilot.config.DevPilotConfig
import devpilot.config.SetupWizard
import java.io.BufferedReader

class SetupCommand(
    private val wizard: SetupWizard,
    private val reader: BufferedReader,
    private val onConfigChanged: (DevPilotConfig) -> Unit,
) : Command {
    override val name = "setup"
    override val aliases = emptyList<String>()
    override val description = "초기 설정 마법사를 다시 실행합니다"
    override val usage = "/setup"

    override suspend fun execute(args: List<String>): CommandResult {
        val config = wizard.run(reader)
        onConfigChanged(config)
        return CommandResult.Success(
            "설정 적용 완료 — 전략: ${config.strategy.strategy.name.lowercase()}, Primary: ${config.strategy.primary.modelId}"
        )
    }
}
