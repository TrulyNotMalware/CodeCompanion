package dev.notypie.domain.architecture

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Architectural guard: the pure-domain packages (meet/standup/user/common) must never
 * depend on the command package. command may depend on them, not the reverse — this keeps
 * the domain model free of the Slack command/workflow machinery and prevents the
 * package cycle removed in the transport-agnostic refactor from silently returning.
 */
class DomainLayeringGuardTest :
    StringSpec({
        "pure domain packages must not import the command package" {
            val sourceRoot = File("src/main/kotlin/dev/notypie/domain")
            sourceRoot.exists() shouldBe true
            val pureRoots = listOf("meet", "standup", "user", "common")
            val violations =
                pureRoots
                    .map { File(sourceRoot, it) }
                    .filter { it.exists() }
                    .flatMap { it.walkTopDown().toList() }
                    .filter { it.isFile && it.extension == "kt" }
                    .flatMap { file ->
                        file
                            .readLines()
                            .filter { it.trimStart().startsWith("import dev.notypie.domain.command") }
                            .map { "${file.path} -> ${it.trim()}" }
                    }
            violations shouldBe emptyList()
        }
    })
