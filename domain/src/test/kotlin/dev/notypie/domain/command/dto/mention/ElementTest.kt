package dev.notypie.domain.command.dto.mention

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.readValue

class ElementTest :
    BehaviorSpec({
        val mapper =
            JsonMapper
                .builder()
                .addModule(KotlinModule.Builder().build())
                .build()

        given("extractText") {
            `when`("text is PlainText") {
                val element =
                    Element(
                        type = "text",
                        userId = null,
                        text = PlainText(value = "hello world"),
                    )

                then("should return the plain text value") {
                    element.extractText() shouldBe "hello world"
                }
            }

            `when`("text is TextObject") {
                val element =
                    Element(
                        type = "text",
                        userId = null,
                        text = TextObject(type = "mrkdwn", text = "*bold*"),
                    )

                then("should return the text field") {
                    element.extractText() shouldBe "*bold*"
                }
            }

            `when`("text is null") {
                val element = Element(type = "user", userId = "U001")

                then("should return null") {
                    element.extractText() shouldBe null
                }
            }
        }

        given("TextValueDeserializer") {
            `when`("JSON value is a plain string") {
                val json = """{"type":"text","user_id":null,"text":"hello"}"""

                val element = mapper.readValue<Element>(json)

                then("text should be PlainText") {
                    element.text.shouldBeInstanceOf<PlainText>().value shouldBe "hello"
                }
            }

            `when`("JSON value is an object") {
                val json =
                    """{"type":"text","user_id":null,"text":{"type":"mrkdwn","text":"*bold*","verbatim":false,"emoji":true}}"""

                val element = mapper.readValue<Element>(json)

                then("text should be TextObject") {
                    val textObj = element.text.shouldBeInstanceOf<TextObject>()
                    textObj.type shouldBe "mrkdwn"
                    textObj.text shouldBe "*bold*"
                    textObj.verbatim shouldBe false
                    textObj.emoji shouldBe true
                }
            }

            `when`("JSON text object has defaults for verbatim and emoji") {
                val json = """{"type":"text","user_id":null,"text":{"type":"plain_text","text":"hi"}}"""

                val element = mapper.readValue<Element>(json)

                then("verbatim and emoji should default to true") {
                    val textObj = element.text as TextObject
                    textObj.verbatim shouldBe true
                    textObj.emoji shouldBe true
                }
            }

            `when`("JSON text is null") {
                val json = """{"type":"user","user_id":"U001","text":null}"""

                val element = mapper.readValue<Element>(json)

                then("text should be null") {
                    element.text shouldBe null
                }
            }
        }

        given("PlainText") {
            `when`("created with value") {
                val plainText = PlainText(value = "test")

                then("value should match") {
                    plainText.value shouldBe "test"
                }
            }
        }

        given("TextObject") {
            `when`("created with all fields") {
                val textObj =
                    TextObject(
                        type = "mrkdwn",
                        text = "*bold*",
                        verbatim = false,
                        emoji = false,
                    )

                then("fields should match") {
                    textObj.type shouldBe "mrkdwn"
                    textObj.text shouldBe "*bold*"
                    textObj.verbatim shouldBe false
                    textObj.emoji shouldBe false
                }
            }
        }
    })
