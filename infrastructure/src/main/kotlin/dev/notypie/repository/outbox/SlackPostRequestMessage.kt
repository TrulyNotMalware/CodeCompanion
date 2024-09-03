package dev.notypie.repository.outbox

data class SlackPostRequestMessage(
    val type: SlackRequestType,
    val payload: Map<String, Any> = emptyMap(),
    val metadata: Map<String, Any> = emptyMap()
)


enum class SlackRequestType ( description: String ){
    APPROVAL_REQUESTS("Request approval form")
}