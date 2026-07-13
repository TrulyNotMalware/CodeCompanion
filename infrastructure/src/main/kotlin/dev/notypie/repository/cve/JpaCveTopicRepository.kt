package dev.notypie.repository.cve

import dev.notypie.repository.cve.schema.CveTopicSchema
import org.springframework.data.jpa.repository.JpaRepository

interface JpaCveTopicRepository : JpaRepository<CveTopicSchema, Long> {
    fun findByTopicKey(topicKey: String): CveTopicSchema?

    fun findByActiveTrueOrderByTopicKey(): List<CveTopicSchema>
}
