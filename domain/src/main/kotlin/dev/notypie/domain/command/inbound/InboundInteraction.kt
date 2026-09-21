package dev.notypie.domain.command.inbound

import dev.notypie.domain.command.entity.CommandDetailType

@JvmInline
value class TriggerHandle(
    val raw: String,
)

@JvmInline
value class ReplyHandle(
    val raw: String,
)

@JvmInline
value class MessageHandle(
    val raw: String,
)

data class InboundActor(
    val id: String,
)

enum class InboundActionRole(
    val triggersEvent: Boolean,
) {
    APPROVE(true),
    REJECT(true),
    ACTIVATE(true),
    PASSIVE(false),
}

data class InboundAction(
    val role: InboundActionRole,
    val isSelected: Boolean,
)

enum class InboundFieldKind(
    val alwaysComplete: Boolean,
) {
    TEXT(true),
    DATE(false),
    TIME(false),
    CHOICE(false),
    MULTI_CHOICE(false),
    USERS(false),
    CONVERSATION(false),
    TOGGLE(true),
    UNKNOWN(false),
}

data class InboundField(
    val key: String?,
    val kind: InboundFieldKind,
    val isSelected: Boolean,
    val rawValue: String, // comma-joined already when the field is a multi-select
)

class InboundForm(
    val fields: List<InboundField>,
) {
    fun field(key: String): InboundField? = fields.firstOrNull { it.key == key }

    fun value(key: String): String = field(key)?.rawValue.orEmpty()

    fun isSelected(key: String): Boolean = field(key)?.isSelected ?: false

    fun first(kind: InboundFieldKind): InboundField? = fields.firstOrNull { it.kind == kind }

    fun all(kind: InboundFieldKind): List<InboundField> = fields.filter { it.kind == kind }

    fun firstValue(kind: InboundFieldKind): String? = first(kind)?.rawValue
}

// Ids must match the modal template writer's block ids; drift silently empties the field read.
object InboundFieldKeys {
    const val ADD_PARTICIPANT_USERS: String = "add_participant_users"

    const val STANDUP_ANSWER_QUESTION_PREFIX: String = "standup_q_"

    const val STANDUP_SETUP_NAME: String = "standup_setup_name"
    const val STANDUP_SETUP_QUESTIONS: String = "standup_setup_questions"
    const val STANDUP_SETUP_MEMBERS: String = "standup_setup_members"
    const val STANDUP_SETUP_SUMMARY_CHANNEL: String = "standup_setup_summary_channel"
    const val STANDUP_SETUP_WEEKDAYS: String = "standup_setup_weekdays"
    const val STANDUP_SETUP_TIME: String = "standup_setup_time"
    const val STANDUP_SETUP_CUTOFF: String = "standup_setup_cutoff"
    const val STANDUP_SETUP_TIMEZONE: String = "standup_setup_timezone"

    const val CVE_SUBSCRIBE_TOPICS: String = "cve_subscribe_topics"
    const val CVE_UNSUBSCRIBE_TOPICS: String = "cve_unsubscribe_topics"
}

sealed interface InboundSubmission {
    data class RescheduleMeeting(
        val meetingUidRaw: String,
        val requesterId: String,
        val date: String,
        val time: String,
    ) : InboundSubmission

    data class AddParticipant(
        val meetingUidRaw: String,
        val requesterId: String,
        val participantUserIdsRaw: String,
    ) : InboundSubmission

    data class DeclineReason(
        val meetingIdempotencyKeyRaw: String,
        val participantUserId: String,
        val noticeChannel: String,
        val noticeMessageTs: String,
        val reasonRaw: String,
        val detailRaw: String,
    ) : InboundSubmission

    data class StandupAnswer(
        val sessionUidRaw: String,
        val userId: String,
        val noticeChannel: String,
        val noticeMessageTs: String,
        val answers: List<String>,
    ) : InboundSubmission

    data class StandupSetup(
        val idempotencyKeyRaw: String,
        val creatorId: String,
        val commandChannel: String,
        val name: String,
        val questionsRaw: String,
        val membersRaw: String,
        val summaryChannel: String,
        val weekdaysRaw: String,
        val timeRaw: String,
        val cutoffRaw: String,
        val timezoneRaw: String,
    ) : InboundSubmission

    data class CveSubscribe(
        val topicKeys: List<String>,
    ) : InboundSubmission

    data class CveUnsubscribe(
        val topicKeys: List<String>,
    ) : InboundSubmission
}

data class InboundInteraction(
    val detailType: CommandDetailType,
    val actor: InboundActor,
    val channelId: String,
    val trigger: TriggerHandle,
    val reply: ReplyHandle,
    val message: MessageHandle?,
    val idempotencyKey: String,
    val routingExtras: List<String> = emptyList(),
    val form: InboundForm,
    val action: InboundAction,
    val submission: InboundSubmission? = null,
) : InboundPayload

fun InboundInteraction.isPrimary(): Boolean = action.role.triggersEvent

fun InboundInteraction.isCanceled(): Boolean = action.role == InboundActionRole.REJECT

fun InboundInteraction.isComplete(): Boolean =
    action.role.triggersEvent &&
        action.isSelected &&
        form.fields.all { it.isSelected || it.kind.alwaysComplete }
