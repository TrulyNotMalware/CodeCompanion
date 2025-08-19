package dev.notypie.docs

import org.springframework.restdocs.snippet.Attributes

object RestDocsUtils {
    const val DATE_FORMAT = "yyyy-MM-dd"
    const val DATETIME_FORMAT = "yyyy-MM-dd'T'HH:mm:ss"

    fun emptySample(): Attributes.Attribute = Attributes.key("sample").value("")
    fun emptyFormat(): Attributes.Attribute = Attributes.key("format").value("")
    fun emptyDefaultValue(): Attributes.Attribute = Attributes.key("default").value("")

    fun customSample(value: String): Attributes.Attribute = Attributes.key("sample").value(value)
    fun customFormat(value: String): Attributes.Attribute = Attributes.key("format").value(value)
    fun defaultValue(value: String): Attributes.Attribute = Attributes.key("default").value(value)
}

object RestDocsAttributeKeys {
    const val KEY_SAMPLE = "sample"
    const val KEY_FORMAT = "format"
    const val KEY_DEFAULT_VALUE = "default"
}

object EnumFormattingUtils {
    fun <T : Enum<T>> enumFormat(enums: Collection<T>): String {
        return enums.joinToString(" | ") { it.name }
    }
}