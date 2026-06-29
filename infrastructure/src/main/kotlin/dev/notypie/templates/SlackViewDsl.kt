package dev.notypie.templates

/**
 * Tiny type-safe builder for Slack `views.open` payloads — the JSON shape ModalTemplateBuilder
 * was assembling by hand with nested `mapOf("type" to "modal", "blocks" to buildList { ... })`
 * literals. The DSL preserves the exact key order of the previous hand-rolled maps (Jackson
 * serializes [LinkedHashMap] in insertion order, which several round-trip tests depend on)
 * while removing the visual noise that obscured the actual modal structure.
 *
 * Scoped to this module's modal needs only — extend with new helpers as templates are added
 * rather than trying to mirror the whole Slack Block Kit surface.
 */
@DslMarker
annotation class SlackViewDsl

/**
 * Entry point: builds a `view` payload as a `Map<String, Any>` ready for [jsonMapper] to
 * serialize. Returning a Map (instead of a domain object) keeps the bridge to the Slack SDK
 * loose — callers can either re-serialize to JSON or pass it through Jackson directly.
 */
fun modal(block: SlackViewBuilder.() -> Unit): Map<String, Any> = SlackViewBuilder().apply(block).build()

@SlackViewDsl
class SlackViewBuilder {
    private val view = mutableMapOf<String, Any>("type" to "modal")

    fun callbackId(id: String) {
        view["callback_id"] = id
    }

    fun privateMetadata(metadata: String) {
        view["private_metadata"] = metadata
    }

    fun title(text: String) {
        view["title"] = plainText(text = text)
    }

    fun submit(text: String) {
        view["submit"] = plainText(text = text)
    }

    fun close(text: String) {
        view["close"] = plainText(text = text)
    }

    fun blocks(block: BlocksBuilder.() -> Unit) {
        view["blocks"] = BlocksBuilder().apply(block).build()
    }

    internal fun build(): Map<String, Any> = view
}

@SlackViewDsl
class BlocksBuilder {
    private val blocks = mutableListOf<Map<String, Any>>()

    fun section(block: SectionBuilder.() -> Unit) {
        blocks.add(SectionBuilder().apply(block).build())
    }

    fun input(blockId: String, block: InputBuilder.() -> Unit) {
        blocks.add(InputBuilder(blockId = blockId).apply(block).build())
    }

    internal fun build(): List<Map<String, Any>> = blocks
}

@SlackViewDsl
class SectionBuilder {
    private val section = mutableMapOf<String, Any>("type" to "section")

    fun mrkdwn(text: String) {
        section["text"] = mapOf("type" to "mrkdwn", "text" to text)
    }

    internal fun build(): Map<String, Any> = section
}

@SlackViewDsl
class InputBuilder(
    blockId: String,
) {
    private val input =
        mutableMapOf<String, Any>(
            "type" to "input",
            "block_id" to blockId,
        )

    fun label(text: String) {
        input["label"] = plainText(text = text)
    }

    // Slack input blocks are required by default; mark optional so the block can be left empty
    // (e.g. the decline-reason detail, which is only required when the reason is "Other").
    fun optional(value: Boolean = true) {
        input["optional"] = value
    }

    fun staticSelect(actionId: String, placeholder: String? = null, options: OptionsBuilder.() -> Unit) {
        val element =
            mutableMapOf<String, Any>(
                "type" to "static_select",
                "action_id" to actionId,
            )
        if (placeholder != null) element["placeholder"] = plainText(text = placeholder)
        element["options"] = OptionsBuilder().apply(options).build()
        input["element"] = element
    }

    fun plainTextInput(actionId: String, multiline: Boolean = false, initialValue: String? = null) {
        val element =
            mutableMapOf<String, Any>(
                "type" to "plain_text_input",
                "action_id" to actionId,
                "multiline" to multiline,
            )
        if (initialValue != null) element["initial_value"] = initialValue
        input["element"] = element
    }

    fun multiStaticSelect(actionId: String, placeholder: String? = null, options: OptionsBuilder.() -> Unit) {
        val element =
            mutableMapOf<String, Any>(
                "type" to "multi_static_select",
                "action_id" to actionId,
            )
        if (placeholder != null) element["placeholder"] = plainText(text = placeholder)
        element["options"] = OptionsBuilder().apply(options).build()
        input["element"] = element
    }

    fun multiUsersSelect(actionId: String, placeholder: String? = null) {
        val element =
            mutableMapOf<String, Any>(
                "type" to "multi_users_select",
                "action_id" to actionId,
            )
        if (placeholder != null) element["placeholder"] = plainText(text = placeholder)
        input["element"] = element
    }

    fun conversationsSelect(actionId: String, placeholder: String? = null) {
        val element =
            mutableMapOf<String, Any>(
                "type" to "conversations_select",
                "action_id" to actionId,
            )
        if (placeholder != null) element["placeholder"] = plainText(text = placeholder)
        input["element"] = element
    }

    fun timePicker(actionId: String, initialTime: String? = null, placeholder: String? = null) {
        val element =
            mutableMapOf<String, Any>(
                "type" to "timepicker",
                "action_id" to actionId,
            )
        if (initialTime != null) element["initial_time"] = initialTime
        if (placeholder != null) element["placeholder"] = plainText(text = placeholder)
        input["element"] = element
    }

    fun datePicker(actionId: String, initialDate: String? = null, placeholder: String? = null) {
        val element =
            mutableMapOf<String, Any>(
                "type" to "datepicker",
                "action_id" to actionId,
            )
        if (initialDate != null) element["initial_date"] = initialDate
        if (placeholder != null) element["placeholder"] = plainText(text = placeholder)
        input["element"] = element
    }

    internal fun build(): Map<String, Any> = input
}

@SlackViewDsl
class OptionsBuilder {
    private val options = mutableListOf<Map<String, Any>>()

    fun option(text: String, value: String) {
        options.add(
            mapOf(
                "text" to plainText(text = text),
                "value" to value,
            ),
        )
    }

    internal fun build(): List<Map<String, Any>> = options
}

private fun plainText(text: String): Map<String, String> = mapOf("type" to "plain_text", "text" to text)
