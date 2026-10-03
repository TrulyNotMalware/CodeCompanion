package dev.notypie.configurations

import com.zaxxer.hikari.HikariDataSource
import dev.notypie.repository.agent.AgentSessionRepositoryImpl
import dev.notypie.repository.agent.AgentTurnHistoryRepositoryImpl
import dev.notypie.repository.agent.JpaAgentSessionRepository
import dev.notypie.repository.agent.JpaAgentTurnHistoryRepository
import dev.notypie.repository.authorization.JpaUserCommandRoleRepository
import dev.notypie.repository.authorization.UserCommandRoleRepositoryImpl
import dev.notypie.repository.cve.CveCollectLedgerRepositoryImpl
import dev.notypie.repository.cve.CveDeliveryRepositoryImpl
import dev.notypie.repository.cve.CveEventRepositoryImpl
import dev.notypie.repository.cve.CveSubscriptionRepositoryImpl
import dev.notypie.repository.cve.CveTopicRepositoryImpl
import dev.notypie.repository.cve.JpaCveCollectLedgerRepository
import dev.notypie.repository.cve.JpaCveDeliveryRepository
import dev.notypie.repository.cve.JpaCveEventRepository
import dev.notypie.repository.cve.JpaCveSubscriptionRepository
import dev.notypie.repository.cve.JpaCveTopicRepository
import dev.notypie.repository.mcp.JpaMcpToolCallHistoryRepository
import dev.notypie.repository.mcp.McpToolCallHistoryRepositoryImpl
import dev.notypie.repository.meeting.AgendaDispatchRepositoryImpl
import dev.notypie.repository.meeting.JpaAgendaDispatchRepository
import dev.notypie.repository.meeting.JpaMeetingReminderRepository
import dev.notypie.repository.meeting.JpaMeetingRepository
import dev.notypie.repository.meeting.MeetingReminderRepositoryImpl
import dev.notypie.repository.meeting.MeetingRepositoryImpl
import dev.notypie.repository.standup.JpaRoutineRepository
import dev.notypie.repository.standup.JpaSessionDispatchRepository
import dev.notypie.repository.standup.JpaStandupSessionRepository
import dev.notypie.repository.standup.StandupRepositoryImpl
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy
import org.springframework.jdbc.support.SQLExceptionTranslator
import org.springframework.transaction.PlatformTransactionManager
import java.time.Clock

const val PRIMARY_DATASOURCE_CONFIG = "primaryPersistenceUnit"
const val JPA_ENTITY_PACKAGES = "dev.notypie.repository"

@Configuration
@EnableJpaRepositories(basePackages = [JPA_ENTITY_PACKAGES])
class JpaConfiguration {
    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    fun hikariDataSource(dataSourceProperties: DataSourceProperties): HikariDataSource =
        dataSourceProperties
            .initializeDataSourceBuilder()
            .type(HikariDataSource::class.java)
            .build()

    @Bean
    @Primary
    fun lazyConnectionDataSourceProxy(hikariDataSource: HikariDataSource) =
        LazyConnectionDataSourceProxy(hikariDataSource)

    @Bean
    fun snapshotIsolationExceptionTranslator(): SQLExceptionTranslator = SnapshotIsolationExceptionTranslator()

    @Bean
    @Primary
    fun meetingRepository(jpaMeetingRepository: JpaMeetingRepository, clock: ObjectProvider<Clock>) =
        MeetingRepositoryImpl(
            jpaMeetingRepository = jpaMeetingRepository,
            // The application declares the Clock bean; infrastructure-only JPA slices have none.
            clock = clock.getIfAvailable { Clock.systemDefaultZone() },
        )

    @Bean
    @Primary
    fun meetingReminderRepository(
        jpaMeetingRepository: JpaMeetingRepository,
        jpaMeetingReminderRepository: JpaMeetingReminderRepository,
        transactionManager: PlatformTransactionManager,
    ) = MeetingReminderRepositoryImpl(
        jpaMeetingRepository = jpaMeetingRepository,
        jpaMeetingReminderRepository = jpaMeetingReminderRepository,
        transactionManager = transactionManager,
    )

    @Bean
    @Primary
    fun agendaDispatchRepository(
        jpaMeetingRepository: JpaMeetingRepository,
        jpaAgendaDispatchRepository: JpaAgendaDispatchRepository,
    ) = AgendaDispatchRepositoryImpl(
        jpaMeetingRepository = jpaMeetingRepository,
        jpaAgendaDispatchRepository = jpaAgendaDispatchRepository,
    )

    @Bean
    @Primary
    fun agentSessionRepository(jpaAgentSessionRepository: JpaAgentSessionRepository) =
        AgentSessionRepositoryImpl(jpaAgentSessionRepository = jpaAgentSessionRepository)

    @Bean
    @Primary
    fun agentTurnHistoryRepository(jpaAgentTurnHistoryRepository: JpaAgentTurnHistoryRepository) =
        AgentTurnHistoryRepositoryImpl(jpaAgentTurnHistoryRepository = jpaAgentTurnHistoryRepository)

    @Bean
    @Primary
    fun userCommandRoleRepository(jpaUserCommandRoleRepository: JpaUserCommandRoleRepository) =
        UserCommandRoleRepositoryImpl(jpaUserCommandRoleRepository = jpaUserCommandRoleRepository)

    @Bean
    @Primary
    fun mcpToolCallHistoryRepository(jpaMcpToolCallHistoryRepository: JpaMcpToolCallHistoryRepository) =
        McpToolCallHistoryRepositoryImpl(jpaMcpToolCallHistoryRepository = jpaMcpToolCallHistoryRepository)

    @Bean
    @Primary
    fun cveTopicRepository(
        jpaCveTopicRepository: JpaCveTopicRepository,
        transactionManager: PlatformTransactionManager,
    ) = CveTopicRepositoryImpl(jpaCveTopicRepository = jpaCveTopicRepository, transactionManager = transactionManager)

    @Bean
    @Primary
    fun cveEventRepository(jpaCveEventRepository: JpaCveEventRepository) =
        CveEventRepositoryImpl(jpaCveEventRepository = jpaCveEventRepository)

    @Bean
    @Primary
    fun cveSubscriptionRepository(
        jpaCveSubscriptionRepository: JpaCveSubscriptionRepository,
        jpaCveTopicRepository: JpaCveTopicRepository,
    ) = CveSubscriptionRepositoryImpl(
        jpaCveSubscriptionRepository = jpaCveSubscriptionRepository,
        jpaCveTopicRepository = jpaCveTopicRepository,
    )

    @Bean
    @Primary
    fun cveCollectLedgerRepository(jpaCveCollectLedgerRepository: JpaCveCollectLedgerRepository) =
        CveCollectLedgerRepositoryImpl(jpaCveCollectLedgerRepository = jpaCveCollectLedgerRepository)

    @Bean
    @Primary
    fun cveDeliveryRepository(jpaCveDeliveryRepository: JpaCveDeliveryRepository) =
        CveDeliveryRepositoryImpl(jpaCveDeliveryRepository = jpaCveDeliveryRepository)

    @Bean
    @Primary
    fun standupRepository(
        jpaRoutineRepository: JpaRoutineRepository,
        jpaStandupSessionRepository: JpaStandupSessionRepository,
        jpaSessionDispatchRepository: JpaSessionDispatchRepository,
    ) = StandupRepositoryImpl(
        jpaRoutineRepository = jpaRoutineRepository,
        jpaStandupSessionRepository = jpaStandupSessionRepository,
        jpaSessionDispatchRepository = jpaSessionDispatchRepository,
    )
}
