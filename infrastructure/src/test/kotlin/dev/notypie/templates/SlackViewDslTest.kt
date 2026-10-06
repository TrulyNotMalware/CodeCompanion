package dev.notypie.templates

import com.slack.api.model.block.InputBlock
import com.slack.api.model.block.element.PlainTextInputElement
import com.slack.api.model.view.View
import com.slack.api.util.json.GsonFactory
import dev.notypie.common.jsonMapper
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

class SlackViewDslTest :
    BehaviorSpec({
        given("plainTextInput") {
            `when`("one input sets maxLength and another leaves it out") {
                val json =
                    jsonMapper.writeValueAsString(
                        modal {
                            callbackId(id = "dsl_test")
                            title(text = "DSL")
                            blocks {
                                input(blockId = "capped") {
                                    label(text = "Capped")
                                    plainTextInput(actionId = "capped_input", multiline = true, maxLength = 255)
                                }
                                input(blockId = "open") {
                                    label(text = "Open")
                                    plainTextInput(actionId = "open_input")
                                }
                            }
                        },
                    )
                val inputs =
                    GsonFactory
                        .createSnakeCase()
                        .fromJson(json, View::class.java)
                        .blocks
                        .filterIsInstance<InputBlock>()
                        .associate { it.blockId to (it.element as PlainTextInputElement) }

                then("max_length is emitted only for the capped input") {
                    inputs.getValue("capped").maxLength shouldBe 255
                    inputs.getValue("capped").isMultiline shouldBe true
                    inputs.getValue("open").maxLength shouldBe null
                    json.substringAfter("\"open\"") shouldNotContain "max_length"
                }
            }
        }
    })
