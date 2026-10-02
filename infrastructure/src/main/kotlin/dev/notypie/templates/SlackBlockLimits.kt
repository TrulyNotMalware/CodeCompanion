package dev.notypie.templates

// Block Kit limits Slack enforces per payload. Crossing any of them rejects the whole message or view
// (`invalid_blocks` / `invalid_arguments`), which the relay classifies as permanent — the reply is lost, not retried.
object SlackBlockLimits {
    const val SECTION_TEXT_MAX_LENGTH: Int = 3_000

    // Working budget below the hard cap, the same margin the CVE digest uses: re-opened code fences and the
    // truncation marker are added on top of a packed chunk and must never push it past the cap.
    const val SECTION_TEXT_BUDGET: Int = 2_900
    const val MESSAGE_MAX_BLOCKS: Int = 50
    const val HEADER_TEXT_MAX_LENGTH: Int = 150
    const val OPTION_TEXT_MAX_LENGTH: Int = 75
    const val MAX_OPTIONS: Int = 100
    const val PLAIN_TEXT_INPUT_MAX_LENGTH: Int = 3_000
    const val TRUNCATION_MARKER: String = "…(truncated)"
}

private const val CODE_FENCE = "```"
private const val FENCE_REOPEN = "$CODE_FENCE\n"
private const val FENCE_CLOSE = "\n$CODE_FENCE"
private const val TRUNCATION_SUFFIX = "\n${SlackBlockLimits.TRUNCATION_MARKER}"

// Longest HTML entity escapeMrkdwn emits (`&amp;`); a cut inside one would leave a stray `&am` behind.
private const val MAX_ENTITY_LENGTH = 5

/**
 * Splits [text] into section-sized chunks of at most [budget] characters, cutting on line boundaries and
 * hard-wrapping only a single line longer than a chunk. More than [maxSections] chunks keeps the first
 * [maxSections] and ends the last with [SlackBlockLimits.TRUNCATION_MARKER]. With [balanceCodeFences] a
 * ``` block cut by a chunk boundary is closed at the end of one chunk and re-opened at the start of the next,
 * so each section renders on its own. Text that already fits is returned unchanged as a single chunk.
 */
internal fun splitSectionText(
    text: String,
    maxSections: Int,
    balanceCodeFences: Boolean,
    budget: Int = SlackBlockLimits.SECTION_TEXT_BUDGET,
): List<String> {
    require(maxSections >= 1) { "maxSections must be positive: $maxSections" }
    if (text.length <= budget) return listOf(text)
    val chunkBudget = if (balanceCodeFences) budget - FENCE_REOPEN.length - FENCE_CLOSE.length else budget
    val chunks = packLines(text = text, chunkBudget = chunkBudget)
    val truncated = chunks.size > maxSections
    val kept =
        if (truncated) {
            chunks.take(n = maxSections - 1) +
                chunks[maxSections - 1].takeSafely(limit = chunkBudget - TRUNCATION_SUFFIX.length)
        } else {
            chunks
        }
    val balanced = if (balanceCodeFences) balanceFences(chunks = kept, closeLast = truncated) else kept
    return if (truncated) balanced.dropLast(n = 1) + (balanced.last() + TRUNCATION_SUFFIX) else balanced
}

/**
 * Cuts a single section's text to [limit] characters, ending it with [SlackBlockLimits.TRUNCATION_MARKER]. Public so
 * the application-layer CVE bodies cut the same way instead of a bare `take()` that splits an entity or a pair.
 */
fun String.truncateSectionText(limit: Int = SlackBlockLimits.SECTION_TEXT_MAX_LENGTH): String =
    if (length <= limit) this else takeSafely(limit = limit - TRUNCATION_SUFFIX.length) + TRUNCATION_SUFFIX

/** Cuts plain text to [limit] characters (option labels, headers), ending it with an ellipsis. */
internal fun String.truncatePlainText(limit: Int): String =
    if (length <= limit) this else takeSafely(limit = limit - 1) + "…"

private fun packLines(text: String, chunkBudget: Int): List<String> {
    val chunks = mutableListOf<String>()
    val current = StringBuilder()
    var hasLine = false
    text
        .split('\n')
        .flatMap { line -> line.hardWrap(maxLength = chunkBudget) }
        .forEach { line ->
            if (hasLine && current.length + 1 + line.length > chunkBudget) {
                chunks += current.toString()
                current.clear()
                hasLine = false
            }
            if (hasLine) current.append('\n')
            current.append(line)
            hasLine = true
        }
    if (hasLine) chunks += current.toString()
    // A run of blank lines can pack into a chunk with no visible text, which Slack rejects as an empty section.
    return chunks.filter { it.isNotBlank() }.ifEmpty { listOf(text.takeSafely(limit = chunkBudget)) }
}

// Prefers the last space in the second half of the window so words and <url|label> links are not split.
private fun String.hardWrap(maxLength: Int): List<String> {
    if (length <= maxLength) return listOf(this)
    val pieces = mutableListOf<String>()
    var rest = this
    while (rest.length > maxLength) {
        val window = rest.takeSafely(limit = maxLength)
        val space = window.lastIndexOf(' ')
        val cut = (if (space > maxLength / 2) space + 1 else window.length).coerceAtLeast(1)
        pieces += rest.substring(0, cut)
        rest = rest.substring(cut)
    }
    if (rest.isNotEmpty()) pieces += rest
    return pieces
}

private fun balanceFences(chunks: List<String>, closeLast: Boolean): List<String> {
    var open = false
    return chunks.mapIndexedNotNull { index, chunk ->
        val reopen = open
        if (chunk.countFences() % 2 == 1) open = !open
        // A chunk that starts on the open block's closing fence needs no re-opened block: prefixing one rendered an
        // empty code block. The previous chunk already closed the block, so that leading fence is dropped instead,
        // and a chunk that was nothing but the fence disappears rather than becoming an empty section.
        val closesFirst = reopen && chunk.startsWith(CODE_FENCE)
        val body = if (closesFirst) chunk.removePrefix(CODE_FENCE).removePrefix("\n") else chunk
        if (body.isBlank()) return@mapIndexedNotNull null
        buildString {
            if (reopen && !closesFirst) append(FENCE_REOPEN)
            append(body)
            if (open && (index < chunks.lastIndex || closeLast)) append(FENCE_CLOSE)
        }
    }
}

private fun String.countFences(): Int {
    var count = 0
    var from = indexOf(CODE_FENCE)
    while (from >= 0) {
        count++
        from = indexOf(CODE_FENCE, startIndex = from + CODE_FENCE.length)
    }
    return count
}

// Never splits a surrogate pair or an escaped entity at the cut.
private fun String.takeSafely(limit: Int): String {
    if (length <= limit) return this
    val end = if (this[limit - 1].isHighSurrogate()) limit - 1 else limit
    val cut = substring(0, end)
    val ampersand = cut.lastIndexOf('&')
    return if (ampersand >= 0 && ampersand > cut.length - MAX_ENTITY_LENGTH && cut.indexOf(';', ampersand) < 0) {
        cut.substring(0, ampersand)
    } else {
        cut
    }
}
