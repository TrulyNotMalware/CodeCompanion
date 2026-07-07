package dev.notypie.domain.command.authorization

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class UserRoleTest :
    BehaviorSpec({
        given("the role hierarchy") {
            `when`("checking each role's grants") {
                then("USER holds BASIC only") {
                    UserRole.USER.grants(permission = CommandPermission.BASIC) shouldBe true
                    UserRole.USER.grants(permission = CommandPermission.AI) shouldBe false
                    UserRole.USER.grants(permission = CommandPermission.OPERATIONS) shouldBe false
                }

                then("AI_USER adds the AI lane but not operations") {
                    UserRole.AI_USER.grants(permission = CommandPermission.BASIC) shouldBe true
                    UserRole.AI_USER.grants(permission = CommandPermission.AI) shouldBe true
                    UserRole.AI_USER.grants(permission = CommandPermission.OPERATIONS) shouldBe false
                }

                then("DEVELOPER adds operations but not administration") {
                    UserRole.DEVELOPER.grants(permission = CommandPermission.BASIC) shouldBe true
                    UserRole.DEVELOPER.grants(permission = CommandPermission.AI) shouldBe true
                    UserRole.DEVELOPER.grants(permission = CommandPermission.OPERATIONS) shouldBe true
                    UserRole.DEVELOPER.grants(permission = CommandPermission.ADMINISTRATION) shouldBe false
                }

                then("ADMIN holds every permission, including ones added later") {
                    CommandPermission.entries.forEach { permission ->
                        UserRole.ADMIN.grants(permission = permission) shouldBe true
                    }
                }
            }
        }
    })
