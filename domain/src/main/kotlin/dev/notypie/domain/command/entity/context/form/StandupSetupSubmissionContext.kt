package dev.notypie.domain.command.entity.context.form

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.ReactionContext
import dev.notypie.domain.command.inbound.InboundInteraction
import dev.notypie.domain.command.inbound.InboundSubmission
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.intent.IntentQueue
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId

/**
 * Parses the `/standup setup` modal `view_submission` into a [CommandIntent.CreateStandupRoutine].
 * The inbound mapper resolves the semantic fields (name, questions, members, schedule) and the
 * creator/command-channel routing into a typed [InboundSubmission.StandupSetup]; this context keeps
 * only the interpretation policy (trimming, splits, weekday/time/cutoff/timezone parsing, defaults).
 *
 * Validation of the assembled [dev.notypie.domain.standup.entity.Routine] (question count,
 * weekday presence, positive cutoff, etc.) is deferred to the application-layer service that
 * constructs the entity — its `init` block is the single source of truth, so duplicating the
 * rules here would risk drift.
 */
internal class StandupSetupSubmissionContext(
    commandBasicInfo: CommandBasicInfo,
    subCommand: SubCommand<NoSubCommands> = SubCommand.empty(),
    intents: IntentQueue,
) : ReactionContext<NoSubCommands>(
        commandBasicInfo = commandBasicInfo,
        subCommand = subCommand,
        intents = intents,
    ) {
    override fun parseCommandType(): CommandType = CommandType.PIPELINE

    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.STANDUP_SETUP_SUBMIT

    override fun handleInteraction(interaction: InboundInteraction): CommandOutput {
        val s =
            interaction.submission as? InboundSubmission.StandupSetup
                ?: return successOutput()
        val creatorId = s.creatorId.ifBlank { interaction.actor.id }
        val commandChannel = s.commandChannel

        val name = s.name.trim()
        val questions =
            s.questionsRaw
                .split("\n")
                .map { it.trim() }
                .filter { it.isNotBlank() }
        val memberIds =
            s.membersRaw
                .split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
        val summaryChannel = s.summaryChannel.trim()
        val weekdays =
            s.weekdaysRaw
                .split(",")
                .mapNotNull { token -> runCatching { DayOfWeek.valueOf(token.trim()) }.getOrNull() }
                .toSet()
        val triggerLocalTime = runCatching { LocalTime.parse(s.timeRaw) }.getOrDefault(DEFAULT_TRIGGER_TIME)
        val cutoffMinutes = s.cutoffRaw.trim().toLongOrNull() ?: DEFAULT_CUTOFF_MINUTES
        val timezone = runCatching { ZoneId.of(s.timezoneRaw.trim()) }.getOrDefault(DEFAULT_TIMEZONE)

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
        return successOutput()
    }

    private fun successOutput() =
        CommandOutput.success(
            basicInfo = commandBasicInfo,
            commandType = commandType,
            commandDetailType = commandDetailType,
        )

    companion object {
        const val DEFAULT_CUTOFF_MINUTES: Long = 120L
        private val DEFAULT_TRIGGER_TIME: LocalTime = LocalTime.of(10, 0)
        private val DEFAULT_TIMEZONE: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
