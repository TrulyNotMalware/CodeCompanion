package dev.notypie.application.service.cve.ai

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class NoopAiSummarizerTest :
    BehaviorSpec({
        val summarizer = NoopAiSummarizer()

        given("an event with short content") {
            val request = createSummaryRequest(eventTitle = "Title", rawContent = "Short body.")

            `when`("summarized") {
                val summary = summarizer.summarize(request = request)

                then("it deterministically joins the title and the raw content") {
                    summary shouldBe "Title\n\nShort body."
                }
            }
        }

        given("an event with blank content") {
            val request = createSummaryRequest(eventTitle = "Only title", rawContent = "")

            `when`("summarized") {
                val summary = summarizer.summarize(request = request)

                then("only the title is returned") {
                    summary shouldBe "Only title"
                }
            }
        }

        given("an event whose content exceeds the truncation cap") {
            val longBody = "x".repeat(NoopAiSummarizer.MAX_CONTENT_CHARS + 500)
            val request = createSummaryRequest(eventTitle = "Title", rawContent = longBody)

            `when`("summarized") {
                val summary = summarizer.summarize(request = request)

                then("the content is truncated to the cap") {
                    val body = summary.removePrefix("Title\n\n")
                    body.length shouldBe NoopAiSummarizer.MAX_CONTENT_CHARS
                }
            }
        }

        given("two calls with the same request") {
            val request = createSummaryRequest(rawContent = "Same input.")

            `when`("summarized twice") {
                val first = summarizer.summarize(request = request)
                val second = summarizer.summarize(request = request)

                then("output is identical and makes no external call") {
                    first shouldBe second
                    first shouldContain "Same input."
                    first shouldNotContain "===== BEGIN"
                }
            }
        }
    })
