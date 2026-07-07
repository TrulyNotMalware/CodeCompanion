package dev.notypie.application.service.command

import dev.notypie.application.configurations.AppConfig
import dev.notypie.domain.command.authorization.UserRole
import dev.notypie.repository.authorization.UserCommandRoleRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

class CommandRoleResolverTest :
    BehaviorSpec({
        val bootstrapAdminId = "U_OWNER"
        val grantedDeveloperId = "U_DEV"
        val unknownUserId = "U_NOBODY"

        val roleRepository = mockk<UserCommandRoleRepository>()
        every { roleRepository.findRole(userId = grantedDeveloperId) } returns UserRole.DEVELOPER
        every { roleRepository.findRole(userId = unknownUserId) } returns null

        val resolver =
            CommandRoleResolver(
                appConfig =
                    AppConfig(
                        authorization = AppConfig.Authorization(bootstrapAdmins = listOf(bootstrapAdminId)),
                    ),
                userCommandRoleRepository = roleRepository,
            )

        given("resolve") {
            `when`("the actor is a configured bootstrap admin") {
                then("ADMIN is returned without a DB row") {
                    resolver.resolve(userId = bootstrapAdminId) shouldBe UserRole.ADMIN
                }
            }

            `when`("the actor has a DB grant") {
                then("the granted role is returned") {
                    resolver.resolve(userId = grantedDeveloperId) shouldBe UserRole.DEVELOPER
                }
            }

            `when`("the actor has no grant") {
                then("the USER default is returned") {
                    resolver.resolve(userId = unknownUserId) shouldBe UserRole.USER
                }
            }
        }
    })
