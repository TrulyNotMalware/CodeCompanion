package dev.notypie.domain.common

private const val MAX_ENTITY_LENGTH: Int = 5
private val LINE_BREAKS: Regex = Regex(pattern = "\\R")

fun String.escapeMarkup(): String =
    replace(oldValue = "&", newValue = "&amp;")
        .replace(oldValue = "<", newValue = "&lt;")
        .replace(oldValue = ">", newValue = "&gt;")

fun String.takeSurrogateSafe(maxLength: Int): String {
    val cut = take(n = maxLength)
    return if (cut.length < length && cut.isNotEmpty() && cut.last().isHighSurrogate()) cut.dropLast(n = 1) else cut
}

fun String.takeEntitySafe(maxLength: Int): String {
    if (length <= maxLength) return this
    val cut = takeSurrogateSafe(maxLength = maxLength)
    val ampersand = cut.lastIndexOf(char = '&')
    return if (
        ampersand >= 0 &&
        ampersand > cut.length - MAX_ENTITY_LENGTH &&
        cut.indexOf(char = ';', startIndex = ampersand) < 0
    ) {
        cut.substring(startIndex = 0, endIndex = ampersand)
    } else {
        cut
    }
}

fun String.splitLineBreaks(): List<String> = split(regex = LINE_BREAKS)
