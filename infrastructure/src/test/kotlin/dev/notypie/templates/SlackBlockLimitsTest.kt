package dev.notypie.templates

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith

class SlackBlockLimitsTest :
    BehaviorSpec({
        val budget = SlackBlockLimits.SECTION_TEXT_BUDGET

        given("splitSectionText") {
            `when`("the text already fits one section") {
                val text = "short answer\nwith two lines"

                then("it comes back unchanged as a single chunk") {
                    splitSectionText(text = text, maxSections = 50, balanceCodeFences = true) shouldBe listOf(text)
                }
            }

            `when`("a long multi-line answer exceeds one section") {
                val lines = (1..400).map { index -> "line $index — ${"x".repeat(n = 20)}" }
                val text = lines.joinToString(separator = "\n")
                val chunks = splitSectionText(text = text, maxSections = 50, balanceCodeFences = false)

                then("every chunk stays within the section budget") {
                    chunks.size shouldBeGreaterThan 1
                    chunks.forEach { it.length shouldBeLessThanOrEqual budget }
                }
                then("chunks cut on line boundaries, so joining them restores the text") {
                    chunks.joinToString(separator = "\n") shouldBe text
                }
            }

            `when`("a single line is longer than a section") {
                val word = "word "
                val text = word.repeat(n = 1_500).trimEnd()
                val chunks = splitSectionText(text = text, maxSections = 50, balanceCodeFences = false)

                then("it is wrapped at a space and nothing is lost") {
                    chunks.forEach { it.length shouldBeLessThanOrEqual budget }
                    chunks.joinToString(separator = "") shouldBe text
                    chunks.dropLast(n = 1).forEach { it shouldEndWith " " }
                }
            }

            `when`("the text needs more chunks than allowed") {
                val text = (1..2_000).joinToString(separator = "\n") { "row $it ${"y".repeat(n = 60)}" }
                val chunks = splitSectionText(text = text, maxSections = 3, balanceCodeFences = false)

                then("only maxSections chunks are kept and the last ends with the truncation marker") {
                    chunks shouldHaveSize 3
                    chunks.last() shouldEndWith SlackBlockLimits.TRUNCATION_MARKER
                    chunks.forEach { it.length shouldBeLessThanOrEqual budget }
                }
            }

            `when`("a code block is cut by a chunk boundary") {
                val code =
                    (1..300).joinToString(
                        separator = "\n",
                    ) { "val v$it = compute($it) // ${"c".repeat(n = 10)}" }
                val text = "Here is the code:\n```\n$code\n```\nThat's all."
                val chunks = splitSectionText(text = text, maxSections = 50, balanceCodeFences = true)

                then("each chunk has balanced fences so it renders on its own") {
                    chunks.size shouldBeGreaterThan 1
                    chunks.forEach { chunk ->
                        Regex("```").findAll(chunk).count() % 2 shouldBe 0
                        chunk.length shouldBeLessThanOrEqual budget
                    }
                }
                then("the continuation re-opens the block") {
                    chunks[1] shouldStartWith "```\n"
                }
            }

            `when`("a chunk boundary falls right before a code block's closing fence") {
                val smallBudget = 40
                val text = "```\n" + "a".repeat(n = 27) + "\n```\nafter"
                val chunks =
                    splitSectionText(text = text, maxSections = 5, balanceCodeFences = true, budget = smallBudget)

                then("the continuation starts after the fence instead of re-opening an empty block") {
                    chunks shouldBe listOf("```\n" + "a".repeat(n = 27) + "\n```", "after")
                }
            }

            `when`("a chunk is nothing but the closing fence") {
                val smallBudget = 40
                val text = "```\n" + "a".repeat(n = 27) + "\n```\n" + "b".repeat(n = 31)
                val chunks =
                    splitSectionText(text = text, maxSections = 5, balanceCodeFences = true, budget = smallBudget)

                then("it is dropped rather than sent as an empty section") {
                    chunks shouldBe listOf("```\n" + "a".repeat(n = 27) + "\n```", "b".repeat(n = 31))
                }
            }

            `when`("the truncation point falls inside an open code block") {
                val code = (1..2_000).joinToString(separator = "\n") { "println($it) // ${"d".repeat(n = 30)}" }
                val chunks = splitSectionText(text = "```\n$code\n```", maxSections = 2, balanceCodeFences = true)

                then("the block is closed before the marker so the marker is not rendered as code") {
                    chunks shouldHaveSize 2
                    chunks.last() shouldEndWith "```\n${SlackBlockLimits.TRUNCATION_MARKER}"
                    chunks.forEach { it.length shouldBeLessThanOrEqual budget }
                }
            }

            `when`("a hard wrap lands on a surrogate pair or an escaped entity") {
                val emoji = "😀"
                val text = "a".repeat(n = budget - 1) + emoji + "b".repeat(n = 10)
                val entityText = "a".repeat(n = budget - 2) + "&amp;" + "b".repeat(n = 10)

                then("the pair is kept whole") {
                    val chunks = splitSectionText(text = text, maxSections = 5, balanceCodeFences = false)
                    chunks.joinToString(separator = "") shouldBe text
                    chunks.first().last().isHighSurrogate() shouldBe false
                }
                then("the entity is kept whole") {
                    val chunks = splitSectionText(text = entityText, maxSections = 5, balanceCodeFences = false)
                    chunks.joinToString(separator = "") shouldBe entityText
                    chunks.first() shouldNotContain "&am"
                }
            }
        }

        given("truncateSectionText") {
            `when`("the text is over the limit") {
                val truncated = "z".repeat(n = 5_000).truncateSectionText()

                then("it is cut to the section cap with the marker") {
                    truncated.length shouldBeLessThanOrEqual SlackBlockLimits.SECTION_TEXT_MAX_LENGTH
                    truncated shouldEndWith SlackBlockLimits.TRUNCATION_MARKER
                }
            }

            `when`("the text fits") {
                then("it is unchanged") {
                    "fits".truncateSectionText() shouldBe "fits"
                }
            }
        }

        given("truncatePlainText") {
            `when`("the text is over the limit") {
                then("it is cut to the limit and ends with an ellipsis") {
                    val truncated = "t".repeat(n = 100).truncatePlainText(limit = 75)
                    truncated.length shouldBe 75
                    truncated shouldEndWith "…"
                }
            }
        }
    })
