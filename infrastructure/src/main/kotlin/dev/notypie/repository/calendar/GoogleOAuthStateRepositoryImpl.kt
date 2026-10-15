package dev.notypie.repository.calendar

import dev.notypie.repository.calendar.schema.GoogleOAuthStateSchema
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

open class GoogleOAuthStateRepositoryImpl(
    private val jpaGoogleOAuthStateRepository: JpaGoogleOAuthStateRepository,
) : GoogleOAuthStateRepository {
    @Transactional
    override fun issue(state: String, userId: String, expiresAt: Instant) {
        jpaGoogleOAuthStateRepository.save(
            GoogleOAuthStateSchema(state = state, slackUserId = userId, expiresAt = expiresAt),
        )
    }

    @Transactional
    override fun consume(state: String, now: Instant): String? {
        if (jpaGoogleOAuthStateRepository.consume(state = state, now = now) != 1) return null
        return jpaGoogleOAuthStateRepository.findByIdOrNull(id = state)?.slackUserId
    }

    @Transactional
    override fun deleteExpired(before: Instant): Int =
        jpaGoogleOAuthStateRepository.deleteExpiredBefore(before = before)

    @Transactional
    override fun deleteForUser(userId: String): Int = jpaGoogleOAuthStateRepository.deleteBySlackUser(userId = userId)
}
