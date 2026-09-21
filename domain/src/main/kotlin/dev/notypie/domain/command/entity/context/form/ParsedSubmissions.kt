package dev.notypie.domain.command.entity.context.form

import dev.notypie.domain.command.inbound.InboundSubmission
import dev.notypie.domain.meet.entity.RejectReason
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

internal fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()

internal sealed interface NoticeTarget {
    data object None : NoticeTarget

    data class Update(
        val channel: String,
        val messageTs: String,
    ) : NoticeTarget

    companion object {
        fun of(channel: String, messageTs: String): NoticeTarget =
            if (channel.isNotBlank() && messageTs.isNotBlank()) {
                Update(channel = channel, messageTs = messageTs)
            } else {
                None
            }
    }
}

internal data class AddParticipantParsed(
    val meetingUid: UUID,
    val requesterId: String,
    val participantUserIds: List<String>,
) {
    companion object {
        fun from(raw: InboundSubmission.AddParticipant, actorId: String): AddParticipantParsed? {
            val meetingUid = raw.meetingUidRaw.toUuidOrNull() ?: return null
            val participants =
                raw.participantUserIdsRaw
                    .split(",")
                    .map(String::trim)
                    .filter(String::isNotBlank)
            return participants.takeIf { it.isNotEmpty() }?.let {
                AddParticipantParsed(
                    meetingUid = meetingUid,
                    requesterId = raw.requesterId.ifBlank { actorId },
                    participantUserIds = it,
                )
            }
        }
    }
}

internal data class RescheduleMeetingParsed(
    val meetingUid: UUID,
    val requesterId: String,
    val newStartAt: LocalDateTime,
) {
    companion object {
        internal const val DATE_PATTERN = "yyyy-MM-dd"
        internal const val TIME_PATTERN = "HH:mm"

        fun from(raw: InboundSubmission.RescheduleMeeting, actorId: String): RescheduleMeetingParsed? {
            val meetingUid = raw.meetingUidRaw.toUuidOrNull() ?: return null
            val newStartAt = parseNewStartAt(date = raw.date, time = raw.time) ?: return null
            return RescheduleMeetingParsed(
                meetingUid = meetingUid,
                requesterId = raw.requesterId.ifBlank { actorId },
                newStartAt = newStartAt,
            )
        }

        private fun parseNewStartAt(date: String, time: String): LocalDateTime? {
            if (date.isBlank() || time.isBlank()) return null
            return runCatching {
                LocalDateTime.parse(
                    "$date $time",
                    DateTimeFormatter.ofPattern("$DATE_PATTERN $TIME_PATTERN"),
                )
            }.getOrNull()
        }
    }
}

internal data class DeclineReasonParsed(
    val meetingIdempotencyKey: UUID,
    val participantUserId: String,
    val reason: RejectReason,
    val reasonDetail: String?,
    val notice: NoticeTarget,
) {
    fun noticeSummaryMarkdown(): String =
        buildString {
            append("You declined the meeting — *Reason:* ${reason.showMessage}")
            if (!reasonDetail.isNullOrBlank()) append(" — $reasonDetail")
        }

    companion object {
        fun from(raw: InboundSubmission.DeclineReason, actorId: String): DeclineReasonParsed? {
            val meetingIdempotencyKey = raw.meetingIdempotencyKeyRaw.toUuidOrNull() ?: return null
            val reason = parseReason(raw = raw.reasonRaw)
            return DeclineReasonParsed(
                meetingIdempotencyKey = meetingIdempotencyKey,
                participantUserId = raw.participantUserId.ifBlank { actorId },
                reason = reason,
                reasonDetail = raw.detailRaw.trim().takeIf { reason == RejectReason.OTHER },
                notice = NoticeTarget.of(channel = raw.noticeChannel, messageTs = raw.noticeMessageTs),
            )
        }

        private fun parseReason(raw: String): RejectReason =
            runCatching { RejectReason.valueOf(raw) }
                .getOrDefault(RejectReason.OTHER)
                .takeIf { it != RejectReason.ATTENDING } ?: RejectReason.OTHER
    }
}

internal data class StandupAnswerParsed(
    val sessionUid: UUID,
    val userId: String,
    val responses: List<String>,
    val notice: NoticeTarget,
) {
    companion object {
        fun from(raw: InboundSubmission.StandupAnswer, actorId: String): StandupAnswerParsed? {
            val sessionUid = raw.sessionUidRaw.toUuidOrNull() ?: return null
            return StandupAnswerParsed(
                sessionUid = sessionUid,
                userId = raw.userId.ifBlank { actorId },
                responses = raw.answers,
                notice = NoticeTarget.of(channel = raw.noticeChannel, messageTs = raw.noticeMessageTs),
            )
        }
    }
}

internal data class StandupSetupParsed(
    val name: String,
    val creatorId: String,
    val commandChannel: String,
    val summaryChannel: String,
    val questions: List<String>,
    val memberIds: List<String>,
    val weekdays: Set<DayOfWeek>,
    val triggerLocalTime: LocalTime,
    val cutoffMinutes: Long,
    val timezone: ZoneId,
) {
    companion object {
        const val DEFAULT_CUTOFF_MINUTES: Long = 120L
        private val DEFAULT_TRIGGER_TIME: LocalTime = LocalTime.of(10, 0)
        private val DEFAULT_TIMEZONE: ZoneId = ZoneId.of("Asia/Seoul")

        fun from(raw: InboundSubmission.StandupSetup, actorId: String): StandupSetupParsed =
            StandupSetupParsed(
                name = raw.name.trim(),
                creatorId = raw.creatorId.ifBlank { actorId },
                commandChannel = raw.commandChannel,
                summaryChannel = raw.summaryChannel.trim(),
                questions =
                    raw.questionsRaw
                        .split("\n")
                        .map(String::trim)
                        .filter(String::isNotBlank),
                memberIds =
                    raw.membersRaw
                        .split(",")
                        .map(String::trim)
                        .filter(String::isNotBlank),
                weekdays =
                    raw.weekdaysRaw
                        .split(",")
                        .mapNotNull { token -> runCatching { DayOfWeek.valueOf(token.trim()) }.getOrNull() }
                        .toSet(),
                triggerLocalTime = runCatching { LocalTime.parse(raw.timeRaw) }.getOrDefault(DEFAULT_TRIGGER_TIME),
                cutoffMinutes = raw.cutoffRaw.trim().toLongOrNull() ?: DEFAULT_CUTOFF_MINUTES,
                timezone = runCatching { ZoneId.of(raw.timezoneRaw.trim()) }.getOrDefault(DEFAULT_TIMEZONE),
            )
    }
}

internal data class CveSubscribeParsed(
    val userId: String,
    val topicKeys: List<String>,
) {
    companion object {
        fun from(raw: InboundSubmission.CveSubscribe, actorId: String): CveSubscribeParsed =
            CveSubscribeParsed(userId = actorId, topicKeys = raw.topicKeys)
    }
}

internal data class CveUnsubscribeParsed(
    val userId: String,
    val topicKeys: List<String>,
) {
    companion object {
        fun from(raw: InboundSubmission.CveUnsubscribe, actorId: String): CveUnsubscribeParsed =
            CveUnsubscribeParsed(userId = actorId, topicKeys = raw.topicKeys)
    }
}
