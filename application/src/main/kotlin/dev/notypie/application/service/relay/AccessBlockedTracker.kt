package dev.notypie.application.service.relay

import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

// When the relay last held a row because Slack refused the bot's token or workspace (review F6/R3). The held rows are
// invisible to the outbox counts: defer takes their send back and moves updated_at past the stuck threshold, so they
// are neither retrying nor stuck, and readOutboxHealth reads this instead. In memory and per replica: a dead token
// refuses every replica's sends alike, each held row comes back every ACCESS_BLOCKED_DEFER, and a restarted replica
// learns it again from the first row it holds.
@Component
class AccessBlockedTracker {
    private val lastBlockedAt = AtomicReference<Instant?>(null)

    fun record(at: Instant) {
        lastBlockedAt.updateAndGet { previous -> if (previous == null || at > previous) at else previous }
    }

    fun lastBlockedAt(): Instant? = lastBlockedAt.get()
}
