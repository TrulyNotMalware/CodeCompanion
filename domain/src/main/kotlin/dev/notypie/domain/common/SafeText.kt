package dev.notypie.domain.common

fun String.escapeMarkup(): String =
    replace(oldValue = "&", newValue = "&amp;")
        .replace(oldValue = "<", newValue = "&lt;")
        .replace(oldValue = ">", newValue = "&gt;")

fun String.takeSurrogateSafe(maxLength: Int): String {
    val cut = take(n = maxLength)
    return if (cut.length < length && cut.isNotEmpty() && cut.last().isHighSurrogate()) cut.dropLast(n = 1) else cut
}
