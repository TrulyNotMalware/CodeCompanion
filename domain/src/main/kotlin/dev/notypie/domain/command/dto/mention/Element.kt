package dev.notypie.domain.command.dto.mention

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.annotation.JsonDeserialize

//FIXME Element to sealed class, require subType
data class Element(
    val type: String,
    val text: TextElement? = null,

    @field:JsonProperty("user_id")
    val userId: String?,

    @field:JsonProperty("image_url")
    val imageUrl: String? = null,

    @field:JsonProperty("alt_text")
    val altText: String? = null,

    @field:JsonProperty("verbatim")
    val verbatim: Boolean = true,

    @field:JsonProperty("action_id")
    val actionId: String? = null,
    @field:JsonProperty("value")
    val value: String? = null,
    @field:JsonProperty("style")
    val style: String? = null,

    val elements: List<Element> = listOf()
)

@JsonDeserialize(using = TextValueDeserializer::class)
sealed class TextElement

data class TextObject(
    val type: String,
    val text: String,
    @field:JsonProperty("verbatim")
    val verbatim: Boolean = true,

    @field:JsonProperty("emoji")
    val emoji: Boolean = true
): TextElement()


data class PlainText(
    val value: String
): TextElement()

class TextValueDeserializer: JsonDeserializer<TextElement>(){
    override fun deserialize(parser: JsonParser, ctx: DeserializationContext): TextElement =
        when(parser.currentToken()){
            JsonToken.VALUE_STRING -> PlainText(parser.text)
            JsonToken.START_OBJECT -> {
                val node: JsonNode = parser.codec.readTree(parser)
                val type = node["type"].asText()
                val text = node["text"].asText()
                val emoji = node["emoji"]?.asBoolean() ?: true
                val verbatim = node["verbatim"]?.asBoolean() ?: true
                TextObject(type = type, text = text, verbatim = verbatim, emoji = emoji)
            }
            else -> throw IllegalArgumentException("Unexpected token for TextValue: ${parser.currentToken}")
        }
}