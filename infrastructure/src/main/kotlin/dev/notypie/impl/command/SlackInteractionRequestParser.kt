package dev.notypie.impl.command

import com.google.gson.JsonParser
import com.slack.api.app_backend.interactive_components.payload.BlockActionPayload
import com.slack.api.app_backend.interactive_components.payload.BlockActionPayload.Action
import com.slack.api.app_backend.views.payload.ViewSubmissionPayload
import com.slack.api.model.view.ViewState
import com.slack.api.util.json.GsonFactory
import dev.notypie.domain.command.dto.interactions.*
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.templates.ButtonType
import dev.notypie.templates.DeclineReasonModalIds
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant

class SlackInteractionRequestParser : InteractionPayloadParser {
    companion object {
        private const val VIEW_SUBMISSION_TYPE: String = "view_submission"
    }

    override fun parseStringPayload(payload: String): InteractionPayload {
        val rootType = peekPayloadType(payload = payload)
        return if (rootType == VIEW_SUBMISSION_TYPE) {
            val viewSubmission =
                GsonFactory.createSnakeCase().fromJson(payload, ViewSubmissionPayload::class.java)
            toInteractionPayloads(viewSubmission = viewSubmission)
        } else {
            val blockActionPayload =
                GsonFactory.createSnakeCase().fromJson(payload, BlockActionPayload::class.java)
            toInteractionPayloads(blockActionPayload = blockActionPayload)
        }
    }

    /**
     * Peeks only the top-level `type` field — the full payload shape differs for block_actions
     * vs view_submission, so deserializing optimistically would crash on the wrong branch.
     */
    private fun peekPayloadType(payload: String): String? =
        runCatching {
            JsonParser
                .parseString(payload)
                .asJsonObject
                ?.get("type")
                ?.asString
        }.getOrNull()

    /**
     * Maps a Slack `view_submission` payload (fired when a user clicks Submit on a modal) into
     * the common [InteractionPayload] shape. `currentAction` is synthesized as an
     * [ActionElementTypes.APPLY_BUTTON] so the interaction handler treats the submission as a
     * primary completion and routes it through the normal context pipeline.
     *
     * Routing tokens come from the modal's `private_metadata` (same comma-tokenized format
     * as the embedded-text routing used for block_actions), not from a message body.
     */
    private fun toInteractionPayloads(viewSubmission: ViewSubmissionPayload): InteractionPayload {
        val team = Team(domain = viewSubmission.team?.domain.orEmpty(), id = viewSubmission.team?.id.orEmpty())
        val user =
            User(
                id = viewSubmission.user?.id.orEmpty(),
                name = viewSubmission.user?.name.orEmpty(),
                teamId = viewSubmission.user?.teamId.orEmpty(),
                userName = viewSubmission.user?.username.orEmpty(),
            )
        val privateMetadata = viewSubmission.view?.privateMetadata.orEmpty()
        val tokens = privateMetadata.split(",").map { it.trim() }
        val idempotencyKey = tokens.getOrNull(0)?.takeIf { it.isNotBlank() } ?: ""
        val type =
            tokens
                .getOrNull(1)
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { CommandDetailType.valueOf(it) }.getOrDefault(CommandDetailType.NOTHING) }
                ?: CommandDetailType.NOTHING
        val routingExtras =
            if (tokens.size > 2) tokens.subList(2, tokens.size).map(::decodeRoutingExtra) else emptyList()

