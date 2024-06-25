package dev.notypie.application.service.mention

import com.fasterxml.jackson.databind.ObjectMapper
import dev.notypie.domain.command.SlackCommandType
import dev.notypie.domain.command.dto.SlackCommandData
import org.springframework.stereotype.Service
import org.springframework.util.MultiValueMap

@Service
class SlackMentionEventHandlerImpl(
    val objectMapper: ObjectMapper
): AppMentionEventHandler {
    companion object {
        const val SLACK_APPID_KEY_NAME = "api_app_id"
    }

    override fun parseAppMentionEvent(
        headers: MultiValueMap<String, String>, payload: Map<String, Any>
    ): SlackCommandData{
        val appId = this.resolveAppId(payload = payload)
        val body = this.convertBodyData(payload = payload)
        return SlackCommandData(
            appId = appId,
        )
    }

    private fun resolveAppId(payload: Map<String, Any>): String{
        if(payload[SLACK_APPID_KEY_NAME] != null) return payload[SLACK_APPID_KEY_NAME].toString()
        else throw RuntimeException("COMMAND_TYPE_NOT_DETECTED")
    }

    private fun convertBodyData( payload: Map<String, Any> ) : Any{
        if(payload["event"] != null && payload["event"] is Map<*, *>){
            val eventBody:Map<String, Any> =payload["event"] as Map<String, Any>
            val target = eventBody["type"] as String
            val slackCommandType = SlackCommandType.valueOf(target.uppercase())
            return this.objectMapper.convertValue(eventBody, slackCommandType.convertType.java)
        } else {
            throw RuntimeException("NOT VALID REQUEST") //TODO fix this exception later.
        }
    }


}