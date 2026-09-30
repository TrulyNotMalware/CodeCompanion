package dev.notypie.templates

// Slack parses `<…>` in mrkdwn as control sequences even with `verbatim` set: `<!channel>` notifies the whole
// channel and `<https://evil|label>` renders a disguised link. Escape user- or externally-supplied text before
// interpolating it; the formatting a template adds itself (`*bold*`, `<@userId>`) stays outside the escape.
fun String.escapeMrkdwn(): String =
    replace(oldValue = "&", newValue = "&amp;")
        .replace(oldValue = "<", newValue = "&lt;")
        .replace(oldValue = ">", newValue = "&gt;")

// `<!…>` is Slack's special-mention syntax: `<!channel>`, `<!here>`, `<!everyone>`, the legacy `<!group>`, and
// `<!subteam^ID>` user groups, each optionally with a `|label`. Only `<!date^…>` among them is plain formatting.
private val SPECIAL_MENTION = Regex("<!(?!date\\^)([^<>]*)>")

// For AI output, which must keep the model's links, emphasis and `<@user>` mentions and so cannot be escaped
// wholesale: only special mentions are defused (their angle brackets escaped, so they show as literal text).
// Otherwise a model echoing a meeting title or a tool result could notify a whole channel under the bot's name.
fun String.neutralizeBroadcastMentions(): String =
    replace(regex = SPECIAL_MENTION) { match -> "&lt;!${match.groupValues[1]}&gt;" }
