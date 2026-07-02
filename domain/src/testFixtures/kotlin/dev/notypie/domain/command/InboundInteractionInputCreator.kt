package dev.notypie.domain.command

import dev.notypie.domain.TEST_BASE_URL
import dev.notypie.domain.TEST_CHANNEL_ID
import dev.notypie.domain.TEST_USER_ID
import dev.notypie.domain.TEST_USER_NAME
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.inbound.InboundAction
import dev.notypie.domain.command.inbound.InboundActionRole
import dev.notypie.domain.command.inbound.InboundActor
import dev.notypie.domain.command.inbound.InboundField
import dev.notypie.domain.command.inbound.InboundFieldKind
import dev.notypie.domain.command.inbound.InboundForm
import dev.notypie.domain.command.inbound.InboundInteraction
import dev.notypie.domain.command.inbound.InboundSubmission
import dev.notypie.domain.command.inbound.MessageHandle
import dev.notypie.domain.command.inbound.ReplyHandle
import dev.notypie.domain.command.inbound.TriggerHandle
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

const val SEPARATOR = ","

/**
 * Builds a transport-neutral [InboundInteraction] directly, with sensible defaults. Domain context
 * tests construct their input through this factory (plus the field/action helpers below) instead of
 * building a Slack-shaped payload, keeping the domain free of any Slack interaction types.
 */
fun createInboundInteraction(
    detailType: CommandDetailType = CommandDetailType.NOTHING,
    action: InboundAction = passiveAction(),
    form: List<InboundField> = emptyList(),
    actor: InboundActor = InboundActor(id = TEST_USER_ID),
    channelId: String = TEST_CHANNEL_ID,
    trigger: TriggerHandle = TriggerHandle(raw = ""),
    reply: ReplyHandle = ReplyHandle(raw = TEST_BASE_URL),
    message: MessageHandle? = null,
    idempotencyKey: UUID = UUID.randomUUID(),
    routingExtras: List<String> = emptyList(),
    submission: InboundSubmission? = null,
): InboundInteraction =
    InboundInteraction(
        detailType = detailType,
        actor = actor,
        channelId = channelId,
        trigger = trigger,
        reply = reply,
        message = message,
        idempotencyKey = idempotencyKey.toString(),
        routingExtras = routingExtras,
        form = InboundForm(fields = form),
        action = action,
        submission = submission,
    )

fun approveAction(isSelected: Boolean = true) = InboundAction(role = InboundActionRole.APPROVE, isSelected = isSelected)

fun rejectAction(isSelected: Boolean = true) = InboundAction(role = InboundActionRole.REJECT, isSelected = isSelected)

fun passiveAction(isSelected: Boolean = false) =
    InboundAction(role = InboundActionRole.PASSIVE, isSelected = isSelected)

fun inboundField(
    kind: InboundFieldKind,
    rawValue: String = "",
    isSelected: Boolean = false,
    key: String? = null,
) = InboundField(key = key, kind = kind, isSelected = isSelected, rawValue = rawValue)

/**
 * Neutral projection of a selected primary (APPLY) button when it appears inside a form's fields.
 * A button carries no field semantics, so it maps to an [InboundFieldKind.UNKNOWN] field — the same
 * shape the production mapper produces for a non-input element.
 */
fun applyButtonField() = inboundField(kind = InboundFieldKind.UNKNOWN, isSelected = true, rawValue = "apply")

fun rejectButtonField() = inboundField(kind = InboundFieldKind.UNKNOWN, isSelected = true)

fun plainTextField(text: String) = inboundField(kind = InboundFieldKind.TEXT, isSelected = true, rawValue = text)

fun datePickerField(date: LocalDate, format: String) =
    inboundField(
        kind = InboundFieldKind.DATE,
        isSelected = true,
        rawValue = date.format(DateTimeFormatter.ofPattern(format)),
    )

fun timePickerField(time: LocalTime, format: String) =
    inboundField(
        kind = InboundFieldKind.TIME,
        isSelected = true,
        rawValue = time.format(DateTimeFormatter.ofPattern(format)),
    )

fun multiUsersField(userName: String = TEST_USER_NAME, maximumSequence: Int = 0) =
    inboundField(
        kind = InboundFieldKind.USERS,
        isSelected = true,
        rawValue =
            if (maximumSequence > 0) {
                (1..maximumSequence).joinToString(SEPARATOR) { "$userName$it" }
            } else {
                userName
            },
    )
