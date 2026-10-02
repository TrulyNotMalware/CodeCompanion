package dev.notypie.application.service.cve.notification

import dev.notypie.application.outbox.createOutboxRow
import dev.notypie.application.service.meeting.createH2DataSource
import dev.notypie.application.service.meeting.createH2TransactionManager
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.repository.cve.CveDeliveryRepository
import dev.notypie.repository.cve.schema.CveDeliveryMode
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.OutboundMessagePort
import dev.notypie.schema.createUndeliveredCveEvent
import dev.notypie.templates.SlackBlockLimits
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.springframework.jdbc.core.JdbcTemplate
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
            transactionManager: PlatformTransactionManager = stubTransactionManager(),
        ): CveNotificationDispatcher {
            every { deliveryRepository.dbNow() } returns DB_NOW
            return CveNotificationDispatcher(
                cveDeliveryRepository = deliveryRepository,
                outboxRepository = outboxRepository,
                outboundMessagePort = outboundMessagePort,
                transactionManager = transactionManager,
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

        given("an immediate pair whose feed text carries Slack control sequences") {
            val deliveryRepository = mockk<CveDeliveryRepository>(relaxed = true)
            val outboundMessagePort = mockk<OutboundMessagePort>()
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
                        topicDisplayName = "R&D <team>",
                        title = "<!channel> v2.3.1",
                        aiSummary = "Fix for < 2.3.1: <https://evil.example|Patch here>",
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
                    outboxRepository = stubOutbox(),
                )

            `when`("the immediate tick runs") {
                dispatcher.immediateTick()

                then("topic, title and summary are escaped so no mention or disguised link reaches Slack") {
                    messages.single().channelText().markdown shouldBe
                        "*R&amp;D &lt;team&gt;* — &lt;!channel&gt; v2.3.1\n\n" +
                        "Fix for &lt; 2.3.1: &lt;https://evil.example|Patch here&gt;"
                }
            }
        }

        given("an immediate pair whose summary only overflows the section limit once escaped") {
            val deliveryRepository = mockk<CveDeliveryRepository>(relaxed = true)
            val outboundMessagePort = mockk<OutboundMessagePort>()
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
                        aiSummary = "<".repeat(n = 1_000),
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
                    outboxRepository = stubOutbox(),
                )

            `when`("the immediate tick runs") {
                dispatcher.immediateTick()

                then("the cap is measured on the escaped body and never splits an entity") {
                    val markdown = messages.single().channelText().markdown
                    markdown.length shouldBeLessThanOrEqual 2900 + "\n…(truncated)".length
                    markdown shouldNotContain "<"
                    markdown shouldEndWith "&lt;\n…(truncated)"
                }
            }
        }

        given("a digest event whose topic, title and summary carry Slack control sequences") {
            val deliveryRepository = mockk<CveDeliveryRepository>(relaxed = true)
            val outboundMessagePort = mockk<OutboundMessagePort>()
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
                        topicDisplayName = "<!here>",
                        title = "a&b",
                        aiSummary = "<https://evil.example|x>",
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
                    outboxRepository = stubOutbox(),
                )

            `when`("the digest tick runs") {
                dispatcher.digestTick()

                then("every interpolated piece is escaped") {
                    messages.single().channelText().markdown shouldBe
                        "*&lt;!here&gt;*\n• *a&amp;b*\n&lt;https://evil.example|x&gt;"
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

        given("two immediate pairs where the first fails to enqueue, on a real transaction manager") {
            val dataSource = createH2DataSource()
            val jdbc = JdbcTemplate(dataSource)
            jdbc.execute(
                "CREATE TABLE delivery_claim (event_id BIGINT, user_id VARCHAR(32), PRIMARY KEY (event_id, user_id))",
            )
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
            every { deliveryRepository.claim(eventId = any(), userId = any()) } answers {
                jdbc.update(
                    "INSERT INTO delivery_claim (event_id, user_id) VALUES (?, ?)",
                    firstArg<Long>(),
                    secondArg<String>(),
                ) == 1
            }
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
                    transactionManager = createH2TransactionManager(dataSource = dataSource),
                )

            `when`("the immediate tick runs") {
                dispatcher.immediateTick()

                then("the first pair's claim rolls back with its failed enqueue, so a later tick can claim it again") {
                    jdbc.queryForList("SELECT user_id FROM delivery_claim", String::class.java) shouldBe listOf("U2")
                }

                then("the first pair's failure is isolated and the second pair is still claimed and enqueued") {
                    verify(exactly = 2) { outboxRepository.save(any()) }
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

        given("a digest bundle larger than one message body") {
            val deliveryRepository = mockk<CveDeliveryRepository>(relaxed = true)
            val outboundMessagePort = mockk<OutboundMessagePort>()
            val messages = mutableListOf<OutboundMessage>()
            val claimed = mutableListOf<Long>()
            every {
                deliveryRepository.findUndelivered(
                    deliveryMode = CveDeliveryMode.DIGEST,
                    since = any(),
                    doneBefore = any(),
                    limit = 50,
                )
            } returns
                (10L..29L).map { eventId ->
                    createUndeliveredCveEvent(
                        eventId = eventId,
                        userId = "U1",
                        topicDisplayName = "Alpha",
                        title = "CVE-2026-00$eventId",
                        aiSummary = "x".repeat(n = 700),
                    )
                }
            every { deliveryRepository.claim(eventId = capture(claimed), userId = "U1") } returns true
            every { outboundMessagePort.toRow(message = capture(messages), basicInfo = any()) } answers {
                createOutboxRow(eventId = UUID.randomUUID().toString())
            }
            val dispatcher =
                dispatcherWith(
                    deliveryRepository = deliveryRepository,
                    outboundMessagePort = outboundMessagePort,
                    outboxRepository = stubOutbox(),
                )

            `when`("the digest tick runs") {
                dispatcher.digestTick()

                then("every claimed event's identifier reaches a sent body") {
                    claimed shouldBe (10L..29L).toList()
                    val sent = messages.joinToString(separator = "\n") { it.channelText().markdown }
                    claimed.forEach { eventId -> sent shouldContain "CVE-2026-00$eventId" }
                }

                then("the digest is split into numbered parts within the message body budget, each under its topic") {
                    messages.size shouldBe 2
                    messages.map { it.channelText().headline } shouldBe
                        listOf("CodeCompanion — CVE digest (1/2)", "CodeCompanion — CVE digest (2/2)")
                    messages.forEach { message ->
                        val markdown = message.channelText().markdown
                        markdown.length shouldBeLessThanOrEqual SlackBlockLimits.MESSAGE_BODY_BUDGET
                        markdown shouldStartWith "*Alpha*\n"
                        markdown shouldNotContain "(truncated)"
                    }
                }
            }
        }

        given("a digest event whose single line alone is longer than a message body") {
            val deliveryRepository = mockk<CveDeliveryRepository>(relaxed = true)
            val outboundMessagePort = mockk<OutboundMessagePort>()
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
                        aiSummary = "x".repeat(n = 12_000),
                    ),
                    createUndeliveredCveEvent(
                        eventId = 2L,
                        userId = "U1",
                        topicDisplayName = "Alpha",
                        title = "t2",
                        aiSummary = "s2",
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
                    outboxRepository = stubOutbox(),
                    digestSummaryMaxLength = 20_000,
                )

            `when`("the digest tick runs") {
                dispatcher.digestTick()

                then("only that line is cut with a marker and the next event starts a fresh part") {
                    messages.size shouldBe 2
                    val first = messages[0].channelText().markdown
                    first.length shouldBeLessThanOrEqual SlackBlockLimits.MESSAGE_BODY_BUDGET
                    first shouldStartWith "*Alpha*\n• *t1*\n"
                    first shouldEndWith SlackBlockLimits.TRUNCATION_MARKER
                    messages[1].channelText().markdown shouldBe "*Alpha*\n• *t2*\ns2"
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
