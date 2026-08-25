package dev.notypie.repository.cve

import dev.notypie.repository.cve.schema.CveTopicSchema
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional

interface JpaCveTopicRepository : JpaRepository<CveTopicSchema, Long> {
    fun findByTopicKey(topicKey: String): CveTopicSchema?

    fun findByActiveTrueOrderByTopicKey(): List<CveTopicSchema>

    @Query("SELECT t FROM cve_topic t ORDER BY t.topicKey ASC")
    fun findAllOrderByTopicKey(): List<CveTopicSchema>

    @Query("SELECT COUNT(t) FROM cve_topic t WHERE t.active = true")
    fun countActive(): Long

    @Modifying
    @Transactional
    @Query("UPDATE cve_topic t SET t.active = :active WHERE t.topicKey = :topicKey")
    fun setActive(
        @Param("topicKey") topicKey: String,
        @Param("active") active: Boolean,
    ): Int
}
