package dev.notypie.domain.command.outbound

@JvmInline
value class ModalOpenHandle(
    val raw: String,
)

@JvmInline
value class ResponseReplaceHandle(
    val raw: String,
)

fun replaceHandleOrNull(raw: String): ResponseReplaceHandle? =
    raw.takeIf { it.isNotBlank() }?.let { ResponseReplaceHandle(raw = it) }
