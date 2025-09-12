package dev.notypie.docs

import org.springframework.restdocs.payload.FieldDescriptor
import org.springframework.restdocs.payload.JsonFieldType
import org.springframework.restdocs.payload.PayloadDocumentation
import kotlin.reflect.KClass

sealed class DocsFieldType(
    val type: JsonFieldType,
)

object ARRAY : DocsFieldType(type = JsonFieldType.ARRAY)

object BOOLEAN : DocsFieldType(type = JsonFieldType.BOOLEAN)

object NUMBER : DocsFieldType(type = JsonFieldType.NUMBER)

object STRING : DocsFieldType(type = JsonFieldType.STRING)

object OBJECT : DocsFieldType(type = JsonFieldType.OBJECT)

object NULL : DocsFieldType(type = JsonFieldType.NULL)

object ANY : DocsFieldType(type = JsonFieldType.VARIES)

object DATE : DocsFieldType(type = JsonFieldType.STRING)

object DATETIME : DocsFieldType(type = JsonFieldType.STRING)

data class ENUM<T : Enum<T>>(
    val enums: Collection<T>,
) : DocsFieldType(type = JsonFieldType.STRING) {
    constructor(clazz: KClass<T>) : this(clazz.java.enumConstants.asList())
}

infix fun String.type(docsFieldType: DocsFieldType): Field {
    val field = createField(value = this, type = docsFieldType.type)
    when (docsFieldType) {
        is DATE -> field formattedAs RestDocsUtils.DATE_FORMAT
        is DATETIME -> field formattedAs RestDocsUtils.DATETIME_FORMAT
        else -> {}
    }
    return field
}

infix fun <T : Enum<T>> String.type(enumFieldType: ENUM<T>): Field {
    val field = createField(value = this, type = JsonFieldType.STRING, optional = false)
    field.format = EnumFormattingUtils.enumFormat(enumFieldType.enums)
    return field
}

private fun createField(value: String, type: JsonFieldType, optional: Boolean = true): Field {
    val descriptor =
        PayloadDocumentation
            .fieldWithPath(value)
            .type(type)
            .attributes(RestDocsUtils.emptySample(), RestDocsUtils.emptyFormat(), RestDocsUtils.emptyDefaultValue())
            .description("")

    if (optional) descriptor.optional()

    return Field(descriptor)
}

open class Field(
    val descriptor: FieldDescriptor,
) {
    val isIgnored: Boolean = descriptor.isIgnored
    val isOptional: Boolean = descriptor.isOptional

    protected open var default: String
        get() = descriptor.attributes.getOrDefault(RestDocsAttributeKeys.KEY_DEFAULT_VALUE, "") as String
        set(value) {
            descriptor.attributes(RestDocsUtils.defaultValue(value))
        }

    open var format: String
        get() = descriptor.attributes.getOrDefault(RestDocsAttributeKeys.KEY_FORMAT, "") as String
        set(value) {
            descriptor.attributes(RestDocsUtils.customFormat(value))
        }

    protected open var sample: String
        get() = descriptor.attributes.getOrDefault(RestDocsAttributeKeys.KEY_SAMPLE, "") as String
        set(value) {
            descriptor.attributes(RestDocsUtils.customSample(value))
        }

    open infix fun means(value: String): Field = description(value)

    open infix fun attributes(block: Field.() -> Unit): Field {
        block()
        return this
    }

    open infix fun withDefaultValue(value: String): Field {
        default = value
        return this
    }

    open infix fun formattedAs(value: String): Field {
        format = value
        return this
    }

    open infix fun example(value: String): Field {
        sample = value
        return this
    }

    open infix fun isOptional(value: Boolean): Field {
        if (value) descriptor.optional()
        return this
    }

    open infix fun isIgnored(value: Boolean): Field {
        if (value) descriptor.ignored()
        return this
    }

    open infix fun description(value: String): Field {
        descriptor.description(value)
        return this
    }
}
