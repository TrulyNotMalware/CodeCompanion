package dev.notypie.impl.command

import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.inbound.InboundAction
import dev.notypie.domain.command.inbound.InboundActionRole
import dev.notypie.domain.command.inbound.InboundActor
import dev.notypie.domain.command.inbound.InboundCommand
import dev.notypie.domain.command.inbound.InboundField
import dev.notypie.domain.command.inbound.InboundFieldKeys
import dev.notypie.domain.command.inbound.InboundFieldKind
import dev.notypie.domain.command.inbound.InboundForm
import dev.notypie.domain.command.inbound.InboundInteraction
import dev.notypie.domain.command.inbound.InboundKind
import dev.notypie.domain.command.inbound.InboundSubmission
import dev.notypie.domain.command.inbound.MessageHandle
import dev.notypie.domain.command.inbound.ReplyHandle
import dev.notypie.domain.command.inbound.TriggerHandle
import dev.notypie.impl.command.slack.ActionElementTypes
import dev.notypie.impl.command.slack.InteractionPayload
import dev.notypie.impl.command.slack.States

/**
 * Adapts the Slack-shaped [InteractionPayload] into the transport-neutral [InboundInteraction] the
 * domain contexts consume. The order of [InboundForm.fields] mirrors the parser's `states` order,
 * which several contexts read positionally (e.g. the meeting form's start/end TIME pickers).
 */
fun InteractionPayload.toInbound(): InboundInteraction {
    val form = InboundForm(fields = states.map { it.toInboundField() })
    return InboundInteraction(
        detailType = type,
        actor = InboundActor(id = user.id),
        channelId = channel.id,
        trigger = TriggerHandle(raw = triggerId),
        reply = ReplyHandle(raw = responseUrl),
        message = container.messageTs?.let { MessageHandle(raw = it) },
        idempotencyKey = idempotencyKey,
        routingExtras = routingExtras,
        form = form,
        action = currentAction.toInboundAction(),
        submission = buildSubmission(form = form),
    )
}

/**
 * Resolves the positional `routingExtras`/block-id reads into a typed per-flow [InboundSubmission]
 * so the domain contexts consume named fields and keep only their interpretation policy. Mirrors the
 * extraction each `view_submission` context previously performed inline; unrelated types yield null.
 */
private fun InteractionPayload.buildSubmission(form: InboundForm): InboundSubmission? =
    when (type) {
        CommandDetailType.MEETING_RESCHEDULE_SUBMIT ->
            InboundSubmission.RescheduleMeeting(
                meetingUidRaw = idempotencyKey,
                requesterId = routingExtras.getOrNull(0).orEmpty(),
                date = form.firstNonBlankValue(kind = InboundFieldKind.DATE),
                time = form.firstNonBlankValue(kind = InboundFieldKind.TIME),
            )

        CommandDetailType.MEETING_ADD_PARTICIPANT_SUBMIT ->
            InboundSubmission.AddParticipant(
                meetingUidRaw = idempotencyKey,
                requesterId = routingExtras.getOrNull(0).orEmpty(),
                participantUserIdsRaw = form.value(key = InboundFieldKeys.ADD_PARTICIPANT_USERS),
            )

        CommandDetailType.MEETING_DECLINE_REASON ->
            InboundSubmission.DeclineReason(
                meetingIdempotencyKeyRaw = idempotencyKey,
                participantUserId = routingExtras.getOrNull(0).orEmpty(),
                noticeChannel = routingExtras.getOrNull(1).orEmpty(),
                noticeMessageTs = routingExtras.getOrNull(2).orEmpty(),
                reasonRaw = form.firstValue(kind = InboundFieldKind.CHOICE).orEmpty(),
                detailRaw = form.firstValue(kind = InboundFieldKind.TEXT).orEmpty(),
            )

        CommandDetailType.STANDUP_ANSWER_SUBMIT ->
            InboundSubmission.StandupAnswer(
                sessionUidRaw = idempotencyKey,
                userId = routingExtras.getOrNull(0).orEmpty(),
                noticeChannel = routingExtras.getOrNull(1).orEmpty(),
                noticeMessageTs = routingExtras.getOrNull(2).orEmpty(),
                // view.state.values arrives unordered; sort by the `standup_q_<index>` block id so
                // answers[i] stays aligned with the routine's questions[i].
                answers =
                    form
                        .all(kind = InboundFieldKind.TEXT)
                        .sortedBy { field -> standupAnswerIndex(blockId = field.key) }
                        .map { it.rawValue.trim() },
            )

        CommandDetailType.STANDUP_SETUP_SUBMIT ->
            InboundSubmission.StandupSetup(
                idempotencyKeyRaw = idempotencyKey,
                creatorId = routingExtras.getOrNull(0).orEmpty(),
                commandChannel = routingExtras.getOrNull(1).orEmpty(),
                name = form.value(key = InboundFieldKeys.STANDUP_SETUP_NAME),
                questionsRaw = form.value(key = InboundFieldKeys.STANDUP_SETUP_QUESTIONS),
                membersRaw = form.value(key = InboundFieldKeys.STANDUP_SETUP_MEMBERS),
                summaryChannel = form.value(key = InboundFieldKeys.STANDUP_SETUP_SUMMARY_CHANNEL),
                weekdaysRaw = form.value(key = InboundFieldKeys.STANDUP_SETUP_WEEKDAYS),
                timeRaw = form.value(key = InboundFieldKeys.STANDUP_SETUP_TIME),
                cutoffRaw = form.value(key = InboundFieldKeys.STANDUP_SETUP_CUTOFF),
                timezoneRaw = form.value(key = InboundFieldKeys.STANDUP_SETUP_TIMEZONE),
            )

        CommandDetailType.CVE_SUBSCRIBE_SUBMIT ->
            InboundSubmission.CveSubscribe(
                topicKeys = form.selectedTopicKeys(key = InboundFieldKeys.CVE_SUBSCRIBE_TOPICS),
            )

        CommandDetailType.CVE_UNSUBSCRIBE_SUBMIT ->
            InboundSubmission.CveUnsubscribe(
                topicKeys = form.selectedTopicKeys(key = InboundFieldKeys.CVE_UNSUBSCRIBE_TOPICS),
            )

        else -> null
    }

