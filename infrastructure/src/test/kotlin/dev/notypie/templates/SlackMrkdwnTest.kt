package dev.notypie.templates

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class SlackMrkdwnTest :
    BehaviorSpec({
        given("escapeMrkdwn") {
            `when`("the text carries Slack control sequences") {
                then("a broadcast mention becomes literal text") {
                    "<!channel> standup is late".escapeMrkdwn() shouldBe "&lt;!channel&gt; standup is late"
                }
                then("a disguised link loses its angle brackets") {
                    "<https://evil.example|Patch here>".escapeMrkdwn() shouldBe
                        "&lt;https://evil.example|Patch here&gt;"
                }
                then("a version range such as < 2.3.1 survives as text") {
                    "affects < 2.3.1 and > 1.0".escapeMrkdwn() shouldBe "affects &lt; 2.3.1 and &gt; 1.0"
                }
            }

            `when`("the text already contains an ampersand or an entity") {
                then("the ampersand is escaped first so entities are not decoded back into markup") {
                    "R&D &lt;!here&gt;".escapeMrkdwn() shouldBe "R&amp;D &amp;lt;!here&amp;gt;"
                }
            }

            `when`("the text has no control characters") {
                then("it is returned unchanged, including mrkdwn emphasis") {
                    "*bold* _it_ `code`".escapeMrkdwn() shouldBe "*bold* _it_ `code`"
                }
            }
        }

        given("neutralizeBroadcastMentions") {
            `when`("model output carries a special mention") {
                then("channel, here, everyone and the legacy group alias become literal text") {
                    "<!channel> <!here> <!everyone> <!group>".neutralizeBroadcastMentions() shouldBe
                        "&lt;!channel&gt; &lt;!here&gt; &lt;!everyone&gt; &lt;!group&gt;"
                }
                then("a labelled form and a user-group mention are defused too") {
                    "<!here|here> ping <!subteam^S0123|@backend>".neutralizeBroadcastMentions() shouldBe
                        "&lt;!here|here&gt; ping &lt;!subteam^S0123|@backend&gt;"
                }
            }

            `when`("model output uses ordinary formatting") {
                then("links, user mentions, emphasis and date formatting survive unchanged") {
                    val text = "See <https://example.com|docs>, ask <@U123>, *bold* <!date^1392734382^{date}|Feb 18>"
                    text.neutralizeBroadcastMentions() shouldBe text
                }
            }
        }
    })
