package dev.notypie.domain.common

// The outbound markdown dialect reads `<…>` as control sequences: a broadcast mention, a disguised link, a user
// mention. Text a user or an upstream feed supplied is escaped before it is interpolated into markdown, so it can
// only ever show as text; the formatting the caller adds itself (`*bold*`, `<@userId>`) stays outside the escape.
// This is the canonical implementation: infrastructure's `templates/escapeMrkdwn()` delegates here, so the domain
// (which may not import infrastructure) and the templates escape identically and never twice.
fun String.escapeMarkup(): String =
    replace(oldValue = "&", newValue = "&amp;")
        .replace(oldValue = "<", newValue = "&lt;")
        .replace(oldValue = ">", newValue = "&gt;")
