package devpilot.command

interface Command {
    val name: String
    val aliases: List<String> get() = emptyList()
    val description: String
    val usage: String get() = "/$name"

    suspend fun execute(args: List<String> = emptyList()): CommandResult

    fun matches(input: String): Boolean {
        val commandName = input.removePrefix("/").split(" ").firstOrNull() ?: return false
        return commandName == name || commandName in aliases
    }
}

sealed class CommandResult {
    data class Success(val message: String = "") : CommandResult()
    data class Error(val message: String) : CommandResult()
    data object Exit : CommandResult()
    data object ClearSession : CommandResult()
}

class CommandRegistry {
    private val commands = mutableListOf<Command>()

    fun register(command: Command) { commands.add(command) }

    fun registerAll(vararg cmds: Command) { commands.addAll(cmds) }

    fun getAllCommands(): List<Command> = commands.toList()

    suspend fun execute(input: String): CommandResult? {
        val command = commands.find { it.matches(input) } ?: return null
        val args = input.removePrefix("/").split(" ").drop(1).filter { it.isNotBlank() }
        return command.execute(args)
    }
}
