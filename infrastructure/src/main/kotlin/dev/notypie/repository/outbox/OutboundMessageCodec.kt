package dev.notypie.repository.outbox

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import dev.notypie.domain.command.dto.modals.TimeScheduleInfo
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import tools.jackson.core.JacksonException
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.cfg.DateTimeFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinFeature
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.readValue

// The domain stays annotation-free; all polymorphism and the non-serializable DateTimeFormatter
// are handled here through Jackson mix-ins registered on the codec's own mapper.

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
@JsonSubTypes(
    JsonSubTypes.Type(value = OutboundMessage.ChannelMessage::class, name = "ChannelMessage"),
    JsonSubTypes.Type(value = OutboundMessage.Ephemeral::class, name = "Ephemeral"),
    JsonSubTypes.Type(value = OutboundMessage.Approval::class, name = "Approval"),
    JsonSubTypes.Type(value = OutboundMessage.Notice::class, name = "Notice"),
    JsonSubTypes.Type(value = OutboundMessage.UpdateMessage::class, name = "UpdateMessage"),
    JsonSubTypes.Type(value = OutboundMessage.ReplaceMessage::class, name = "ReplaceMessage"),
)
// OpenModal and DirectMessage are intentionally unregistered: neither is outbox-bound, so encoding
// or decoding one fails fast with an unresolved-type-id error rather than silently round-tripping.
private interface OutboundMessageMixin

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
@JsonSubTypes(
    JsonSubTypes.Type(value = MessageContent.Text::class, name = "Text"),
    JsonSubTypes.Type(value = MessageContent.ErrorNotice::class, name = "ErrorNotice"),
    JsonSubTypes.Type(value = MessageContent.Schedule::class, name = "Schedule"),
    JsonSubTypes.Type(value = MessageContent.Form::class, name = "Form"),
    JsonSubTypes.Type(value = MessageContent.MeetingRequest::class, name = "MeetingRequest"),
    JsonSubTypes.Type(value = MessageContent.MeetingList::class, name = "MeetingList"),
    JsonSubTypes.Type(value = MessageContent.StandupSummary::class, name = "StandupSummary"),
)
private interface MessageContentMixin

// DateTimeFormatter has no stable JSON form and no equals(); excluded so the Kotlin default
// reconstructs it on decode.
@JsonIgnoreProperties("timeFormatter")
private interface TimeScheduleInfoMixin

class OutboundMessageCodecException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Round-trips an [OutboundEnvelope] to and from the single JSON string stored in the outbox.
 * Decoding fails fast with [OutboundMessageCodecException] on malformed JSON or an unregistered
 * message/content subtype.
 */
object OutboundMessageCodec {
    private val mapper: JsonMapper =
        JsonMapper
            .builder()
            .findAndAddModules()
            .addModule(
                KotlinModule
                    .Builder()
                    .enable(KotlinFeature.UseJavaDurationConversion)
                    .enable(KotlinFeature.KotlinPropertyNameAsImplicitName)
                    .build(),
            ).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .addMixIn(OutboundMessage::class.java, OutboundMessageMixin::class.java)
            .addMixIn(MessageContent::class.java, MessageContentMixin::class.java)
            .addMixIn(TimeScheduleInfo::class.java, TimeScheduleInfoMixin::class.java)
            .build()

    fun encode(envelope: OutboundEnvelope): String =
        try {
            mapper.writeValueAsString(envelope)
        } catch (ex: JacksonException) {
            throw OutboundMessageCodecException(
                message = "Failed to encode outbound envelope: ${ex.message}",
                cause = ex,
            )
        }

    fun decode(json: String): OutboundEnvelope =
        try {
            mapper.readValue(json)
        } catch (ex: JacksonException) {
            throw OutboundMessageCodecException(
                message = "Failed to decode outbound envelope: ${ex.message}",
                cause = ex,
            )
        }
}
