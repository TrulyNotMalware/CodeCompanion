package dev.notypie.impl.command

import com.slack.api.app_backend.interactive_components.payload.BlockActionPayload
import com.slack.api.app_backend.interactive_components.payload.BlockActionPayload.Action
import com.slack.api.model.view.ViewState
import com.slack.api.util.json.GsonFactory
import dev.notypie.domain.command.dto.interactions.*
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.templates.ButtonType
import org.apache.commons.text.StringTokenizer
import java.time.Instant

class SlackInteractionRequestParser
: InteractionPayloadParser {
    
    override fun parseStringPayload(payload: String): InteractionPayload {
        val blockActionPayload = GsonFactory.createSnakeCase().fromJson(payload, BlockActionPayload::class.java)
        return this.toInteractionPayloads(blockActionPayload = blockActionPayload)
    }

    private fun toInteractionPayloads(blockActionPayload: BlockActionPayload): InteractionPayload{
        val channel = Channel(id = blockActionPayload.channel.id, name = blockActionPayload.channel.name)
        val unixTimeStamp = blockActionPayload.container.messageTs
        val timestampDouble = unixTimeStamp.toDouble()
        val team = Team(domain = blockActionPayload.team.domain, id = blockActionPayload.team.id)
        val user = User(id = blockActionPayload.user.id, name = blockActionPayload.user.name,
            teamId = blockActionPayload.user.teamId, username = blockActionPayload.user.username)

        val seconds = timestampDouble.toLong()
        val nanos = ((timestampDouble - seconds) * 1_000_000_000).toInt()
        val messageTime: Instant = Instant.ofEpochSecond(seconds, nanos.toLong())
        val container = Container(isEphemeral = blockActionPayload.container.isEphemeral, messageTime = messageTime, type = blockActionPayload.container.type)

        //Ephemeral contents does not include message sections.
        val currentAction = this.parseCurrentAction(blockActionPayload.actions)
        val botId = if(container.isEphemeral) blockActionPayload.apiAppId else blockActionPayload.message.botId
        val messageTokenizer =
            if (container.isEphemeral){
                if(currentAction.type.isPrimary) StringTokenizer(currentAction.selectedValue, ",")
                else StringTokenizer("${CommandDetailType.NOTHING},${CommandDetailType.NOTHING}",",")
            }else StringTokenizer(blockActionPayload.message.text, ",")
        val idempotencyKey = messageTokenizer.nextToken()
        val type = messageTokenizer.nextToken().replace("\\s".toRegex(), "")
        return InteractionPayload(type = CommandDetailType.valueOf(type), apiAppId = blockActionPayload.apiAppId, channel = channel,
            container = container, responseUrl = blockActionPayload.responseUrl, token = blockActionPayload.token, triggerId = blockActionPayload.triggerId,
            isEnterprise = blockActionPayload.isEnterpriseInstall, team = team, user = user,
            states = this.parseStates(blockActionPayload.state),
            currentAction = this.parseCurrentAction(blockActionPayload.actions),
            botId = botId,
            idempotencyKey = idempotencyKey)
    }

    private fun parseStates(viewState: ViewState): List<States>{
        return viewState.values.values.flatMap { innerMap ->
            innerMap.values.mapNotNull { value ->
                when(value.type){
                    ActionElementTypes.MULTI_STATIC_SELECT.elementName -> {
                        if(value.selectedOptions.isEmpty()) States(type = ActionElementTypes.MULTI_STATIC_SELECT)
                        else{
                            val selectedValue = value.selectedOptions.joinToString { it.value }
                            States(type = ActionElementTypes.MULTI_STATIC_SELECT, isSelected = true, selectedValue = selectedValue)
                        }
                    }
                    ActionElementTypes.MULTI_USERS_SELECT.elementName -> {
                        if(value.selectedUsers.isEmpty()) States(type = ActionElementTypes.MULTI_USERS_SELECT)
                        else States(type = ActionElementTypes.MULTI_USERS_SELECT, isSelected = true, selectedValue = value.selectedUsers.joinToString(", "))
                    }
                    ActionElementTypes.DATE_PICKER.elementName ->
                        States(type = ActionElementTypes.DATE_PICKER, isSelected = true, selectedValue = value.selectedDate)
                    ActionElementTypes.TIME_PICKER.elementName ->
                        States(type = ActionElementTypes.TIME_PICKER, isSelected = true, selectedValue = value.selectedTime)

                    else -> States(type = ActionElementTypes.UNKNOWN)
                }
            }
        }
    }

    private fun parseCurrentAction(actions: List<Action>): States {
        return actions.firstNotNullOfOrNull { action ->
            when (action.type) {
                ActionElementTypes.MULTI_STATIC_SELECT.elementName ->
                    States(
                        type = ActionElementTypes.MULTI_STATIC_SELECT,
                        isSelected = true,
                        selectedValue = action.selectedOptions.joinToString { it.value })

                ActionElementTypes.MULTI_USERS_SELECT.elementName ->
                    States(
                        type = ActionElementTypes.MULTI_USERS_SELECT,
                        isSelected = true,
                        selectedValue = action.selectedUsers.joinToString(", ")
                    )

                ActionElementTypes.BUTTON.elementName -> this.buttonParser(action = action)
                else -> null
            }
        } ?: States(type = ActionElementTypes.UNKNOWN)
    }

    private fun buttonParser(action: Action): States {
        if(action.type != ActionElementTypes.BUTTON.elementName) throw IllegalArgumentException("Action type is not button.")
        return when (action.style) {
            ButtonType.PRIMARY.name.lowercase() ->
                States(
                    type = ActionElementTypes.APPLY_BUTTON,
                    isSelected = true,
                    selectedValue = action.value
                )

            ButtonType.DANGER.name.lowercase() ->
                States(
                    type = ActionElementTypes.REJECT_BUTTON,
                    isSelected = true,
                    selectedValue = action.value
                )

            else -> States(
                type = ActionElementTypes.BUTTON,
                isSelected = true,
                selectedValue = action.value
            )
        }
    }
}