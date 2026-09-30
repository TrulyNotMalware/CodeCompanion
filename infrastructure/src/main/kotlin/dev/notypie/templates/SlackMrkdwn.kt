package dev.notypie.templates

// Slack parses `<…>` in mrkdwn as control sequences even with `verbatim` set: `<!channel>` notifies the whole
// channel and `<https://evil|label>` renders a disguised link. Escape user- or externally-supplied text before
// interpolating it; the formatting a template adds itself (`*bold*`, `<@userId>`) stays outside the escape.
fun String.escapeMrkdwn(): String =
    replace(oldValue = "&", newValue = "&amp;")
        .replace(oldValue = "<", newValue = "&lt;")
        .replace(oldValue = ">", newValue = "&gt;")
