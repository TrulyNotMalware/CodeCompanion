package dev.notypie.domain.command.authorization

enum class UserRole(
    private val permissions: Set<CommandPermission>,
) {
    USER(setOf(CommandPermission.BASIC)),

    AI_USER(setOf(CommandPermission.BASIC, CommandPermission.AI)),

    DEVELOPER(setOf(CommandPermission.BASIC, CommandPermission.AI, CommandPermission.OPERATIONS)),

    ADMIN(CommandPermission.entries.toSet()),
    ;

    fun grants(permission: CommandPermission): Boolean = permission in permissions
}
