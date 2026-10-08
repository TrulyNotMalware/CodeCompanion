package dev.notypie.repository.calendar

import java.time.Instant

interface GoogleOAuthStateRepository {
    fun issue(state: String, userId: String, expiresAt: Instant)

    fun consume(state: String, now: Instant): String?

    fun deleteExpired(before: Instant): Int

    fun deleteForUser(userId: String): Int
}
