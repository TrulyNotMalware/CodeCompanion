package dev.notypie.application.configurations

import dev.notypie.domain.history.repository.HistoryRepository
import dev.notypie.repository.history.JpaHistoryEntityRepository
import dev.notypie.repository.history.JpaHistoryRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class HistoryConfiguration {

    @Bean
    @ConditionalOnMissingBean(HistoryRepository::class)
    fun historyRepository(jpaHistoryRepository: JpaHistoryEntityRepository)
    = JpaHistoryRepository(jpaHistoryRepository)
}