package dev.notypie.domain.command.entity.context.form

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.interactions.InteractionPayload
import dev.notypie.domain.command.dto.interactions.States
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.ReactionContext
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.intent.IntentQueue
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId

/**
 * Parses the `/standup setup` modal `view_submission` into a [CommandIntent.CreateStandupRoutine].
 * Fields are located by their declared `block_id` (see `StandupSetupModalIds`) rather than by
 * iteration order, because Slack's `view.state.values` map is unordered. The creatorId and the
 * originating command channel ride along in the modal's `private_metadata` and surface as
 * `routingExtras[0]`/`[1]`.
 *
 * Validation of the assembled [dev.notypie.domain.standup.entity.Routine] (question count,
 * weekday presence, positive cutoff, etc.) is deferred to the application-layer service that
 * constructs the entity — its `init` block is the single source of truth, so duplicating the
 * rules here would risk drift.
 */
internal class StandupSetupSubmissionContext(
    commandBasicInfo: CommandBasicInfo,
    requestHeaders: SlackRequestHeaders = SlackRequestHeaders(),
    subCommand: SubCommand<NoSubCommands> = SubCommand.empty(),
    intents: IntentQueue,
) : ReactionContext<NoSubCommands>(
        requestHeaders = requestHeaders,
        commandBasicInfo = commandBasicInfo,
        subCommand = subCommand,
        intents = intents,
    ) {
    override fun parseCommandType(): CommandType = CommandType.PIPELINE

    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.STANDUP_SETUP_SUBMIT

    override fun handleInteraction(interactionPayload: InteractionPayload): CommandOutput {
        val creatorId =
            interactionPayload.routingExtras
                .getOrNull(0)
                ?.takeIf { it.isNotBlank() }
                ?: interactionPayload.user.id
        val commandChannel = interactionPayload.routingExtras.getOrNull(1).orEmpty()

        val name = selectedValueOf(payload = interactionPayload, blockId = NAME_BLOCK_ID).trim()
        val questions =
            selectedValueOf(payload = interactionPayload, blockId = QUESTIONS_BLOCK_ID)
                .split("\n")
                .map { it.trim() }
                .filter { it.isNotBlank() }
        val memberIds =
            selectedValueOf(payload = interactionPayload, blockId = MEMBERS_BLOCK_ID)
                .split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
        val summaryChannel = selectedValueOf(payload = interactionPayload, blockId = SUMMARY_CHANNEL_BLOCK_ID).trim()
        val weekdays =
            selectedValueOf(payload = interactionPayload, blockId = WEEKDAYS_BLOCK_ID)
                .split(",")
                .mapNotNull { token -> runCatching { DayOfWeek.valueOf(token.trim()) }.getOrNull() }
                .toSet()
        val triggerLocalTime = parseTriggerTime(payload = interactionPayload)
        val cutoffMinutes = parseCutoffMinutes(payload = interactionPayload)
        val timezone = parseTimezone(payload = interactionPayload)

        addIntent(
            CommandIntent.CreateStandupRoutine(
                name = name,
                creatorId = creatorId,
                commandChannel = commandChannel,
                summaryChannel = summaryChannel,
                questions = questions,
                memberIds = memberIds,
                weekdays = weekdays,
                triggerLocalTime = triggerLocalTime,
                cutoffMinutes = cutoffMinutes,
                timezone = timezone,
            ),
        )
        return CommandOutput.success(
            basicInfo = commandBasicInfo,
            commandType = commandType,
            commandDetailType = commandDetailType,
        )
    }

    private fun selectedValueOf(payload: InteractionPayload, blockId: String): String =
        stateOf(payload = payload, blockId = blockId)?.selectedValue.orEmpty()

    private fun stateOf(payload: InteractionPayload, blockId: String): States? =
        payload.states.firstOrNull { it.blockId == blockId }

    private fun parseTriggerTime(payload: InteractionPayload): LocalTime {
        val raw = selectedValueOf(payload = payload, blockId = TIME_BLOCK_ID)
        return runCatching { LocalTime.parse(raw) }.getOrDefault(DEFAULT_TRIGGER_TIME)
    }

    private fun parseCutoffMinutes(payload: InteractionPayload): Long {
        val raw = selectedValueOf(payload = payload, blockId = CUTOFF_BLOCK_ID).trim()
        return raw.toLongOrNull() ?: DEFAULT_CUTOFF_MINUTES
    }

    private fun parseTimezone(payload: InteractionPayload): ZoneId {
        val raw = selectedValueOf(payload = payload, blockId = TIMEZONE_BLOCK_ID).trim()
        return runCatching { ZoneId.of(raw) }.getOrDefault(DEFAULT_TIMEZONE)
    }

    companion object {
        // Mirrors StandupSetupModalIds in the infrastructure layer. Kept as plain constants here
        // so the domain module stays free of templating dependencies.
        const val NAME_BLOCK_ID: String = "standup_setup_name"
        const val QUESTIONS_BLOCK_ID: String = "standup_setup_questions"
        const val MEMBERS_BLOCK_ID: String = "standup_setup_members"
        const val SUMMARY_CHANNEL_BLOCK_ID: String = "standup_setup_summary_channel"
        const val WEEKDAYS_BLOCK_ID: String = "standup_setup_weekdays"
        const val TIME_BLOCK_ID: String = "standup_setup_time"
        const val CUTOFF_BLOCK_ID: String = "standup_setup_cutoff"
        const val TIMEZONE_BLOCK_ID: String = "standup_setup_timezone"

        const val DEFAULT_CUTOFF_MINUTES: Long = 120L
        private val DEFAULT_TRIGGER_TIME: LocalTime = LocalTime.of(10, 0)
        private val DEFAULT_TIMEZONE: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
