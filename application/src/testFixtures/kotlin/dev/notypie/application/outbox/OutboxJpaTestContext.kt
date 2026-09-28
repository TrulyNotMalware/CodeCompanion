package dev.notypie.application.outbox

import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.impl.command.event.MessageDispatcher
import dev.notypie.impl.command.event.OpenViewPayloadContents
import dev.notypie.impl.command.event.SlackEventPayload
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.schema.OutboxMessage
import jakarta.persistence.EntityManagerFactory
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.transaction.annotation.EnableTransactionManagement
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(basePackageClasses = [MessageOutboxRepository::class])
class OutboxJpaTestConfiguration {
    @Bean
    fun dataSource(): DataSource =
        EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).generateUniqueName(true).build()

    @Bean
    fun entityManagerFactory(dataSource: DataSource): LocalContainerEntityManagerFactoryBean =
        LocalContainerEntityManagerFactoryBean().apply {
            setDataSource(dataSource)
            setPackagesToScan(OutboxMessage::class.java.packageName)
            jpaVendorAdapter = HibernateJpaVendorAdapter().apply { setGenerateDdl(true) }
        }

    @Bean
    fun transactionManager(entityManagerFactory: EntityManagerFactory): JpaTransactionManager =
        JpaTransactionManager(entityManagerFactory)

    @Bean
    fun jdbcTemplate(dataSource: DataSource): JdbcTemplate = JdbcTemplate(dataSource)
}

fun createOutboxJpaContext(): AnnotationConfigApplicationContext =
    AnnotationConfigApplicationContext(OutboxJpaTestConfiguration::class.java)

class MutableClock(
    private var current: Instant = Instant.now(),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : Clock() {
    val now: LocalDateTime
        get() = LocalDateTime.ofInstant(current, zoneId)

    fun advance(by: Duration) {
        current = current.plus(by)
    }

    override fun getZone(): ZoneId = zoneId

    override fun withZone(zone: ZoneId): Clock = MutableClock(current = current, zoneId = zone)

    override fun instant(): Instant = current
}

class ScriptedMessageDispatcher(
    private val outcomes: List<(SlackEventPayload) -> CommandOutput>,
    private val fallback: (SlackEventPayload) -> CommandOutput,
) : MessageDispatcher {
    private val counter = AtomicInteger(0)

    val calls: Int
        get() = counter.get()

    override fun dispatch(event: SlackEventPayload): CommandOutput =
        (outcomes.getOrNull(counter.getAndIncrement()) ?: fallback)(event)

    override fun dispatchImmediate(event: OpenViewPayloadContents): CommandOutput =
        error("views.open is not an outbox dispatch")
}

class QueuedExecutor : Executor {
    private val tasks = ArrayDeque<Runnable>()

    val size: Int
        get() = tasks.size

    override fun execute(command: Runnable) {
        tasks.addLast(command)
    }

    fun runAll() {
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
    }
}

fun JdbcTemplate.outboxColumn(eventId: String, column: String): Any? =
    queryForMap("SELECT $column FROM outbox_message WHERE event_id = ?", eventId).values.single()
