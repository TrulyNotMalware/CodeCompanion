package dev.notypie.templates

import dev.notypie.domain.common.escapeMarkup

// Slack parses `<…>` in mrkdwn as control sequences even with `verbatim` set: `<!channel>` notifies the whole
// channel and `<https://evil|label>` renders a disguised link. Escape user- or externally-supplied text before
// interpolating it; the formatting a template adds itself (`*bold*`, `<@userId>`) stays outside the escape.
// The implementation lives in the domain (`domain/common/escapeMarkup`) so domain-built markdown, such as the
// decline notice summary, escapes the same three characters; this name stays for the Slack-side callers.
fun String.escapeMrkdwn(): String = escapeMarkup()

// `<!…>` is Slack's special-mention syntax: `<!channel>`, `<!here>`, `<!everyone>`, the legacy `<!group>`, and
// `<!subteam^ID>` user groups, each optionally with a `|label`. Only `<!date^…>` among them is plain formatting.
private val SPECIAL_MENTION = Regex("<!(?!date\\^)([^<>]*)>")

// For AI output, which must keep the model's links, emphasis and `<@user>` mentions and so cannot be escaped
// wholesale: only special mentions are defused (their angle brackets escaped, so they show as literal text).
// Otherwise a model echoing a meeting title or a tool result could notify a whole channel under the bot's name.
fun String.neutralizeBroadcastMentions(): String =
    replace(regex = SPECIAL_MENTION) { match -> "&lt;!${match.groupValues[1]}&gt;" }
