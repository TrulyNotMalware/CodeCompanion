package dev.notypie.exception.meeting

import dev.notypie.domain.common.error.CodeCompanionRuntimeException
import dev.notypie.domain.common.error.ErrorCode
import dev.notypie.domain.common.error.ExceptionArgument
import kotlin.reflect.KClass

class DatabaseException(
    val tableName: String,
    errorCode: ErrorCode,
    details: List<ExceptionArgument>,
) : CodeCompanionRuntimeException(
        errorCode = errorCode,
        details = details,
    )

enum class JpaErrorCode(
    override val statusCode: Int,
    override val message: String,
) : ErrorCode {
    TABLE_NOT_FOUND(statusCode = 404, message = "Table not found."),
}

class NotFoundExceptionBuilder {
    private var tableName: String = ""
    private var fieldName: String = ""
    private var fieldValue: String = ""
    private var errorReason: String = ""

    fun table(kClass: KClass<*>) {
        tableName = kClass.simpleName ?: "Unknown"
    }

    fun table(name: String) {
        tableName = name
    }

    fun field(name: String): FieldBuilder {
        fieldName = name
        return FieldBuilder(this)
    }

    fun reason(message: String) {
        errorReason = message
    }

    class FieldBuilder(
        private val parent: NotFoundExceptionBuilder,
    ) {
        infix fun withValue(value: String): NotFoundExceptionBuilder {
            parent.fieldValue = value
            return parent
        }
    }

    internal fun build(): DatabaseException =
        DatabaseException(
            tableName = tableName,
            errorCode = JpaErrorCode.TABLE_NOT_FOUND,
            details = listOf(ExceptionArgument(fieldName = fieldName, value = fieldValue, reason = errorReason)),
        )
}

fun schemaNotFound(init: NotFoundExceptionBuilder.() -> Unit): Nothing {
    val builder = NotFoundExceptionBuilder()
    builder.init()
    throw builder.build()
}

inline fun <reified T : Any> T?.throwIfSchemaNotFound(fieldName: String, fieldValue: Any, reason: String? = null): T =
    this ?: throw DatabaseException(
        tableName = T::class.simpleName ?: "Unknown",
        errorCode = JpaErrorCode.TABLE_NOT_FOUND,
        details =
            listOf(
                ExceptionArgument(
                    fieldName = fieldName,
                    value = fieldValue.toString(),
                    reason = reason ?: "${T::class.simpleName} with $fieldName=$fieldValue not found.",
                ),
            ),
    )
