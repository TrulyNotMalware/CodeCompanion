package dev.notypie.domain.architecture

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File

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
