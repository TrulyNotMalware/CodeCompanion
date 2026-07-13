package dev.notypie.domain.command.context

import dev.notypie.domain.command.approveAction
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createInboundInteraction
import dev.notypie.domain.command.createIntentQueue
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.context.form.CveSubscribeSubmissionContext
import dev.notypie.domain.command.entity.context.form.CveUnsubscribeSubmissionContext
import dev.notypie.domain.command.inbound.InboundActor
import dev.notypie.domain.command.inbound.InboundSubmission
import dev.notypie.domain.command.intent.CommandIntent
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class CveSubscriptionSubmissionContextTest :
    BehaviorSpec({
        given("CveSubscribeSubmissionContext receives a subscribe view_submission") {
            val intentQueue = createIntentQueue()
            val context =
                CveSubscribeSubmissionContext(
                    commandBasicInfo = createCommandBasicInfo(),
                    intents = intentQueue,
                )
            val payload =
                createInboundInteraction(
                    detailType = CommandDetailType.CVE_SUBSCRIBE_SUBMIT,
                    action = approveAction(isSelected = true),
                    actor = InboundActor(id = "U_SUBSCRIBER"),
                    submission = InboundSubmission.CveSubscribe(topicKeys = listOf("kotlin", "spring")),
                )

            `when`("handleInteraction is invoked") {
                val result = context.handleInteraction(interaction = payload)
                val intents = intentQueue.drainSnapshot()

                then("the interaction succeeds with the subscribe detail type") {
                    result.ok shouldBe true
                    result.commandDetailType shouldBe CommandDetailType.CVE_SUBSCRIBE_SUBMIT
                }

                then("a CveSubscribe intent carries the actor id and selected keys") {
                    val subscribe = intents.filterIsInstance<CommandIntent.CveSubscribe>().single()
                    subscribe.userId shouldBe "U_SUBSCRIBER"
                    subscribe.topicKeys shouldContainExactly listOf("kotlin", "spring")
                }
            }
        }

        given("CveUnsubscribeSubmissionContext receives an unsubscribe view_submission") {
            val intentQueue = createIntentQueue()
            val context =
                CveUnsubscribeSubmissionContext(
                    commandBasicInfo = createCommandBasicInfo(),
                    intents = intentQueue,
                )
            val payload =
                createInboundInteraction(
                    detailType = CommandDetailType.CVE_UNSUBSCRIBE_SUBMIT,
                    action = approveAction(isSelected = true),
                    actor = InboundActor(id = "U_SUBSCRIBER"),
                    submission = InboundSubmission.CveUnsubscribe(topicKeys = listOf("cve-java")),
                )

            `when`("handleInteraction is invoked") {
                val result = context.handleInteraction(interaction = payload)
                val intents = intentQueue.drainSnapshot()

                then("the interaction succeeds with the unsubscribe detail type") {
                    result.ok shouldBe true
                    result.commandDetailType shouldBe CommandDetailType.CVE_UNSUBSCRIBE_SUBMIT
                }

                then("a CveUnsubscribe intent carries the actor id and selected keys") {
                    val unsubscribe = intents.filterIsInstance<CommandIntent.CveUnsubscribe>().single()
                    unsubscribe.userId shouldBe "U_SUBSCRIBER"
                    unsubscribe.topicKeys shouldContainExactly listOf("cve-java")
                }
            }
        }

        given("a submission whose typed payload is the wrong variant") {
            val intentQueue = createIntentQueue()
            val context =
                CveSubscribeSubmissionContext(
                    commandBasicInfo = createCommandBasicInfo(),
                    intents = intentQueue,
                )
            val payload =
                createInboundInteraction(
                    detailType = CommandDetailType.CVE_SUBSCRIBE_SUBMIT,
                    action = approveAction(isSelected = true),
                    submission = null,
                )

            `when`("handleInteraction is invoked") {
                val result = context.handleInteraction(interaction = payload)

                then("it succeeds without emitting an intent") {
                    result.ok shouldBe true
                    intentQueue.drainSnapshot().filterIsInstance<CommandIntent.CveSubscribe>().shouldBeEmpty()
                }
            }
        }
    })
