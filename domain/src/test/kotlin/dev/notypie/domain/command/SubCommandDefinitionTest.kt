package dev.notypie.domain.command

import dev.notypie.domain.command.entity.slash.MeetingSubCommandDefinition
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class SubCommandDefinitionTest :
    BehaviorSpec({

        given("SubCommandDefinition.validateArguments") {
            `when`("requiresArguments is false") {
                val definition =
                    NoSubCommands(
                        requiresArguments = false,
                        minRequiredArgs = 0,
                    )

                then("should always return true regardless of args") {
                    definition.validateArguments(subCommands = emptyList()) shouldBe true
                    definition.validateArguments(subCommands = listOf("a", "b")) shouldBe true
                }
            }

            `when`("requiresArguments is true and args meet minimum") {
                val definition =
                    NoSubCommands(
                        requiresArguments = true,
                        minRequiredArgs = 2,
                    )

                then("should return true when args >= minRequiredArgs") {
                    definition.validateArguments(subCommands = listOf("a", "b")) shouldBe true
                    definition.validateArguments(subCommands = listOf("a", "b", "c")) shouldBe true
                }
            }

            `when`("requiresArguments is true and args below minimum") {
                val definition =
                    NoSubCommands(
                        requiresArguments = true,
                        minRequiredArgs = 3,
                    )

                then("should return false when args < minRequiredArgs") {
                    definition.validateArguments(subCommands = listOf("a")) shouldBe false
                    definition.validateArguments(subCommands = emptyList()) shouldBe false
                }
            }
        }

        given("SubCommand") {
            `when`("empty") {
                val subCommand = SubCommand.empty()

                then("should have NoSubCommands definition") {
                    subCommand.subCommandDefinition.shouldBeInstanceOf<NoSubCommands>()
                    subCommand.options shouldBe emptyList()
                }

                then("should be valid") {
                    subCommand.isValid() shouldBe true
                }
            }

            `when`("created with of()") {
                val subCommand =
                    SubCommand.of(
                        definition = MeetingSubCommandDefinition.LIST,
                        options = listOf("today"),
                    )

                then("should have correct definition and options") {
                    subCommand.subCommandDefinition shouldBe MeetingSubCommandDefinition.LIST
                    subCommand.options shouldBe listOf("today")
                }

                then("should be valid") {
                    subCommand.isValid() shouldBe true
                }
            }
        }

        given("findSubCommandByIdentifier") {
            `when`("identifier matches an enum value") {
                val result = findSubCommandByIdentifier<MeetingSubCommandDefinition>(identifier = "list")

                then("should return the matching definition") {
                    result shouldBe MeetingSubCommandDefinition.LIST
                }
            }

            `when`("identifier is empty string") {
                val result = findSubCommandByIdentifier<MeetingSubCommandDefinition>(identifier = "")

                then("should return NONE") {
                    result shouldBe MeetingSubCommandDefinition.NONE
                }
            }

            `when`("identifier does not match any enum value") {
                val result = findSubCommandByIdentifier<MeetingSubCommandDefinition>(identifier = "nonexistent")

                then("should return null") {
                    result shouldBe null
                }
            }
        }
    })
