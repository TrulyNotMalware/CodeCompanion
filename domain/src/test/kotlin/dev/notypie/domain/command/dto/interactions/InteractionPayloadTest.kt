package dev.notypie.domain.command.dto.interactions

import dev.notypie.domain.command.SlackCommandType
import dev.notypie.domain.command.createInteractionPayloadInput
import dev.notypie.domain.command.entity.CommandDetailType
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class InteractionPayloadTest :
    BehaviorSpec({

        given("isCompleted") {
            `when`("primary action is selected and all states are selected") {
                val payload =
                    createInteractionPayloadInput(
                        commandDetailType = CommandDetailType.APPROVAL_FORM,
                        currentAction =
                            States(
                                type = ActionElementTypes.APPLY_BUTTON,
                                isSelected = true,
                                selectedValue = "approve",
                            ),
                        states =
                            listOf(
                                States(
                                    type = ActionElementTypes.MULTI_USERS_SELECT,
                                    isSelected = true,
                                    selectedValue = "U001",
                                ),
                            ),
                        idempotencyKey = UUID.randomUUID(),
                    )

                then("should return true") {
                    payload.isCompleted() shouldBe true
                }
            }

            `when`("primary action is selected but a state is not selected") {
                val payload =
                    createInteractionPayloadInput(
                        commandDetailType = CommandDetailType.APPROVAL_FORM,
                        currentAction =
                            States(type = ActionElementTypes.APPLY_BUTTON, isSelected = true),
                        states =
                            listOf(
                                States(type = ActionElementTypes.MULTI_USERS_SELECT, isSelected = false),
                            ),
                        idempotencyKey = UUID.randomUUID(),
                    )

                then("should return false") {
                    payload.isCompleted() shouldBe false
                }
            }

            `when`("current action is not primary") {
                val payload =
                    createInteractionPayloadInput(
                        commandDetailType = CommandDetailType.APPROVAL_FORM,
                        currentAction =
                            States(type = ActionElementTypes.MULTI_STATIC_SELECT, isSelected = true),
                        states = emptyList(),
                        idempotencyKey = UUID.randomUUID(),
                    )

                then("should return false") {
                    payload.isCompleted() shouldBe false
                }
            }

            `when`("current action is not selected") {
                val payload =
                    createInteractionPayloadInput(
                        commandDetailType = CommandDetailType.APPROVAL_FORM,
                        currentAction =
                            States(type = ActionElementTypes.APPLY_BUTTON, isSelected = false),
                        states = emptyList(),
                        idempotencyKey = UUID.randomUUID(),
                    )

                then("should return false") {
                    payload.isCompleted() shouldBe false
                }
            }

            `when`("unselected CHECKBOX state exists") {
                val payload =
                    createInteractionPayloadInput(
                        commandDetailType = CommandDetailType.APPROVAL_FORM,
                        currentAction =
                            States(type = ActionElementTypes.APPLY_BUTTON, isSelected = true),
                        states =
                            listOf(
                                States(type = ActionElementTypes.CHECKBOX, isSelected = false),
                            ),
                        idempotencyKey = UUID.randomUUID(),
                    )

                then("should return true because CHECKBOX is always considered completed") {
                    payload.isCompleted() shouldBe true
                }
            }

            `when`("unselected PLAIN_TEXT_INPUT state exists") {
                val payload =
                    createInteractionPayloadInput(
                        commandDetailType = CommandDetailType.APPROVAL_FORM,
                        currentAction =
                            States(type = ActionElementTypes.APPLY_BUTTON, isSelected = true),
                        states =
                            listOf(
                                States(type = ActionElementTypes.PLAIN_TEXT_INPUT, isSelected = false),
                            ),
                        idempotencyKey = UUID.randomUUID(),
                    )

                then("should return true because PLAIN_TEXT_INPUT is always considered completed") {
                    payload.isCompleted() shouldBe true
                }
            }
        }

        given("isPrimary") {
            `when`("current action is APPLY_BUTTON") {
                val payload =
                    createInteractionPayloadInput(
                        commandDetailType = CommandDetailType.NOTHING,
                        currentAction = States(type = ActionElementTypes.APPLY_BUTTON, isSelected = true),
                        states = emptyList(),
                        idempotencyKey = UUID.randomUUID(),
                    )

                then("should return true") {
                    payload.isPrimary() shouldBe true
                }
            }

            `when`("current action is MULTI_STATIC_SELECT") {
                val payload =
                    createInteractionPayloadInput(
                        commandDetailType = CommandDetailType.NOTHING,
                        currentAction = States(type = ActionElementTypes.MULTI_STATIC_SELECT),
                        states = emptyList(),
                        idempotencyKey = UUID.randomUUID(),
                    )

                then("should return false") {
                    payload.isPrimary() shouldBe false
                }
            }
        }

        given("isCanceled") {
            `when`("current action is REJECT_BUTTON") {
                val payload =
                    createInteractionPayloadInput(
                        commandDetailType = CommandDetailType.NOTHING,
                        currentAction = States(type = ActionElementTypes.REJECT_BUTTON, isSelected = true),
                        states = emptyList(),
                        idempotencyKey = UUID.randomUUID(),
                    )

                then("should return true") {
                    payload.isCanceled() shouldBe true
                }
            }

            `when`("current action is APPLY_BUTTON") {
                val payload =
                    createInteractionPayloadInput(
                        commandDetailType = CommandDetailType.NOTHING,
                        currentAction = States(type = ActionElementTypes.APPLY_BUTTON, isSelected = true),
                        states = emptyList(),
                        idempotencyKey = UUID.randomUUID(),
                    )

                then("should return false") {
                    payload.isCanceled() shouldBe false
                }
            }
        }

        given("toSlackCommandData") {
            val payload =
                createInteractionPayloadInput(
                    commandDetailType = CommandDetailType.APPROVAL_FORM,
                    currentAction = States(type = ActionElementTypes.APPLY_BUTTON, isSelected = true),
                    states = emptyList(),
                    idempotencyKey = UUID.randomUUID(),
                )

            `when`("converting to SlackCommandData") {
                val commandData = payload.toSlackCommandData()

                then("should map fields correctly") {
                    commandData.appId shouldBe payload.apiAppId
                    commandData.appToken shouldBe payload.token
                    commandData.publisherId shouldBe payload.user.id
                    commandData.publisherName shouldBe payload.user.name
                    commandData.channel shouldBe payload.channel.id
                    commandData.channelName shouldBe payload.channel.name
                    commandData.slackCommandType shouldBe SlackCommandType.INTERACTION_RESPONSE
                }

                then("body should be the interaction payload itself") {
                    commandData.body shouldBe payload
                }
            }
        }
    })