        // The decline-reason modal now uses a static_select dropdown instead of radio_buttons;
        // both expose the selection via `selectedOption.value` on the SDK's ViewState.Value.
        val selectedReasonValue =
            viewSubmission.view
                ?.state
                ?.values
                ?.get(DeclineReasonModalIds.BLOCK_ID)
                ?.get(DeclineReasonModalIds.ACTION_ID)
                ?.selectedOption
                ?.value
                .orEmpty()
        val selectionState =
            States(
                type = ActionElementTypes.STATIC_SELECT,
                isSelected = selectedReasonValue.isNotBlank(),
                selectedValue = selectedReasonValue,
            )
        // Synthetic primary action — view_submission has no real "current action" but
        // carries selection via view.state; downstream routing gates on isPrimary().
        val currentAction =
            States(
                type = ActionElementTypes.APPLY_BUTTON,
                isSelected = true,
                selectedValue = selectedReasonValue,
            )
        val container =
            Container(
                type = VIEW_SUBMISSION_TYPE,
                messageTime = Instant.now(),
                isEphemeral = false,
                viewId = viewSubmission.view?.id,
            )
        return InteractionPayload(
            type = type,
            apiAppId = viewSubmission.apiAppId.orEmpty(),
            channel = Channel(id = "", name = ""),
            container = container,
            responseUrl = "",
            token = viewSubmission.token.orEmpty(),
            triggerId = viewSubmission.triggerId.orEmpty(),
            isEnterprise = viewSubmission.isEnterpriseInstall,
            team = team,
            user = user,
            states = listOf(selectionState),
            currentAction = currentAction,
            botId = "",
            idempotencyKey = idempotencyKey,
            routingExtras = routingExtras,
            privateMetadata = privateMetadata,
        )
    }

    private fun toInteractionPayloads(blockActionPayload: BlockActionPayload): InteractionPayload {
        val channel = Channel(id = blockActionPayload.channel.id, name = blockActionPayload.channel.name)
        val unixTimeStamp = blockActionPayload.container.messageTs
        val timestampDouble = unixTimeStamp.toDouble()
        val team = Team(domain = blockActionPayload.team.domain, id = blockActionPayload.team.id)
        val user =
            User(
                id = blockActionPayload.user.id,
                name = blockActionPayload.user.name,
                teamId = blockActionPayload.user.teamId,
                userName = blockActionPayload.user.username,
            )

        val seconds = timestampDouble.toLong()
        val nanos = ((timestampDouble - seconds) * 1_000_000_000).toInt()
        val messageTime: Instant = Instant.ofEpochSecond(seconds, nanos.toLong())
        val container =
            Container(
                isEphemeral = blockActionPayload.container.isEphemeral,
                messageTime = messageTime,
                type = blockActionPayload.container.type,
                messageTs = unixTimeStamp,
            )

        // Ephemeral contents does not include message sections.
        val currentAction = parseCurrentAction(blockActionPayload.actions)
        val botId = if (container.isEphemeral) blockActionPayload.apiAppId else blockActionPayload.message.botId
        val rawEmbeddedText: String =
            when {
                container.isEphemeral && currentAction.type.isPrimary -> currentAction.selectedValue
                container.isEphemeral -> ""
                else -> blockActionPayload.message.text
            }
        val tokens = rawEmbeddedText.split(",").map { it.trim() }
        val idempotencyKey = tokens.getOrNull(0)?.takeIf { it.isNotBlank() } ?: ""
        val type =
            tokens
                .getOrNull(1)
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { CommandDetailType.valueOf(it) }.getOrDefault(CommandDetailType.NOTHING) }
                ?: CommandDetailType.NOTHING
        val routingExtras =
            if (tokens.size > 2) tokens.subList(2, tokens.size).map(::decodeRoutingExtra) else emptyList()
        return InteractionPayload(
            type = type,
            apiAppId = blockActionPayload.apiAppId,
            channel = channel,
            container = container,
            responseUrl = blockActionPayload.responseUrl,
            token = blockActionPayload.token,
            triggerId = blockActionPayload.triggerId,
            isEnterprise = blockActionPayload.isEnterpriseInstall,
            team = team,
            user = user,
            states = parseStates(blockActionPayload.state),
            currentAction = currentAction,
            botId = botId,
            idempotencyKey = idempotencyKey,
            routingExtras = routingExtras,
        )
    }

    private fun parseStates(viewState: ViewState): List<States> =
        viewState.values.values.flatMap { innerMap ->
            innerMap.values.mapNotNull { value ->
                when (value.type) {
                    ActionElementTypes.MULTI_STATIC_SELECT.elementName -> {
                        if (value.selectedOptions.isEmpty()) {
                            States(type = ActionElementTypes.MULTI_STATIC_SELECT)
                        } else {
                            val selectedValue = value.selectedOptions.joinToString { it.value }
                            States(
                                type = ActionElementTypes.MULTI_STATIC_SELECT,
                                isSelected = true,
                                selectedValue = selectedValue,
                            )
                        }
                    }
                    ActionElementTypes.PLAIN_TEXT_INPUT.elementName -> {
                        States(
                            type = ActionElementTypes.PLAIN_TEXT_INPUT,
                            isSelected = true,
                            selectedValue =
                                value.value ?: "",
                        )
                    }
                    ActionElementTypes.MULTI_USERS_SELECT.elementName -> {
                        if (value.selectedUsers.isEmpty()) {
                            States(type = ActionElementTypes.MULTI_USERS_SELECT)
                        } else {
                            States(
                                type = ActionElementTypes.MULTI_USERS_SELECT,
                                isSelected = true,
                                selectedValue = value.selectedUsers.joinToString(","),
                            )
                        }
                    }
                    ActionElementTypes.DATE_PICKER.elementName ->
                        States(
                            type = ActionElementTypes.DATE_PICKER,
                            isSelected = true,
                            selectedValue = value.selectedDate,
                        )
                    ActionElementTypes.TIME_PICKER.elementName ->
                        States(
                            type = ActionElementTypes.TIME_PICKER,
                            isSelected = value.selectedTime != null,
                            selectedValue = value.selectedTime ?: "",
                        )
                    ActionElementTypes.CHECKBOX.elementName ->
                        if (value.selectedOptions.isEmpty()) {
                            States(type = ActionElementTypes.CHECKBOX)
                        } else {
                            States(
                                type = ActionElementTypes.CHECKBOX,
                                isSelected = value.selectedOptions.isNotEmpty(),
                                selectedValue = value.selectedOptions.joinToString { it.text.text },
                            )
                        }
                    else -> States(type = ActionElementTypes.UNKNOWN)
                }
            }
        }

    private fun parseCurrentAction(actions: List<Action>): States =
        actions.firstNotNullOfOrNull { action ->
            when (action.type) {
                ActionElementTypes.MULTI_STATIC_SELECT.elementName ->
                    States(
                        type = ActionElementTypes.MULTI_STATIC_SELECT,
                        isSelected = true,
                        selectedValue = action.selectedOptions.joinToString { it.value },
                    )

                ActionElementTypes.MULTI_USERS_SELECT.elementName ->
                    States(
                        type = ActionElementTypes.MULTI_USERS_SELECT,
                        isSelected = true,
                        selectedValue = action.selectedUsers.joinToString(", "),
                    )

                ActionElementTypes.BUTTON.elementName -> buttonParser(action = action)
                else -> null
            }
        } ?: States(type = ActionElementTypes.UNKNOWN)

    /**
     * URL-decodes an individual routing extra. The writer side (SlackApiEventConstructor)
     * URL-encodes each extra before joining with `,` so arbitrary strings can ride along
     * without colliding with the delimiter. Malformed input falls through to the raw token
     * — safer than throwing because the field is at worst displayed to the user.
     */
    private fun decodeRoutingExtra(raw: String): String =
        runCatching { URLDecoder.decode(raw, StandardCharsets.UTF_8) }.getOrDefault(raw)

    private fun buttonParser(action: Action): States =
        if (action.type !=
            ActionElementTypes.BUTTON.elementName
        ) {
            throw IllegalArgumentException("Action type is not button.")
        } else {
            when (action.style) {
                ButtonType.PRIMARY.name.lowercase() ->
                    States(
                        type = ActionElementTypes.APPLY_BUTTON,
                        isSelected = true,
                        selectedValue = action.value,
                    )

                ButtonType.DANGER.name.lowercase() ->
                    States(
                        type = ActionElementTypes.REJECT_BUTTON,
                        isSelected = true,
                        selectedValue = action.value,
                    )

                else ->
                    States(
                        type = ActionElementTypes.BUTTON,
                        isSelected = true,
                        selectedValue = action.value,
                    )
            }
        }
}
