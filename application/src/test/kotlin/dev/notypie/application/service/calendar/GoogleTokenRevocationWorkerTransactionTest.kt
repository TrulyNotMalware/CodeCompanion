package dev.notypie.application.service.calendar

import dev.notypie.application.service.meeting.createH2DataSource
import dev.notypie.application.service.meeting.createH2TransactionManager
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.impl.calendar.GoogleOAuthClient
import dev.notypie.repository.calendar.GoogleCalendarConnectionRepository
import dev.notypie.schema.createTestTokenCipher
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.event.TransactionalEventListenerFactory
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Connection
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier

class GoogleTokenRevocationWorkerTransactionTest :
    BehaviorSpec({
        val dataSource = createH2DataSource()
        val transactionManager = createH2TransactionManager(dataSource = dataSource)
        val cipher = createTestTokenCipher()
        val event =
            GoogleTokenRevocationRequested(
                userId = "U_TX",
                googleSubject = "sub-1",
                encryptedRefreshToken = cipher.encrypt(plaintext = "1//refresh"),
                reason = "disconnect",
            )

        fun contextWith(executor: Executor, stager: OutboundMessageStager): AnnotationConfigApplicationContext =
            AnnotationConfigApplicationContext().apply {
                registerBean(
                    TransactionalEventListenerFactory::class.java,
                    Supplier { TransactionalEventListenerFactory() },
                )
                registerBean(PlatformTransactionManager::class.java, Supplier { transactionManager })
                registerBean(
                    GoogleTokenRevocationWorker::class.java,
                    Supplier {
                        GoogleTokenRevocationWorker(
                            oauthClient = mockk<GoogleOAuthClient>(),
                            tokenCipher = cipher,
                            outboundStager = stager,
                            eventPublisher = mockk<EventPublisher>(relaxed = true),
                            connectionRepository = mockk<GoogleCalendarConnectionRepository>(),
                            transactionManager = transactionManager,
                            executor = executor,
                        )
                    },
                )
                refresh()
            }

        given("a revocation published inside a real transaction") {
            `when`("the transaction commits") {
                val handedOff = CopyOnWriteArrayList<Runnable>()
                val context = contextWith(executor = { task -> handedOff.add(task) }, stager = mockk())
                val handedOffBeforeCommit = AtomicReference<Int>()
                TransactionTemplate(transactionManager).executeWithoutResult {
                    context.publishEvent(event)
                    handedOffBeforeCommit.set(handedOff.size)
                }
                context.close()

                then("the executor receives the revoke only after the commit") {
                    handedOffBeforeCommit.get() shouldBe 0
                    handedOff shouldHaveSize 1
                }
            }

            `when`("the transaction rolls back") {
                val handedOff = CopyOnWriteArrayList<Runnable>()
                val context = contextWith(executor = { task -> handedOff.add(task) }, stager = mockk())
                TransactionTemplate(transactionManager).executeWithoutResult { status ->
                    context.publishEvent(event)
                    status.setRollbackOnly()
                }
                context.close()

                then("nothing is revoked, because the row delete it belonged to never happened") {
                    handedOff.shouldBeEmpty()
                }
            }
        }

        given("an executor that rejects the task after the commit") {
            val outerConnection = AtomicReference<Connection>()
            val noticeConnection = AtomicReference<Connection>()
            val stager =
                mockk<OutboundMessageStager> {
                    every { stage(message = any(), basicInfo = any()) } answers {
                        noticeConnection.set(DataSourceUtils.getConnection(dataSource))
                        mockk(relaxed = true)
                    }
                }
            val context = contextWith(executor = { throw RejectedExecutionException("full") }, stager = stager)

            `when`("the manual-removal DM is staged from the after-commit callback") {
                TransactionTemplate(transactionManager).executeWithoutResult {
                    outerConnection.set(DataSourceUtils.getConnection(dataSource))
                    context.publishEvent(event)
                }
                context.close()

                then("it runs in a new transaction on its own connection, not in the finished one") {
                    noticeConnection.get().shouldNotBeNull() shouldNotBeSameInstanceAs outerConnection.get()
                }
            }
        }
    })
