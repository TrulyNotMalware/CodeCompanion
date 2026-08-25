package dev.notypie.domain.command.inbound

import dev.notypie.domain.command.entity.CommandDetailType

/** Opaque transport handle for opening modals (was Slack `trigger_id`). */
@JvmInline
value class TriggerHandle(
    val raw: String,
)

/** Opaque transport handle for replacing the originating message (was Slack `response_url`). */
@JvmInline
value class ReplyHandle(
    val raw: String,
)

/** Opaque transport handle for updating a specific message (was Slack `container.message_ts`). */
@JvmInline
value class MessageHandle(
    val raw: String,
)

/** The actor who triggered the interaction. Only the id is consumed downstream. */
data class InboundActor(
    val id: String,
)

/**
 * Neutral role of the action that produced this interaction. [triggersEvent] mirrors the old
 * Slack primary-element flag: any role that can run a command-side effect is primary.
 */
enum class InboundActionRole(
    val triggersEvent: Boolean,
) {
    APPROVE(true), // was APPLY_BUTTON
    REJECT(true), // was REJECT_BUTTON (primary AND cancel)
    ACTIVATE(true), // was neutral BUTTON
    PASSIVE(false), // selects / UNKNOWN current action
}

/** The action that produced the interaction (button click or synthesized submit). */
data class InboundAction(
    val role: InboundActionRole,
    val isSelected: Boolean,
)

/**
 * Neutral kind of a submitted form field. [alwaysComplete] mirrors the old completion rule where a
 * PLAIN_TEXT_INPUT and a CHECKBOX are treated as answered even when not explicitly "selected".
 */
enum class InboundFieldKind(
    val alwaysComplete: Boolean,
) {
    TEXT(true), // PLAIN_TEXT_INPUT
    DATE(false), // DATE_PICKER
    TIME(false), // TIME_PICKER
    CHOICE(false), // STATIC_SELECT (+ RADIO_BUTTONS)
    MULTI_CHOICE(false), // MULTI_STATIC_SELECT
    USERS(false), // MULTI_USERS_SELECT
    CONVERSATION(false), // CONVERSATIONS_SELECT
    TOGGLE(true), // CHECKBOX
    UNKNOWN(false),
}

/** A single submitted form field, keyed by its declared block id when available. */
data class InboundField(
    val key: String?, // was the Slack block id
    val kind: InboundFieldKind,
    val isSelected: Boolean,
    val rawValue: String, // was the Slack selected value (comma-joined already)
)

/** Order-preserving collection of submitted fields (order == the inbound adapter/parser order). */
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

/**
 * Field-identity contract shared between the modal template (writer, infra `InteractiveIds`) and the
 * submission contexts (reader). The literal values are the field block ids carried on the wire;
 * co-locating them here makes this the single source of truth so the writer and the domain readers
 * can no longer drift apart (a drift silently degrades a field read to empty).
 */
object InboundFieldKeys {
    // add-participant modal
    const val ADD_PARTICIPANT_USERS: String = "add_participant_users"

    // standup answer modal — per-question fields are this prefix joined with the question index
    const val STANDUP_ANSWER_QUESTION_PREFIX: String = "standup_q_"

    // standup setup modal
    const val STANDUP_SETUP_NAME: String = "standup_setup_name"
    const val STANDUP_SETUP_QUESTIONS: String = "standup_setup_questions"
    const val STANDUP_SETUP_MEMBERS: String = "standup_setup_members"
    const val STANDUP_SETUP_SUMMARY_CHANNEL: String = "standup_setup_summary_channel"
    const val STANDUP_SETUP_WEEKDAYS: String = "standup_setup_weekdays"
    const val STANDUP_SETUP_TIME: String = "standup_setup_time"
    const val STANDUP_SETUP_CUTOFF: String = "standup_setup_cutoff"
    const val STANDUP_SETUP_TIMEZONE: String = "standup_setup_timezone"

    // cve subscribe / unsubscribe modals — the topic multi-select block ids
    const val CVE_SUBSCRIBE_TOPICS: String = "cve_subscribe_topics"
    const val CVE_UNSUBSCRIBE_TOPICS: String = "cve_unsubscribe_topics"
}

/**
 * Typed, semantic per-flow projection of a `view_submission`. The infra mapper resolves the
 * positional `routingExtras`/block-id reads into named fields once, so each domain context consumes
 * its own variant by field name and keeps only its interpretation policy (uid parsing, date/time
 * combine, RejectReason parsing, defaults, string splits). Null for block_actions interactions,
 * which carry no submission.
 */
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

    /** Topic keys selected in the `/subscribe` modal (already split from the multi-select). */
    data class CveSubscribe(
        val topicKeys: List<String>,
    ) : InboundSubmission

    /** Topic keys selected in the `/unsubscribe` modal (already split from the multi-select). */
    data class CveUnsubscribe(
        val topicKeys: List<String>,
    ) : InboundSubmission
}

/** Transport-neutral interaction consumed by domain contexts. */
data class InboundInteraction(
    val detailType: CommandDetailType, // was type
    // NOTE: also an InboundPayload so it can ride directly in an InboundCommand envelope.
    val actor: InboundActor,
    val channelId: String, // was channel.id
    val trigger: TriggerHandle, // was triggerId
    val reply: ReplyHandle, // was responseUrl
    val message: MessageHandle?, // was container.messageTs (nullable)
    val idempotencyKey: String,
    val routingExtras: List<String> = emptyList(),
    val form: InboundForm,
    val action: InboundAction,
    // Typed per-flow view_submission projection; null for block_actions interactions.
    val submission: InboundSubmission? = null,
) : InboundPayload

fun InboundInteraction.isPrimary(): Boolean = action.role.triggersEvent

fun InboundInteraction.isCanceled(): Boolean = action.role == InboundActionRole.REJECT

/** Equivalent to the old Slack payload completion check. */
fun InboundInteraction.isComplete(): Boolean =
    action.role.triggersEvent &&
        action.isSelected &&
        form.fields.all { it.isSelected || it.kind.alwaysComplete }
