package dev.notypie.domain.common

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class MarkupEscapeTest :
    BehaviorSpec({
        given("escapeMarkup") {
            `when`("the text carries control sequences") {
                then("a broadcast mention and a disguised link become literal text") {
                    "<!channel> <https://evil.example|Agenda>".escapeMarkup() shouldBe
                        "&lt;!channel&gt; &lt;https://evil.example|Agenda&gt;"
                }
            }

            `when`("the text already contains an ampersand or an entity") {
                then("the ampersand is escaped first so an entity is not decoded back into markup") {
                    "R&D &lt;!here&gt;".escapeMarkup() shouldBe "R&amp;D &amp;lt;!here&amp;gt;"
                }
            }

            `when`("the text has no control characters") {
                then("it is returned unchanged, emphasis included") {
                    "*bold* _it_ `code`".escapeMarkup() shouldBe "*bold* _it_ `code`"
                }
            }
        }
    })
