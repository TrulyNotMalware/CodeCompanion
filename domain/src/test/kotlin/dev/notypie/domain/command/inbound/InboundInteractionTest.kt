package dev.notypie.domain.command.inbound

import dev.notypie.domain.TEST_SLACK_RESPONSE_URL
import dev.notypie.domain.command.approveAction
import dev.notypie.domain.command.createInboundInteraction
import dev.notypie.domain.command.passiveAction
import dev.notypie.domain.command.rejectAction
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class InboundInteractionTest :
    BehaviorSpec({

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

        given("an interaction carrying its reply handle") {
            val interaction = createInboundInteraction(reply = ReplyHandle(raw = TEST_SLACK_RESPONSE_URL))

            `when`("it is printed") {
                val printed = interaction.toString()

                then("the reply handle prints redacted instead of the URL") {
                    printed shouldNotContain "hooks.slack.com"
                    printed shouldContain "reply=ReplyHandle(redacted)"
                    "${interaction.reply}" shouldBe "ReplyHandle(redacted)"
                }
            }
        }
    })
