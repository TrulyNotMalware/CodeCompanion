package dev.notypie.configurations

import com.zaxxer.hikari.HikariDataSource
import dev.notypie.repository.agent.AgentSessionRepositoryImpl
import dev.notypie.repository.agent.JpaAgentSessionRepository
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
import dev.notypie.repository.user.JpaTeamEntityRepository
import dev.notypie.repository.user.JpaTeamRepository
import dev.notypie.repository.user.JpaUserEntityRepository
import dev.notypie.repository.user.JpaUserRepository
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy

const val PRIMARY_DATASOURCE_CONFIG = "primaryPersistenceUnit"
const val JPA_ENTITY_PACKAGES = "dev.notypie.repository"

@Configuration
@EnableJpaRepositories(basePackages = [JPA_ENTITY_PACKAGES])
class JpaConfiguration {
    @Bean
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
    @Primary
    fun userRepository(
        jpaUserEntityRepository: JpaUserEntityRepository,
        jpaTeamEntityRepository: JpaTeamEntityRepository,
    ) = JpaUserRepository(
        jpaUserEntityRepository = jpaUserEntityRepository,
        jpaTeamEntityRepository = jpaTeamEntityRepository,
    )

    @Bean
    @Primary
    fun teamRepository(jpaTeamEntityRepository: JpaTeamEntityRepository) =
        JpaTeamRepository(teamRepository = jpaTeamEntityRepository)

    @Bean
    @Primary
    fun meetingRepository(jpaMeetingRepository: JpaMeetingRepository) =
        MeetingRepositoryImpl(jpaMeetingRepository = jpaMeetingRepository)

    @Bean
    @Primary
    fun meetingReminderRepository(
        jpaMeetingRepository: JpaMeetingRepository,
        jpaMeetingReminderRepository: JpaMeetingReminderRepository,
    ) = MeetingReminderRepositoryImpl(
        jpaMeetingRepository = jpaMeetingRepository,
        jpaMeetingReminderRepository = jpaMeetingReminderRepository,
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
