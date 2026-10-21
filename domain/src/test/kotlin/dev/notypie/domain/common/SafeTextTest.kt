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

        given("takeEntitySafe") {
            `when`("the text fits the limit") {
                then("it is returned unchanged, even when it ends in what looks like part of an entity") {
                    "a &amp; b".takeEntitySafe(maxLength = 9) shouldBe "a &amp; b"
                    "ab &am".takeEntitySafe(maxLength = 6) shouldBe "ab &am"
                }
            }

            `when`("the limit falls inside an escape entity") {
                then("the partial entity is dropped") {
                    "ab&amp;cd".takeEntitySafe(maxLength = 5) shouldBe "ab"
                }
            }

            `when`("a whole escape entity ends exactly at the limit") {
                then("the entity is kept") {
                    "ab&amp;cd".takeEntitySafe(maxLength = 7) shouldBe "ab&amp;"
                }
            }

            `when`("a shorter escape entity ends exactly at the limit, its ampersand inside the entity window") {
                then("the semicolon after the ampersand marks it whole, so it is kept") {
                    "ab&lt;cd".takeEntitySafe(maxLength = 6) shouldBe "ab&lt;"
                }
            }

            `when`("an ampersand without a semicolon sits exactly one entity length before the limit") {
                then("it lies outside the entity window, so it is plain text and is kept") {
                    "ab&cdefg".takeEntitySafe(maxLength = 7) shouldBe "ab&cdef"
                }
            }

            `when`("the limit falls right after an ampersand") {
                then("the lone ampersand is dropped") {
                    "ab&amp;".takeEntitySafe(maxLength = 3) shouldBe "ab"
                }
            }

            `when`("an ampersand lies further back than one entity length and has no semicolon after it") {
                then("it is plain text, not a cut entity, and is kept") {
                    "R&D notes".takeEntitySafe(maxLength = 7) shouldBe "R&D not"
                }
            }

            `when`("the limit falls between the two halves of an emoji") {
                then("the lone high surrogate is dropped") {
                    "ab\uD83D\uDE00c".takeEntitySafe(maxLength = 3) shouldBe "ab"
                }
            }
        }

        given("splitLineBreaks") {
            `when`("two words are separated by each kind of line break") {
                then("LF, CR, CRLF, U+2028, U+2029, U+0085, U+000B and U+000C each split them into two lines") {
                    val lineBreaks =
                        listOf("\n", "\r", "\r\n", "\u2028", "\u2029", "\u0085", "\u000B", "\u000C")
                    lineBreaks.map { lineBreak -> "a${lineBreak}b".splitLineBreaks() } shouldBe
                        lineBreaks.map { listOf("a", "b") }
                }
            }

            `when`("the text carries two CRLF breaks in a row") {
                then("each CRLF is one break, not two, so only one empty line lies between them") {
                    "a\r\n\r\nb".splitLineBreaks() shouldBe listOf("a", "", "b")
                }
            }

            `when`("the text ends with a line break") {
                then("the trailing empty element is kept") {
                    "a\n".splitLineBreaks() shouldBe listOf("a", "")
                }
            }

            `when`("the text has no line break") {
                then("it stays one element") {
                    "plain text".splitLineBreaks() shouldBe listOf("plain text")
                }
            }

            `when`("the text is empty") {
                then("the result is one empty element") {
                    "".splitLineBreaks() shouldBe listOf("")
                }
            }
        }

        given("isBlankAcrossLineBreaks") {
            `when`("the text is only a NEL (U+0085), which String.isBlank does not count as whitespace") {
                then("it is blank, because splitLineBreaks treats NEL as a line break") {
                    "\u0085".isBlankAcrossLineBreaks() shouldBe true
                }
            }

            `when`("NELs are mixed with spaces, tabs and U+2028") {
                then("it is blank") {
                    " \u0085\t\u2028 \u0085\u0085 ".isBlankAcrossLineBreaks() shouldBe true
                }
            }

            `when`("NELs surround real text") {
                then("it is not blank") {
                    "\u0085what is kotlin?\u0085".isBlankAcrossLineBreaks() shouldBe false
                }
            }

            `when`("the text is empty or only spaces") {
                then("it is blank, as String.isBlank says") {
                    "".isBlankAcrossLineBreaks() shouldBe true
                    "   ".isBlankAcrossLineBreaks() shouldBe true
                }
            }
        }
    })
