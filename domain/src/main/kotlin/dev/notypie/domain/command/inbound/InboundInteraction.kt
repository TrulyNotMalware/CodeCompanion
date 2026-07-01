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
) : InboundPayload

fun InboundInteraction.isPrimary(): Boolean = action.role.triggersEvent

fun InboundInteraction.isCanceled(): Boolean = action.role == InboundActionRole.REJECT

/** Equivalent to the old Slack payload completion check. */
fun InboundInteraction.isComplete(): Boolean =
    action.role.triggersEvent &&
        action.isSelected &&
        form.fields.all { it.isSelected || it.kind.alwaysComplete }
