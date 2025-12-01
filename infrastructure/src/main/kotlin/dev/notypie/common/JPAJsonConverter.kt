package dev.notypie.common

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import tools.jackson.module.kotlin.readValue

@Converter(autoApply = true)
class JPAJsonConverter : AttributeConverter<Map<String, Any>, String> {
    override fun convertToDatabaseColumn(attribute: Map<String, Any>?): String =
        jsonMapper.writeValueAsString(attribute)

    override fun convertToEntityAttribute(content: String?): Map<String, Any> =
        jsonMapper.readValue<Map<String, Any>>(content = content ?: "{}")
}
