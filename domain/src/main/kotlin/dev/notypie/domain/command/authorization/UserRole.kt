package dev.notypie.domain.command.authorization

/**
 * Role granted to a workspace member, resolved per actor before command routing. Roles form a
 * strict hierarchy of [CommandPermission] sets; an actor with no explicit grant is a [USER].
 */
enum class UserRole(
    private val permissions: Set<CommandPermission>,
) {
    /** Default role: forms, modals, slash commands and `help` only. */
    USER(setOf(CommandPermission.BASIC)),

    /** [USER] plus the AI assistant (`ask`). */
    AI_USER(setOf(CommandPermission.BASIC, CommandPermission.AI)),

    /** [AI_USER] plus operational commands (`status`, `notice`). */
    DEVELOPER(setOf(CommandPermission.BASIC, CommandPermission.AI, CommandPermission.OPERATIONS)),

    /** Owner role: every permission, including any added later. */
    ADMIN(CommandPermission.entries.toSet()),
    ;

    fun grants(permission: CommandPermission): Boolean = permission in permissions
}
