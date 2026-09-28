package dev.notypie.application.service.meeting

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.notypie.domain.command.EventQueue
import dev.notypie.domain.command.entity.event.CommandEvent
import dev.notypie.domain.command.entity.event.EventPayload
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.impl.command.event.OutboundMessageEnqueuedPayload
import dev.notypie.repository.meeting.JpaMeetingReminderRepository
import dev.notypie.repository.meeting.JpaMeetingRepository
import dev.notypie.repository.meeting.MeetingReminderRepositoryImpl
import dev.notypie.repository.meeting.MeetingRepositoryImpl
import dev.notypie.repository.meeting.schema.PARTICIPANT_UNIQUE_KEY
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.support.PersistenceExceptionTranslationInterceptor
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.orm.jpa.EntityManagerFactoryUtils
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLIntegrityConstraintViolationException
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import javax.sql.DataSource

private fun newH2Url(): String = "jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1"

fun createH2DataSource(): DataSource = DriverManagerDataSource(newH2Url())

fun createH2TransactionManager(dataSource: DataSource = createH2DataSource()): DataSourceTransactionManager =
    DataSourceTransactionManager(dataSource)

fun createBoundedH2DataSource(maxConnections: Int): HikariDataSource =
    HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = newH2Url()
            maximumPoolSize = maxConnections
            connectionTimeout = 250L
        },
    )

fun createFixedClock(now: LocalDateTime): Clock =
    Clock.fixed(now.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault())

fun createMeetingVersionConflict(): ObjectOptimisticLockingFailureException =
    ObjectOptimisticLockingFailureException("meetings", 1L)

fun createParticipantDuplicateKeyViolation(): DataIntegrityViolationException =
    DataIntegrityViolationException(
        "could not execute statement",
        SQLIntegrityConstraintViolationException(
            "Duplicate entry '7-U_A' for key '$PARTICIPANT_UNIQUE_KEY'",
        ),
    )

fun createNotNullViolation(): DataIntegrityViolationException =
    DataIntegrityViolationException(
        "could not execute statement",
        SQLIntegrityConstraintViolationException("Column 'user_id' cannot be null"),
    )

fun PlatformTransactionManager.failInsideParticipatingTx(exception: RuntimeException): Nothing {
    TransactionTemplate(this).executeWithoutResult { throw exception }
    error("unreachable: the template rethrows the callback exception")
}

class CommitRecordingEventPublisher : EventPublisher {
    val committedMessages: MutableList<OutboundMessage> = CopyOnWriteArrayList()

    val committedEphemeralMarkdowns: List<String>
        get() =
            committedMessages
                .filterIsInstance<OutboundMessage.Ephemeral>()
                .map { (it.content as MessageContent.Text).markdown }

    override fun publishEvent(events: EventQueue<CommandEvent<EventPayload>>) =
        events.forEach { event ->
            val message = (event.payload as OutboundMessageEnqueuedPayload).message
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        committedMessages.add(message)
                    }
                },
            )
        }
}

class MeetingJpaStore(
    private val entityManagerFactory: EntityManagerFactory,
    val transactionManager: JpaTransactionManager,
    val jpaMeetingRepository: JpaMeetingRepository,
    val jpaMeetingReminderRepository: JpaMeetingReminderRepository,
) : AutoCloseable {
    val meetingRepository = MeetingRepositoryImpl(jpaMeetingRepository = jpaMeetingRepository)
    val reminderRepository =
        MeetingReminderRepositoryImpl(
            jpaMeetingRepository = jpaMeetingRepository,
            jpaMeetingReminderRepository = jpaMeetingReminderRepository,
        )

    fun <T : Any> inNewTransaction(action: () -> T): T = TransactionTemplate(transactionManager).execute { action() }!!

    fun currentEntityManager(): EntityManager =
        checkNotNull(EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory)) {
            "no transaction-bound EntityManager"
        }

    override fun close() = entityManagerFactory.close()
}

fun createH2MeetingJpaStore(): MeetingJpaStore {
    val factoryBean =
        LocalContainerEntityManagerFactoryBean().apply {
            dataSource = createH2DataSource()
            setPackagesToScan("dev.notypie.repository.meeting.schema")
            jpaVendorAdapter = HibernateJpaVendorAdapter()
            setJpaPropertyMap(mapOf("hibernate.hbm2ddl.auto" to "create-drop"))
            afterPropertiesSet()
        }
    val entityManagerFactory = checkNotNull(factoryBean.`object`)
    val repositoryFactory =
        JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory)).apply {
            addRepositoryProxyPostProcessor { proxyFactory, _ ->
                proxyFactory.addAdvice(PersistenceExceptionTranslationInterceptor(factoryBean))
            }
        }
    return MeetingJpaStore(
        entityManagerFactory = entityManagerFactory,
        transactionManager = JpaTransactionManager(entityManagerFactory),
        jpaMeetingRepository = repositoryFactory.getRepository(JpaMeetingRepository::class.java),
        jpaMeetingReminderRepository = repositoryFactory.getRepository(JpaMeetingReminderRepository::class.java),
    )
}
