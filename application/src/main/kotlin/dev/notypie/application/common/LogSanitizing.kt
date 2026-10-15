package dev.notypie.application.common

private val LOG_UNSAFE_CHARACTERS = Regex("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]")

internal fun String.neutralizeControlCharacters(): String = replace(regex = LOG_UNSAFE_CHARACTERS, replacement = "?")
