package dev.notypie.application.service.calendar

import dev.notypie.application.service.standup.containFailure
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.impl.calendar.GoogleOAuthClient
import dev.notypie.impl.calendar.TokenCipher
import dev.notypie.repository.calendar.GoogleCalendarConnectionRepository
import dev.notypie.repository.calendar.schema.CalendarConnection
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

private val log = KotlinLogging.logger {}

data class GoogleTokenRevocationRequested(
    val userId: String,
    val googleSubject: String?,
    val encryptedRefreshToken: String,
    val reason: String,
) {
    override fun toString(): String =
        "GoogleTokenRevocationRequested(userId=$userId, googleSubject=$googleSubject, reason=$reason)"
}

class GoogleTokenRevocationWorker(
    private val oauthClient: GoogleOAuthClient,
    private val tokenCipher: TokenCipher,
    private val outboundStager: OutboundMessageStager,
    private val eventPublisher: EventPublisher,
    private val connectionRepository: GoogleCalendarConnectionRepository,
    transactionManager: PlatformTransactionManager,
    private val executor: Executor,
) {
    companion object {
        const val MANUAL_REMOVAL_HINT: String =
            "Google did not confirm revoking CodeCompanion's calendar access. " +
                "You can remove it at https://myaccount.google.com/permissions."
    }

    private val noticeTemplate =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onRevocationRequested(event: GoogleTokenRevocationRequested) {
        try {
            executor.execute {
                containFailure(
                    onFailure = { exception ->
                        log.error(exception) {
                            "Google token revoke failed: userId=${event.userId} reason=${event.reason}"
                        }
                        notifyManualRemoval(userId = event.userId)
                    },
                ) {
                    revoke(event = event)
                }
            }
        } catch (rejected: RejectedExecutionException) {
            log.warn(rejected) { "Google token revoke queue is full: userId=${event.userId} reason=${event.reason}" }
            notifyManualRemoval(userId = event.userId)
        }
    }

    internal fun revoke(event: GoogleTokenRevocationRequested) {
        val current = connectionRepository.find(userId = event.userId)
        if (current != null && current.isActive && !current.isKnownToDifferFrom(subject = event.googleSubject)) {
            log.info { "Google token revoke skipped, the account is connected again: userId=${event.userId}" }
            return
        }
        val token =
            try {
                tokenCipher.decrypt(token = event.encryptedRefreshToken)
            } catch (exception: IllegalArgumentException) {
                log.warn(exception) { "Stored Google refresh token could not be decrypted: userId=${event.userId}" }
                notifyManualRemoval(userId = event.userId)
                return
            }
        if (oauthClient.revoke(token = token)) {
            log.info { "Google token revoked: userId=${event.userId} reason=${event.reason}" }
        } else {
            notifyManualRemoval(userId = event.userId)
        }
    }

    private fun CalendarConnection.isKnownToDifferFrom(subject: String?): Boolean =
        googleSubject != null && subject != null && googleSubject != subject

    private fun notifyManualRemoval(userId: String) {
        try {
            noticeTemplate.executeWithoutResult {
                outboundStager.stageCalendarDirectMessage(
                    userId = userId,
                    text = MANUAL_REMOVAL_HINT,
                    appId = "",
                    publisher = eventPublisher,
                )
            }
        } catch (exception: Exception) {
            log.error(exception) { "Failed to stage the manual-removal DM: userId=$userId" }
        }
    }
}
