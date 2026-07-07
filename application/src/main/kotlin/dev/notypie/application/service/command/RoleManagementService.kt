package dev.notypie.application.service.command

import dev.notypie.domain.command.authorization.UserRole
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.entity.event.RoleManageAction
import dev.notypie.domain.command.entity.event.RoleManagePayload
import dev.notypie.domain.command.entity.event.RoleManageRequestEvent
import dev.notypie.domain.command.entity.event.publishOne
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.repository.authorization.UserCommandRoleRepository
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Executes admin-only role management (`@bot grant|revoke|roles`). The parser has already
 * gated the actor as ADMIN and validated the mention shape, so this listener only applies
 * the change and confirms it on the originating channel. Bootstrap admins are config-managed
 * and therefore immutable from chat.
 */
@Service
class RoleManagementService(
    private val userCommandRoleRepository: UserCommandRoleRepository,
    private val commandRoleResolver: CommandRoleResolver,
    private val outboundStager: OutboundMessageStager,
    private val eventPublisher: EventPublisher,
) {
    companion object {
        private const val RESPONSE_HEADLINE = "CodeCompanion — role management"
    }

    // The staged reply is persisted by a BEFORE_COMMIT listener, so the role write and the
    // confirmation must share one transaction even when the event is published outside the
    // mention handler's boundary.
    @Transactional
    @EventListener
    fun handleRoleManage(event: RoleManageRequestEvent) {
        val payload = event.payload
        val text =
            when (payload.action) {
                RoleManageAction.GRANT -> grant(payload = payload)
                RoleManageAction.REVOKE -> revoke(payload = payload)
                RoleManageAction.LIST -> renderGrants()
            }

        val staged =
            checkNotNull(
                outboundStager.stage(
                    message =
                        OutboundMessage.ChannelMessage(
                            target = ConversationTarget(id = payload.responseBasicInfo.channel),
                            content = MessageContent.Text(headline = RESPONSE_HEADLINE, markdown = text),
                        ),
                    basicInfo = payload.responseBasicInfo,
                ),
            ) { "Role management reply failed to stage an outbox event: action=${payload.action}" }
        eventPublisher.publishOne(event = staged)
    }

    private fun grant(payload: RoleManagePayload): String {
        val targetUserId = checkNotNull(payload.targetUserId) { "GRANT requires a target user" }
        val role = checkNotNull(payload.role) { "GRANT requires a role" }
        if (commandRoleResolver.isBootstrapAdmin(userId = targetUserId)) {
            return bootstrapImmutableMessage(targetUserId = targetUserId)
        }
        userCommandRoleRepository.saveRole(userId = targetUserId, role = role)
        return "Granted `${role.name.lowercase()}` to <@$targetUserId>."
    }

    private fun revoke(payload: RoleManagePayload): String {
        val targetUserId = checkNotNull(payload.targetUserId) { "REVOKE requires a target user" }
        if (commandRoleResolver.isBootstrapAdmin(userId = targetUserId)) {
            return bootstrapImmutableMessage(targetUserId = targetUserId)
        }
        return if (userCommandRoleRepository.deleteRole(userId = targetUserId)) {
            "Revoked the role grant of <@$targetUserId>. They fall back to `${UserRole.USER.name.lowercase()}`."
        } else {
            "<@$targetUserId> has no role grant."
        }
    }

    private fun renderGrants(): String {
        val bootstrapLines =
            commandRoleResolver.bootstrapAdmins
                .sorted()
                .map { userId -> "• <@$userId> — `${UserRole.ADMIN.name.lowercase()}` (bootstrap, config-managed)" }
        val grantLines =
            userCommandRoleRepository
                .findAllGrants()
                .entries
                .sortedWith(compareBy({ it.value.name }, { it.key }))
                .map { (userId, role) -> "• <@$userId> — `${role.name.lowercase()}`" }
        val lines = bootstrapLines + grantLines
        if (lines.isEmpty()) return "No role grants. Everyone defaults to `${UserRole.USER.name.lowercase()}`."
        return lines.joinToString(separator = "\n")
    }

    private fun bootstrapImmutableMessage(targetUserId: String): String =
        "<@$targetUserId> is a bootstrap admin managed by configuration; their role cannot be changed here."
}
