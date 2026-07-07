package dev.notypie.application.service.command

import dev.notypie.application.configurations.AppConfig
import dev.notypie.domain.TEST_CHANNEL_ID
import dev.notypie.domain.command.authorization.UserRole
import dev.notypie.domain.command.createRoleManageRequestEvent
import dev.notypie.domain.command.entity.event.CommandEvent
import dev.notypie.domain.command.entity.event.EventPayload
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.entity.event.RoleManageAction
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.repository.authorization.UserCommandRoleRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify

class RoleManagementServiceTest :
    BehaviorSpec({
        val targetUserId = "U_TARGET"
        val stubStagedEvent = mockk<CommandEvent<EventPayload>>(relaxed = true)

        fun serviceWith(
            roleRepository: UserCommandRoleRepository,
            stagedMessage: CapturingSlot<OutboundMessage>,
            bootstrapAdmins: List<String> = emptyList(),
        ): Pair<RoleManagementService, EventPublisher> {
            val stager = mockk<OutboundMessageStager>()
            every { stager.stage(message = capture(stagedMessage), basicInfo = any()) } returns stubStagedEvent
            val publisher = mockk<EventPublisher>(relaxed = true)
            val service =
                RoleManagementService(
                    userCommandRoleRepository = roleRepository,
                    commandRoleResolver =
                        CommandRoleResolver(
                            appConfig =
                                AppConfig(
                                    authorization = AppConfig.Authorization(bootstrapAdmins = bootstrapAdmins),
                                ),
                            userCommandRoleRepository = roleRepository,
                        ),
                    outboundStager = stager,
                    eventPublisher = publisher,
                )
            return service to publisher
        }

        fun CapturingSlot<OutboundMessage>.markdown(): String =
            captured
                .shouldBeInstanceOf<OutboundMessage.ChannelMessage>()
                .also { it.target.id shouldBe TEST_CHANNEL_ID }
                .content
                .shouldBeInstanceOf<MessageContent.Text>()
                .markdown

        given("a GRANT event") {
            val roleRepository = mockk<UserCommandRoleRepository>(relaxed = true)
            val stagedMessage = slot<OutboundMessage>()
            val (service, publisher) = serviceWith(roleRepository = roleRepository, stagedMessage = stagedMessage)

            `when`("handled") {
                service.handleRoleManage(
                    event =
                        createRoleManageRequestEvent(
                            action = RoleManageAction.GRANT,
                            targetUserId = targetUserId,
                            role = UserRole.AI_USER,
                        ),
                )

                then("the grant is persisted") {
                    verify(exactly = 1) { roleRepository.saveRole(userId = targetUserId, role = UserRole.AI_USER) }
                }

                then("a confirmation naming the role and target is staged and published") {
                    stagedMessage.markdown() shouldBe "Granted `ai_user` to <@$targetUserId>."
                    verify(exactly = 1) { publisher.publishEvent(events = any()) }
                }
            }
        }

        given("a REVOKE event for an existing grant") {
            val roleRepository = mockk<UserCommandRoleRepository>()
            every { roleRepository.deleteRole(userId = targetUserId) } returns true
            val stagedMessage = slot<OutboundMessage>()
            val (service, _) = serviceWith(roleRepository = roleRepository, stagedMessage = stagedMessage)

            `when`("handled") {
                service.handleRoleManage(
                    event =
                        createRoleManageRequestEvent(
                            action = RoleManageAction.REVOKE,
                            targetUserId = targetUserId,
                            role = null,
                        ),
                )

                then("the reply confirms the fallback to user") {
                    stagedMessage.markdown() shouldBe
                        "Revoked the role grant of <@$targetUserId>. They fall back to `user`."
                }
            }
        }

        given("a REVOKE event without an existing grant") {
            val roleRepository = mockk<UserCommandRoleRepository>()
            every { roleRepository.deleteRole(userId = targetUserId) } returns false
            val stagedMessage = slot<OutboundMessage>()
            val (service, _) = serviceWith(roleRepository = roleRepository, stagedMessage = stagedMessage)

            `when`("handled") {
                service.handleRoleManage(
                    event =
                        createRoleManageRequestEvent(
                            action = RoleManageAction.REVOKE,
                            targetUserId = targetUserId,
                            role = null,
                        ),
                )

                then("the reply says there was nothing to revoke") {
                    stagedMessage.markdown() shouldBe "<@$targetUserId> has no role grant."
                }
            }
        }

        given("a LIST event with grants") {
            val roleRepository = mockk<UserCommandRoleRepository>()
            every { roleRepository.findAllGrants() } returns
                mapOf(
                    "U_DEV" to UserRole.DEVELOPER,
                    "U_ADMIN" to UserRole.ADMIN,
                )
            val stagedMessage = slot<OutboundMessage>()
            val (service, _) = serviceWith(roleRepository = roleRepository, stagedMessage = stagedMessage)

            `when`("handled") {
                service.handleRoleManage(
                    event =
                        createRoleManageRequestEvent(
                            action = RoleManageAction.LIST,
                            targetUserId = null,
                            role = null,
                        ),
                )

                then("every grant is rendered as a mention with its role") {
                    val markdown = stagedMessage.markdown()
                    markdown shouldContain "• <@U_ADMIN> — `admin`"
                    markdown shouldContain "• <@U_DEV> — `developer`"
                }
            }
        }

        given("a LIST event without grants") {
            val roleRepository = mockk<UserCommandRoleRepository>()
            every { roleRepository.findAllGrants() } returns emptyMap()
            val stagedMessage = slot<OutboundMessage>()
            val (service, _) = serviceWith(roleRepository = roleRepository, stagedMessage = stagedMessage)

            `when`("handled") {
                service.handleRoleManage(
                    event =
                        createRoleManageRequestEvent(
                            action = RoleManageAction.LIST,
                            targetUserId = null,
                            role = null,
                        ),
                )

                then("the reply states the user default") {
                    stagedMessage.markdown() shouldBe "No role grants. Everyone defaults to `user`."
                }
            }
        }

        given("a bootstrap admin target") {
            val bootstrapAdminId = "U_BOOTSTRAP"

            `when`("a GRANT event names the bootstrap admin") {
                val roleRepository = mockk<UserCommandRoleRepository>(relaxed = true)
                val stagedMessage = slot<OutboundMessage>()
                val (service, _) =
                    serviceWith(
                        roleRepository = roleRepository,
                        stagedMessage = stagedMessage,
                        bootstrapAdmins = listOf(bootstrapAdminId),
                    )

                service.handleRoleManage(
                    event =
                        createRoleManageRequestEvent(
                            action = RoleManageAction.GRANT,
                            targetUserId = bootstrapAdminId,
                            role = UserRole.DEVELOPER,
                        ),
                )

                then("nothing is written and the reply explains config ownership") {
                    verify(exactly = 0) { roleRepository.saveRole(userId = any(), role = any()) }
                    stagedMessage.markdown() shouldBe
                        "<@$bootstrapAdminId> is a bootstrap admin managed by configuration; " +
                        "their role cannot be changed here."
                }
            }

            `when`("a REVOKE event names the bootstrap admin") {
                val roleRepository = mockk<UserCommandRoleRepository>(relaxed = true)
                val stagedMessage = slot<OutboundMessage>()
                val (service, _) =
                    serviceWith(
                        roleRepository = roleRepository,
                        stagedMessage = stagedMessage,
                        bootstrapAdmins = listOf(bootstrapAdminId),
                    )

                service.handleRoleManage(
                    event =
                        createRoleManageRequestEvent(
                            action = RoleManageAction.REVOKE,
                            targetUserId = bootstrapAdminId,
                            role = null,
                        ),
                )

                then("nothing is deleted and the reply explains config ownership") {
                    verify(exactly = 0) { roleRepository.deleteRole(userId = any()) }
                    stagedMessage.markdown() shouldBe
                        "<@$bootstrapAdminId> is a bootstrap admin managed by configuration; " +
                        "their role cannot be changed here."
                }
            }

            `when`("a LIST event runs with a bootstrap admin configured") {
                val roleRepository = mockk<UserCommandRoleRepository>()
                every { roleRepository.findAllGrants() } returns mapOf("U_DEV" to UserRole.DEVELOPER)
                val stagedMessage = slot<OutboundMessage>()
                val (service, _) =
                    serviceWith(
                        roleRepository = roleRepository,
                        stagedMessage = stagedMessage,
                        bootstrapAdmins = listOf(bootstrapAdminId),
                    )

                service.handleRoleManage(
                    event =
                        createRoleManageRequestEvent(
                            action = RoleManageAction.LIST,
                            targetUserId = null,
                            role = null,
                        ),
                )

                then("the bootstrap admin appears marked as config-managed alongside DB grants") {
                    val markdown = stagedMessage.markdown()
                    markdown shouldContain "• <@$bootstrapAdminId> — `admin` (bootstrap, config-managed)"
                    markdown shouldContain "• <@U_DEV> — `developer`"
                }
            }
        }
    })