/** Splits a multi-select's comma-joined raw value (the parser joins with `, `) into distinct keys. */
private fun InboundForm.selectedTopicKeys(key: String): List<String> =
    value(key = key)
        .split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() }

/** First non-blank raw value for [kind], mirroring the reschedule context's date/time selection. */
private fun InboundForm.firstNonBlankValue(kind: InboundFieldKind): String =
    all(kind = kind).firstOrNull { it.rawValue.isNotBlank() }?.rawValue.orEmpty()

/** Trailing index of a `standup_q_<index>` block id; unparseable ids sort last via [Int.MAX_VALUE]. */
private fun standupAnswerIndex(blockId: String?): Int {
    val tail = blockId?.removePrefix(InboundFieldKeys.STANDUP_ANSWER_QUESTION_PREFIX)
    return tail?.toIntOrNull() ?: Int.MAX_VALUE
}

fun States.toInboundField(): InboundField =
    InboundField(
        key = blockId,
        kind = type.toInboundFieldKind(),
        isSelected = isSelected,
        rawValue = selectedValue,
    )

fun States.toInboundAction(): InboundAction =
    InboundAction(
        role =
            when (type) {
                ActionElementTypes.APPLY_BUTTON -> InboundActionRole.APPROVE
                ActionElementTypes.REJECT_BUTTON -> InboundActionRole.REJECT
                ActionElementTypes.BUTTON -> InboundActionRole.ACTIVATE
                else -> InboundActionRole.PASSIVE
            },
        isSelected = isSelected,
    )

private fun ActionElementTypes.toInboundFieldKind(): InboundFieldKind =
    when (this) {
        ActionElementTypes.PLAIN_TEXT_INPUT -> InboundFieldKind.TEXT
        ActionElementTypes.STATIC_SELECT, ActionElementTypes.RADIO_BUTTONS -> InboundFieldKind.CHOICE
        ActionElementTypes.MULTI_STATIC_SELECT -> InboundFieldKind.MULTI_CHOICE
        ActionElementTypes.MULTI_USERS_SELECT -> InboundFieldKind.USERS
        ActionElementTypes.CONVERSATIONS_SELECT -> InboundFieldKind.CONVERSATION
        ActionElementTypes.DATE_PICKER -> InboundFieldKind.DATE
        ActionElementTypes.TIME_PICKER -> InboundFieldKind.TIME
        ActionElementTypes.CHECKBOX -> InboundFieldKind.TOGGLE
        else -> InboundFieldKind.UNKNOWN
    }

/**
 * Boundary translation from the parsed Slack payload into the queue-carried [InboundCommand].
 * The neutral [InboundInteraction] rides directly in [InboundCommand.payload]; identity fields keep
 * reading the Slack payload directly (this runs in the application/infra layer, not the domain).
 */
fun InteractionPayload.toInboundCommand() =
    InboundCommand(
        appId = apiAppId,
        appToken = token,
        actorId = user.id,
        actorName = user.name,
        channel = channel.id,
        channelName = channel.name,
        payload = toInbound(),
        kind = InboundKind.INTERACTION,
        teamId = team.id,
    )
