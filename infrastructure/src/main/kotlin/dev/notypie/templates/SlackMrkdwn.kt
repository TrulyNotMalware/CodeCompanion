package dev.notypie.templates

import dev.notypie.domain.common.escapeMarkup

fun String.escapeMrkdwn(): String = escapeMarkup()

private val SPECIAL_MENTION = Regex("<!(?!date\\^)([^<>]*)>")

fun String.neutralizeBroadcastMentions(): String =
    replace(regex = SPECIAL_MENTION) { match -> "&lt;!${match.groupValues[1]}&gt;" }
