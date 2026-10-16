package dev.notypie.domain.common

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class SafeTextTest :
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

        given("takeSurrogateSafe") {
            val text = "ab\uD83D\uDE00c"

            `when`("the limit falls between the two halves of an emoji") {
                then("the emoji is dropped whole instead of leaving a lone high surrogate") {
                    text.takeSurrogateSafe(maxLength = 3) shouldBe "ab"
                }
            }

            `when`("the limit falls right after an emoji") {
                then("the emoji is kept") {
                    text.takeSurrogateSafe(maxLength = 4) shouldBe "ab\uD83D\uDE00"
                }
            }

            `when`("the text fits the limit") {
                then("it is returned unchanged, even when it already ends in a lone high surrogate") {
                    text.takeSurrogateSafe(maxLength = 5) shouldBe text
                    "ab\uD83D".takeSurrogateSafe(maxLength = 3) shouldBe "ab\uD83D"
                }
            }

            `when`("the limit is zero") {
                then("the result is empty") {
                    text.takeSurrogateSafe(maxLength = 0) shouldBe ""
                }
            }
        }
    })
