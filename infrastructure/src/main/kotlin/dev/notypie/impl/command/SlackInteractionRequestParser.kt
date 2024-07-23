package dev.notypie.impl.command

import com.slack.api.app_backend.interactive_components.payload.BlockActionPayload
import com.slack.api.app_backend.interactive_components.payload.BlockActionPayload.Action
import com.slack.api.model.view.ViewState
import com.slack.api.util.json.GsonFactory
import dev.notypie.domain.command.dto.interactions.*
import java.time.Instant

class SlackInteractionRequestParser
: InteractionPayloadParser {
    
    override fun parseStringContents(payload: String): InteractionPayloads {
        val blockActionPayload = GsonFactory.createSnakeCase().fromJson(payload, BlockActionPayload::class.java)
        return this.toInteractionPayloads(blockActionPayload = blockActionPayload)
    }

    private fun toInteractionPayloads(blockActionPayload: BlockActionPayload): InteractionPayloads{
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
        return InteractionPayloads(type = blockActionPayload.type, apiAppId = blockActionPayload.apiAppId, channel = channel,
            container = container, responseUrl = blockActionPayload.responseUrl, token = blockActionPayload.token, triggerId = blockActionPayload.triggerId,
            isEnterprise = blockActionPayload.isEnterpriseInstall, team = team, user = user,
            states = this.parseStates(blockActionPayload.state),
            currentAction = this.parseCurrentAction(blockActionPayload.actions))
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

                else -> null
            }
        } ?: States(type = ActionElementTypes.UNKNOWN)
    }
}