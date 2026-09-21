package dev.notypie.templates

// Preserves insertion order (mutableMapOf → LinkedHashMap); round-trip tests depend on this exact key order.
@DslMarker
annotation class SlackViewDsl

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
