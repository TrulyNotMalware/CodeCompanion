package dev.notypie.domain.command.dto.mention

import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.annotation.JsonDeserialize

// FIXME Element to sealed class, require subType
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
    val elements: List<Element> = listOf(),
)

internal fun Element.extractText(): String? =
    when (val textVal = text) {
        is PlainText -> textVal.value
        is TextObject -> textVal.text
        else -> null
    }

@JsonDeserialize(using = TextValueDeserializer::class)
sealed class TextElement

internal data class TextObject(
    val type: String,
    val text: String,
    @field:JsonProperty("verbatim")
    val verbatim: Boolean = true,
    @field:JsonProperty("emoji")
    val emoji: Boolean = true,
) : TextElement()

internal data class PlainText(
    val value: String,
) : TextElement()

internal class TextValueDeserializer : ValueDeserializer<TextElement>() {
    override fun deserialize(parser: JsonParser, ctx: DeserializationContext): TextElement =
        when (parser.currentToken()) {
            JsonToken.VALUE_STRING -> {
                PlainText(value = parser.string)
            }

            JsonToken.START_OBJECT -> {
                val node: JsonNode = parser.readValueAsTree()
                TextObject(
                    type = node["type"].asString(),
                    text = node["text"].asString(),
                    verbatim = node["verbatim"]?.booleanValue() ?: true,
                    emoji = node["emoji"]?.booleanValue() ?: true,
                )
            }

            else -> {
                throw IllegalArgumentException("Unexpected token for TextValue: ${parser.currentToken()}")
            }
        }
}
