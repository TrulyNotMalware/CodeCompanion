package dev.notypie.application.service.interaction

import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.inbound.SubmissionIgnoreReason
import dev.notypie.domain.command.inbound.SubmissionParseObserver
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

// Raw form values never reach a tag or log line — keep tags low-cardinality and privacy-safe.
@Component
class MeteredSubmissionParseObserver(
    private val meterRegistry: MeterRegistry,
) : SubmissionParseObserver {
    private val logger = KotlinLogging.logger {}

    override fun ignored(detailType: CommandDetailType, reason: SubmissionIgnoreReason) {
        logger.debug { "Ignored view_submission: detailType=$detailType reason=$reason" }
        meterRegistry
            .counter(
                "codecompanion.submission.ignored",
                "detail_type",
                detailType.name,
                "reason",
                reason.name,
            ).increment()
    }
}
