package dev.notypie.domain.command.inbound

import dev.notypie.domain.command.approveAction
import dev.notypie.domain.command.createInboundInteraction
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.inboundField
import dev.notypie.domain.command.passiveAction
import dev.notypie.domain.command.rejectAction
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class InboundInteractionTest :
    BehaviorSpec({

        given("isComplete") {
            `when`("primary action is selected and all fields are selected") {
                val interaction =
                    createInboundInteraction(
                        detailType = CommandDetailType.APPROVAL_REQUEST,
                        action = approveAction(isSelected = true),
                        form =
                            listOf(
                                inboundField(kind = InboundFieldKind.USERS, rawValue = "U001", isSelected = true),
                            ),
                    )

                then("should return true") {
                    interaction.isComplete() shouldBe true
                }
            }

            `when`("primary action is selected but a field is not selected") {
                val interaction =
                    createInboundInteraction(
                        detailType = CommandDetailType.APPROVAL_REQUEST,
                        action = approveAction(isSelected = true),
                        form = listOf(inboundField(kind = InboundFieldKind.USERS, isSelected = false)),
                    )

                then("should return false") {
                    interaction.isComplete() shouldBe false
                }
            }

            `when`("current action is not primary") {
                val interaction =
                    createInboundInteraction(
                        detailType = CommandDetailType.APPROVAL_REQUEST,
                        action = passiveAction(isSelected = true),
                        form = emptyList(),
                    )

                then("should return false") {
                    interaction.isComplete() shouldBe false
                }
            }

            `when`("current action is not selected") {
                val interaction =
                    createInboundInteraction(
                        detailType = CommandDetailType.APPROVAL_REQUEST,
                        action = approveAction(isSelected = false),
                        form = emptyList(),
                    )

                then("should return false") {
                    interaction.isComplete() shouldBe false
                }
            }

            `when`("an unselected TOGGLE field exists") {
                val interaction =
                    createInboundInteraction(
                        detailType = CommandDetailType.APPROVAL_REQUEST,
                        action = approveAction(isSelected = true),
                        form = listOf(inboundField(kind = InboundFieldKind.TOGGLE, isSelected = false)),
                    )

                then("should return true because TOGGLE is always considered complete") {
                    interaction.isComplete() shouldBe true
                }
            }

            `when`("an unselected TEXT field exists") {
                val interaction =
                    createInboundInteraction(
                        detailType = CommandDetailType.APPROVAL_REQUEST,
                        action = approveAction(isSelected = true),
                        form = listOf(inboundField(kind = InboundFieldKind.TEXT, isSelected = false)),
                    )

                then("should return true because TEXT is always considered complete") {
                    interaction.isComplete() shouldBe true
                }
            }
        }

        given("isPrimary") {
            `when`("action role is APPROVE") {
                val interaction = createInboundInteraction(action = approveAction(isSelected = true))

                then("should return true") {
                    interaction.isPrimary() shouldBe true
                }
            }

            `when`("action role is PASSIVE") {
                val interaction = createInboundInteraction(action = passiveAction())

                then("should return false") {
                    interaction.isPrimary() shouldBe false
                }
            }

            `when`("action role is REJECT") {
                val interaction = createInboundInteraction(action = rejectAction(isSelected = true))

                then("should return true — REJECT still triggers its own event") {
                    interaction.isPrimary() shouldBe true
                }
            }
        }

        given("isCanceled") {
            `when`("action role is REJECT") {
                val interaction = createInboundInteraction(action = rejectAction(isSelected = true))

                then("should return true") {
                    interaction.isCanceled() shouldBe true
                }
            }

            `when`("action role is APPROVE") {
                val interaction = createInboundInteraction(action = approveAction(isSelected = true))

                then("should return false") {
                    interaction.isCanceled() shouldBe false
                }
            }
        }
    })
