package dev.notypie.domain.standup.entity

import dev.notypie.domain.common.validate
import dev.notypie.domain.standup.entity.enums.DispatchStatus
import java.time.Instant

class SessionDispatch(
    val userId: String,
    val dmTriggerAt: Instant,
    val dmSentAt: Instant? = null,
    val dmStatus: DispatchStatus = DispatchStatus.PENDING,
    val failureReason: String? = null,
) {
    init {
        validate(className = this.javaClass.simpleName) {
            notBlank {
                "userId" of userId
            }
            ("dmSentAt" of dmSentAt).shouldSatisfy("SENT dispatch must record dmSentAt") {
                dmStatus != DispatchStatus.SENT || it != null
            }
            ("failureReason" of failureReason).shouldSatisfy("FAILED dispatch must record a non-blank failureReason") {
                dmStatus != DispatchStatus.FAILED || !it.isNullOrBlank()
            }
        }
    }
}
