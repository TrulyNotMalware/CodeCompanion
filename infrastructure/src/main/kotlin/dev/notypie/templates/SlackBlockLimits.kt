package dev.notypie.templates

object SlackBlockLimits {
    const val SECTION_TEXT_MAX_LENGTH: Int = 3_000
    const val SECTION_TEXT_BUDGET: Int = 2_900
    const val SECTION_FIELD_MAX_LENGTH: Int = 2_000

    // Undocumented: chat.postMessage fails with msg_blocks_too_long near 13,200 characters of block text in total.
    const val MESSAGE_TEXT_BUDGET: Int = 12_000
    const val MESSAGE_BODY_BUDGET: Int = MESSAGE_TEXT_BUDGET - 1_000
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

private const val MAX_ENTITY_LENGTH = 5

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

fun splitMessageText(text: String, maxMessages: Int): List<String> =
    splitSectionText(
        text = text,
        maxSections = maxMessages,
        balanceCodeFences = true,
        budget = SlackBlockLimits.MESSAGE_BODY_BUDGET,
    )

fun String.truncateSectionText(limit: Int = SlackBlockLimits.SECTION_TEXT_MAX_LENGTH): String =
    if (length <= limit) this else takeSafely(limit = limit - TRUNCATION_SUFFIX.length) + TRUNCATION_SUFFIX

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
    // Slack rejects a section whose text is blank.
    return chunks.filter { it.isNotBlank() }.ifEmpty { listOf(text.takeSafely(limit = chunkBudget)) }
}

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
