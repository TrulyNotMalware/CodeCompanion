package dev.notypie.common

import jakarta.persistence.AttributeConverter
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.persistence.Converter

@Converter(autoApply = true)
class JPAJsonConverter: AttributeConverter<Map<String, Any>, String>{

    override fun convertToDatabaseColumn(attribute: Map<String, Any>?): String =
        objectMapper.writeValueAsString(attribute)

    override fun convertToEntityAttribute(content: String?): Map<String, Any> =
        objectMapper.readValue<Map<String, Any>>(content = content ?: "{}")

}