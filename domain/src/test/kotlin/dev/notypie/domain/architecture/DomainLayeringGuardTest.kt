package dev.notypie.domain.architecture

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Architectural guards that keep the domain layer honest after the transport-agnostic refactor.
 *
 * 1. The pure-domain packages (meet/standup/common) must never depend on the command package —
 *    command may depend on them, not the reverse, which prevents the removed package cycle from
 *    silently returning.
 * 2. The whole domain source set must stay free of transport/serialization coupling (Slack SDK,
 *    Slack API URLs, Jackson, Gson). Neutral abstractions may *describe* their Slack origin in a
 *    comment (e.g. "was Slack trigger_id"); they may not import or hardcode the transport itself.
 *
 * Known, intentionally-deferred leaks tracked elsewhere (not guarded here yet): the `CommandDetailType`
 * routing enum and the `slackUserId`/`slackTeamId` business identifiers.
 */
class DomainLayeringGuardTest :
    StringSpec({
        val domainMain = File("src/main/kotlin/dev/notypie/domain")

        fun domainKtFiles(vararg roots: File): List<File> =
            roots
                .filter { it.exists() }
                .flatMap { it.walkTopDown().toList() }
                .filter { it.isFile && it.extension == "kt" }

        "pure domain packages must not import the command package" {
            domainMain.exists() shouldBe true
            val pureRoots = listOf("meet", "standup", "common").map { File(domainMain, it) }
            val violations =
                domainKtFiles(*pureRoots.toTypedArray()).flatMap { file ->
                    file
                        .readLines()
                        .filter { it.trimStart().startsWith("import dev.notypie.domain.command") }
                        .map { "${file.path} -> ${it.trim()}" }
                }
            violations shouldBe emptyList()
        }

        "domain source must not leak transport or serialization coupling" {
            domainMain.exists() shouldBe true
            val forbidden =
                listOf(
                    // Catches imports AND fully-qualified inline references.
                    "Slack SDK reference" to Regex("""\bcom\.slack\b"""),
                    "Slack API URL literal" to Regex("""slack\.com"""),
                    "Jackson import" to Regex("""import\s+(com\.fasterxml\.jackson|tools\.jackson)"""),
                    "Jackson annotation" to Regex("""@Json[A-Za-z]+"""),
                    "Gson import" to Regex("""import\s+com\.google\.gson"""),
                    "Slack SDK payload type" to
                        Regex(
                            """\b(BlockActionPayload|ViewSubmissionPayload|SlashCommandPayload""" +
                                """|SlackApiException|LayoutBlock|ViewState)\b""",
                        ),
                )
            val violations =
                domainKtFiles(domainMain).flatMap { file ->
                    file.readLines().withIndex().flatMap { (index, line) ->
                        forbidden
                            .filter { (_, pattern) -> pattern.containsMatchIn(line) }
                            .map { (label, _) -> "${file.path}:${index + 1} [$label] -> ${line.trim()}" }
                    }
                }
            violations shouldBe emptyList()
        }

        // Source scanning cannot see the dependency graph: the root build once injected Jackson into
        // every subproject, so domain compiled against it with zero imports. Probing the test-runtime
        // classpath fails fast if a shared-injection regression ever puts these libraries back.
        "domain classpath must stay free of transport and serialization libraries" {
            val forbiddenClasses =
                listOf(
                    "tools.jackson.databind.ObjectMapper",
                    "com.fasterxml.jackson.databind.ObjectMapper",
                    "com.google.gson.Gson",
                    "com.slack.api.Slack",
                )
            val present = forbiddenClasses.filter { runCatching { Class.forName(it) }.isSuccess }
            present shouldBe emptyList()
        }

        // Slack vocabulary in code identifiers was neutralized to handle names (replyHandle /
        // triggerHandle); comments may still describe Slack origin ("was Slack trigger_id").
        "domain identifiers must not reuse raw Slack vocabulary" {
            val forbiddenVocabulary =
                listOf(
                    "Slack vocabulary identifier" to Regex("""\b(responseUrl|triggerId)\b"""),
                )
            val violations =
                domainKtFiles(domainMain).flatMap { file ->
                    file.readLines().withIndex().flatMap { (index, line) ->
                        val code = line.substringBefore("//")
                        forbiddenVocabulary
                            .filter { (_, pattern) -> pattern.containsMatchIn(code) }
                            .map { (label, _) -> "${file.path}:${index + 1} [$label] -> ${line.trim()}" }
                    }
                }
            violations shouldBe emptyList()
        }
    })
