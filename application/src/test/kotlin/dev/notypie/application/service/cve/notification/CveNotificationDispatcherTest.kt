package dev.notypie.application.service.cve.notification

import dev.notypie.application.outbox.createOutboxRow
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.repository.cve.CveDeliveryRepository
import dev.notypie.repository.cve.schema.CveDeliveryMode
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.OutboundMessagePort
import dev.notypie.schema.createUndeliveredCveEvent
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionStatus
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

private val AFTER_SEND_AT: Instant = Instant.parse("2026-07-14T10:00:00Z")
private val BEFORE_SEND_AT: Instant = Instant.parse("2026-07-14T08:00:00Z")
private val DB_NOW: LocalDateTime = LocalDateTime.of(2026, 7, 14, 10, 0)

private fun OutboundMessage.channelId(): String = (this as OutboundMessage.ChannelMessage).target.id

private fun OutboundMessage.channelText(): MessageContent.Text =
    (this as OutboundMessage.ChannelMessage).content as MessageContent.Text

class CveNotificationDispatcherTest :
    BehaviorSpec({
        fun stubTransactionManager(): PlatformTransactionManager {
            val tm = mockk<PlatformTransactionManager>()
            val status = mockk<TransactionStatus>(relaxed = true)
            every { tm.getTransaction(any()) } returns status
            every { tm.commit(any()) } just Runs
            every { tm.rollback(any()) } just Runs
            return tm
        }

        // save() is a generic JpaRepository method; a purely relaxed mock returns a bare Object that
        // fails the covariant cast, so echo the persisted row back like the standup/agenda tests.
        fun stubOutbox(): MessageOutboxRepository {
            val repo = mockk<MessageOutboxRepository>(relaxed = true)
            every { repo.save(any()) } answers { firstArg() }
            return repo
        }

        fun dispatcherWith(
            deliveryRepository: CveDeliveryRepository,
            outboundMessagePort: OutboundMessagePort,
            outboxRepository: MessageOutboxRepository = mockk(relaxed = true),
            clock: Clock = Clock.fixed(AFTER_SEND_AT, ZoneOffset.UTC),
            digestSummaryMaxLength: Int = 700,
        ): CveNotificationDispatcher {
            // The created_at horizon reads the DB clock through the repository; pin it per test.
            every { deliveryRepository.dbNow() } returns DB_NOW
            return CveNotificationDispatcher(
                cveDeliveryRepository = deliveryRepository,
                outboxRepository = outboxRepository,
                outboundMessagePort = outboundMessagePort,
                transactionManager = stubTransactionManager(),
                batchSize = 50,
                digestSendAt = LocalTime.of(9, 0),
                digestZone = ZoneOffset.UTC,
                digestSummaryMaxLength = digestSummaryMaxLength,
                deliveryHorizonDays = 7,
                clock = clock,
            )
        }

        given("an immediate pair whose claim is won") {
            val deliveryRepository = mockk<CveDeliveryRepository>(relaxed = true)
            val outboundMessagePort = mockk<OutboundMessagePort>()
            val outboxRepository = stubOutbox()
            val messages = mutableListOf<OutboundMessage>()
            every {
                deliveryRepository.findUndelivered(
                    deliveryMode = CveDeliveryMode.IMMEDIATE,
                    since = any(),
                    doneBefore = any(),
                    limit = 50,
                )
            } returns
                listOf(
                    createUndeliveredCveEvent(
                        eventId = 1L,
                        userId = "U1",
                        topicDisplayName = "Java CVE",
                        title = "Boom",
                        aiSummary = "Patch now",
                    ),
                )
            every { deliveryRepository.claim(eventId = 1L, userId = "U1") } returns true
            every { outboundMessagePort.toRow(message = capture(messages), basicInfo = any()) } answers {
                createOutboxRow(eventId = UUID.randomUUID().toString())
            }
            val dispatcher =
                dispatcherWith(
                    deliveryRepository = deliveryRepository,
                    outboundMessagePort = outboundMessagePort,
                    outboxRepository = outboxRepository,
                )

            `when`("the immediate tick runs") {
                dispatcher.immediateTick()

                then("one DM is enqueued to the subscriber with the single-event content") {
                    verify(exactly = 1) { outboxRepository.save(any()) }
                    messages.size shouldBe 1
                    messages.single().channelId() shouldBe "U1"
                    messages.single().channelText().headline shouldBe "CodeCompanion — CVE alert"
                    messages.single().channelText().markdown shouldBe "*Java CVE* — Boom\n\nPatch now"
                }
            }
        }

        given("an immediate pair whose claim is lost") {
            val deliveryRepository = mockk<CveDeliveryRepository>(relaxed = true)
            val outboundMessagePort = mockk<OutboundMessagePort>()
            val outboxRepository = stubOutbox()
            every {
                deliveryRepository.findUndelivered(
                    deliveryMode = CveDeliveryMode.IMMEDIATE,
                    since = any(),
                    doneBefore = any(),
                    limit = 50,
                )
            } returns listOf(createUndeliveredCveEvent(eventId = 1L, userId = "U1"))
            every { deliveryRepository.claim(eventId = 1L, userId = "U1") } returns false
            val dispatcher =
                dispatcherWith(
                    deliveryRepository = deliveryRepository,
                    outboundMessagePort = outboundMessagePort,
                    outboxRepository = outboxRepository,
                )

            `when`("the immediate tick runs") {
                dispatcher.immediateTick()

                then("nothing is enqueued") {
                    verify(exactly = 0) { outboundMessagePort.toRow(message = any(), basicInfo = any()) }
                    verify(exactly = 0) { outboxRepository.save(any()) }
                }
            }
        }

        given("two immediate pairs where the first fails to enqueue") {
            val deliveryRepository = mockk<CveDeliveryRepository>(relaxed = true)
            val outboundMessagePort = mockk<OutboundMessagePort>()
            val outboxRepository = mockk<MessageOutboxRepository>()
            every {
                deliveryRepository.findUndelivered(
                    deliveryMode = CveDeliveryMode.IMMEDIATE,
                    since = any(),
                    doneBefore = any(),
                    limit = 50,
                )
            } returns
                listOf(
                    createUndeliveredCveEvent(eventId = 1L, userId = "U1"),
                    createUndeliveredCveEvent(eventId = 2L, userId = "U2"),
                )
            every { deliveryRepository.claim(eventId = any(), userId = any()) } returns true
            every { outboundMessagePort.toRow(message = any(), basicInfo = any()) } answers {
                createOutboxRow(eventId = UUID.randomUUID().toString())
            }
            var saveCall = 0
            every { outboxRepository.save(any()) } answers {
                saveCall++
                if (saveCall == 1) throw RuntimeException("enqueue boom") else createOutboxRow(eventId = "ok")
            }
            val dispatcher =
                dispatcherWith(
                    deliveryRepository = deliveryRepository,
                    outboundMessagePort = outboundMessagePort,
                    outboxRepository = outboxRepository,
                )

            `when`("the immediate tick runs") {
                dispatcher.immediateTick()

                then("the first pair's failure is isolated and the second pair is still claimed and enqueued") {
                    verify(exactly = 2) { outboxRepository.save(any()) }
                    // claim and save run in the same runInTx per pair, so they interleave pair-by-pair.
                    verifyOrder {
                        deliveryRepository.claim(eventId = 1L, userId = "U1")
                        outboxRepository.save(any())
                        deliveryRepository.claim(eventId = 2L, userId = "U2")
                        outboxRepository.save(any())
                    }
                }
            }
        }

        given("a digest tick before the configured send time") {
            val deliveryRepository = mockk<CveDeliveryRepository>(relaxed = true)
            val outboundMessagePort = mockk<OutboundMessagePort>()
            val outboxRepository = stubOutbox()
            val dispatcher =
                dispatcherWith(
                    deliveryRepository = deliveryRepository,
                    outboundMessagePort = outboundMessagePort,
                    outboxRepository = outboxRepository,
                    clock = Clock.fixed(BEFORE_SEND_AT, ZoneOffset.UTC),
                )

            `when`("the digest tick runs") {
                dispatcher.digestTick()

                then("the delivery repository is never queried and nothing is enqueued") {
                    verify(exactly = 0) {
                        deliveryRepository.findUndelivered(
                            deliveryMode = any(),
                            since = any(),
                            doneBefore = any(),
                            limit = any(),
                        )
                    }
                    verify(exactly = 0) { outboxRepository.save(any()) }
                }
            }
        }

        given("a digest tick after the send time") {
            val deliveryRepository = mockk<CveDeliveryRepository>(relaxed = true)
            val outboundMessagePort = mockk<OutboundMessagePort>()
            val outboxRepository = stubOutbox()
            val doneBefore = slot<LocalDateTime>()
            every {
                deliveryRepository.findUndelivered(
                    deliveryMode = CveDeliveryMode.DIGEST,
                    since = any(),
                    doneBefore = capture(doneBefore),
                    limit = 50,
                )
            } returns emptyList()
            val dispatcher =
                dispatcherWith(
                    deliveryRepository = deliveryRepository,
                    outboundMessagePort = outboundMessagePort,
                    outboxRepository = outboxRepository,
                )

            `when`("the digest tick runs") {
                dispatcher.digestTick()

                then("the visibility cutoff is today's send time (09:00 in the digest zone)") {
                    doneBefore.captured shouldBe
                        Instant.parse("2026-07-14T09:00:00Z").atZone(ZoneId.systemDefault()).toLocalDateTime()
                }
            }
        }

        given("a digest tick after the send time spanning two users") {
            val deliveryRepository = mockk<CveDeliveryRepository>(relaxed = true)
            val outboundMessagePort = mockk<OutboundMessagePort>()
            val outboxRepository = stubOutbox()
            val messages = mutableListOf<OutboundMessage>()
            every {
                deliveryRepository.findUndelivered(
                    deliveryMode = CveDeliveryMode.DIGEST,
                    since = any(),
                    doneBefore = any(),
                    limit = 50,
                )
            } returns
                listOf(
                    createUndeliveredCveEvent(
                        eventId = 1L,
                        userId = "U1",
                        topicDisplayName = "Alpha",
                        title = "t1",
                        aiSummary = "s1",
                    ),
                    createUndeliveredCveEvent(
                        eventId = 2L,
                        userId = "U1",
                        topicDisplayName = "Alpha",
                        title = "t2",
                        aiSummary = "s2",
                    ),
                    createUndeliveredCveEvent(
                        eventId = 3L,
                        userId = "U2",
                        topicDisplayName = "Beta",
                        title = "t3",
                        aiSummary = "s3",
                    ),
                )
            every { deliveryRepository.claim(eventId = any(), userId = any()) } returns true
            every { outboundMessagePort.toRow(message = capture(messages), basicInfo = any()) } answers {
                createOutboxRow(eventId = UUID.randomUUID().toString())
            }
            val dispatcher =
                dispatcherWith(
                    deliveryRepository = deliveryRepository,
                    outboundMessagePort = outboundMessagePort,
                    outboxRepository = outboxRepository,
                )

            `when`("the digest tick runs") {
                dispatcher.digestTick()

                then("each user gets one bundled DM and a single user's events are grouped together") {
                    verify(exactly = 2) { outboxRepository.save(any()) }
                    messages.size shouldBe 2
                    val byUser = messages.associateBy { it.channelId() }
                    byUser.getValue("U1").channelText().headline shouldBe "CodeCompanion — CVE digest"
                    byUser.getValue("U1").channelText().markdown shouldBe "*Alpha*\n• *t1*\ns1\n• *t2*\ns2"
                    byUser.getValue("U2").channelText().markdown shouldBe "*Beta*\n• *t3*\ns3"
                }
            }
        }

        given("a digest user with one won and one lost event") {
            val deliveryRepository = mockk<CveDeliveryRepository>(relaxed = true)
            val outboundMessagePort = mockk<OutboundMessagePort>()
            val outboxRepository = stubOutbox()
            val messages = mutableListOf<OutboundMessage>()
            every {
                deliveryRepository.findUndelivered(
                    deliveryMode = CveDeliveryMode.DIGEST,
                    since = any(),
                    doneBefore = any(),
                    limit = 50,
                )
            } returns
                listOf(
                    createUndeliveredCveEvent(
                        eventId = 1L,
                        userId = "U1",
                        topicDisplayName = "Alpha",
                        title = "kept",
                        aiSummary = "s1",
                    ),
                    createUndeliveredCveEvent(
                        eventId = 2L,
                        userId = "U1",
                        topicDisplayName = "Alpha",
                        title = "dropped",
                        aiSummary = "s2",
                    ),
                )
            every { deliveryRepository.claim(eventId = 1L, userId = "U1") } returns true
            every { deliveryRepository.claim(eventId = 2L, userId = "U1") } returns false
            every { outboundMessagePort.toRow(message = capture(messages), basicInfo = any()) } answers {
                createOutboxRow(eventId = UUID.randomUUID().toString())
            }
            val dispatcher =
                dispatcherWith(
                    deliveryRepository = deliveryRepository,
                    outboundMessagePort = outboundMessagePort,
                    outboxRepository = outboxRepository,
                )

            `when`("the digest tick runs") {
                dispatcher.digestTick()

                then("only the won event is included in the single DM") {
                    verify(exactly = 1) { outboxRepository.save(any()) }
                    messages.single().channelText().markdown shouldBe "*Alpha*\n• *kept*\ns1"
                }
            }
        }

        given("a digest event whose summary exceeds the configured maximum") {
            val deliveryRepository = mockk<CveDeliveryRepository>(relaxed = true)
            val outboundMessagePort = mockk<OutboundMessagePort>()
            val outboxRepository = stubOutbox()
            val messages = mutableListOf<OutboundMessage>()
            every {
                deliveryRepository.findUndelivered(
                    deliveryMode = CveDeliveryMode.DIGEST,
                    since = any(),
                    doneBefore = any(),
                    limit = 50,
                )
            } returns
                listOf(
                    createUndeliveredCveEvent(
                        eventId = 1L,
                        userId = "U1",
                        topicDisplayName = "Alpha",
                        title = "t",
                        aiSummary = "0123456789ABCDEF",
                    ),
                )
            every { deliveryRepository.claim(eventId = any(), userId = any()) } returns true
            every { outboundMessagePort.toRow(message = capture(messages), basicInfo = any()) } answers {
                createOutboxRow(eventId = UUID.randomUUID().toString())
            }
            val dispatcher =
                dispatcherWith(
                    deliveryRepository = deliveryRepository,
                    outboundMessagePort = outboundMessagePort,
                    outboxRepository = outboxRepository,
                    digestSummaryMaxLength = 10,
                )

            `when`("the digest tick runs") {
                dispatcher.digestTick()

                then("the summary is truncated to the configured length") {
                    messages.single().channelText().markdown shouldBe "*Alpha*\n• *t*\n0123456789"
                }
            }
        }

        given("an immediate pair whose summary exceeds the Slack section limit") {
            val deliveryRepository = mockk<CveDeliveryRepository>(relaxed = true)
            val outboundMessagePort = mockk<OutboundMessagePort>()
            val outboxRepository = stubOutbox()
            val messages = mutableListOf<OutboundMessage>()
            val longSummary = "x".repeat(3500)
            every {
                deliveryRepository.findUndelivered(
                    deliveryMode = CveDeliveryMode.IMMEDIATE,
                    since = any(),
                    doneBefore = any(),
                    limit = 50,
                )
            } returns
                listOf(
                    createUndeliveredCveEvent(
                        eventId = 1L,
                        userId = "U1",
                        topicDisplayName = "Alpha",
                        title = "t",
                        aiSummary = longSummary,
                    ),
                )
            every { deliveryRepository.claim(eventId = any(), userId = any()) } returns true
            every { outboundMessagePort.toRow(message = capture(messages), basicInfo = any()) } answers {
                createOutboxRow(eventId = UUID.randomUUID().toString())
            }
            val dispatcher =
                dispatcherWith(
                    deliveryRepository = deliveryRepository,
                    outboundMessagePort = outboundMessagePort,
                    outboxRepository = outboxRepository,
                )

            `when`("the immediate tick runs") {
                dispatcher.immediateTick()

                then("the whole body is capped under the section limit with a truncation marker") {
                    messages.single().channelText().markdown shouldBe
                        "*Alpha* — t\n\n${"x".repeat(2887)}\n…(truncated)"
                }
            }
        }

        given("an immediate pair with a blank summary") {
            val deliveryRepository = mockk<CveDeliveryRepository>(relaxed = true)
            val outboundMessagePort = mockk<OutboundMessagePort>()
            val outboxRepository = stubOutbox()
            val messages = mutableListOf<OutboundMessage>()
            every {
                deliveryRepository.findUndelivered(
                    deliveryMode = CveDeliveryMode.IMMEDIATE,
                    since = any(),
                    doneBefore = any(),
                    limit = 50,
                )
            } returns
                listOf(
                    createUndeliveredCveEvent(
                        eventId = 1L,
                        userId = "U1",
                        topicDisplayName = "Alpha",
                        title = "t",
                        aiSummary = null,
                    ),
                )
            every { deliveryRepository.claim(eventId = any(), userId = any()) } returns true
            every { outboundMessagePort.toRow(message = capture(messages), basicInfo = any()) } answers {
                createOutboxRow(eventId = UUID.randomUUID().toString())
            }
            val dispatcher =
                dispatcherWith(
                    deliveryRepository = deliveryRepository,
                    outboundMessagePort = outboundMessagePort,
                    outboxRepository = outboxRepository,
                )

            `when`("the immediate tick runs") {
                dispatcher.immediateTick()

                then("the DM falls back to a title-only line") {
                    messages.single().channelText().markdown shouldBe "*Alpha* — t"
                }
            }
        }

        given("a digest bundle whose aggregate body exceeds the Slack section limit") {
            val deliveryRepository = mockk<CveDeliveryRepository>(relaxed = true)
            val outboundMessagePort = mockk<OutboundMessagePort>()
            val outboxRepository = stubOutbox()
            val messages = mutableListOf<OutboundMessage>()
            every {
                deliveryRepository.findUndelivered(
                    deliveryMode = CveDeliveryMode.DIGEST,
                    since = any(),
                    doneBefore = any(),
                    limit = 50,
                )
            } returns
                (1L..5L).map { eventId ->
                    createUndeliveredCveEvent(
                        eventId = eventId,
                        userId = "U1",
                        topicDisplayName = "Alpha",
                        title = "t$eventId",
                        aiSummary = "x".repeat(700),
                    )
                }
            every { deliveryRepository.claim(eventId = any(), userId = any()) } returns true
            every { outboundMessagePort.toRow(message = capture(messages), basicInfo = any()) } answers {
                createOutboxRow(eventId = UUID.randomUUID().toString())
            }
            val dispatcher =
                dispatcherWith(
                    deliveryRepository = deliveryRepository,
                    outboundMessagePort = outboundMessagePort,
                    outboxRepository = outboxRepository,
                )

            `when`("the digest tick runs") {
                dispatcher.digestTick()

                then("the bundled body is capped under the section limit with a truncation marker") {
                    val markdown = messages.single().channelText().markdown
                    markdown.length shouldBe 2900 + "\n…(truncated)".length
                    markdown shouldEndWith "…(truncated)"
                }
            }
        }

        given("the delivery horizon bound") {
            val deliveryRepository = mockk<CveDeliveryRepository>(relaxed = true)
            val since = slot<LocalDateTime>()
            every {
                deliveryRepository.findUndelivered(
                    deliveryMode = CveDeliveryMode.IMMEDIATE,
                    since = capture(since),
                    doneBefore = any(),
                    limit = 50,
                )
            } returns emptyList()
            val dispatcher =
                dispatcherWith(
                    deliveryRepository = deliveryRepository,
                    outboundMessagePort = mockk(),
                )

            `when`("the immediate tick runs") {
                dispatcher.immediateTick()

                then("the horizon is derived from the DB clock, not the app clock") {
                    since.captured shouldBe DB_NOW.minusDays(7)
                }
            }
        }
    })
