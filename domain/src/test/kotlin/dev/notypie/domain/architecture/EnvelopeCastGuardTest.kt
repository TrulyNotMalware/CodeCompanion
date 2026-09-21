package dev.notypie.domain.architecture

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.io.File

class EnvelopeCastGuardTest :
    StringSpec({
        val commandMain = File("src/main/kotlin/dev/notypie/domain/command")

        val baseline = emptyMap<String, Int>()

        fun stripNonCode(source: String): String {
            val out = StringBuilder()
            var i = 0
            var blockDepth = 0
            var inLine = false
            var inString = false
            var inChar = false
            var inTriple = false
            while (i < source.length) {
                val c = source[i]
                val pair = if (i + 1 < source.length) source.substring(i, i + 2) else ""
                val triple = if (i + 2 < source.length) source.substring(i, i + 3) else ""
                when {
                    inLine ->
                        if (c == '\n') {
                            inLine = false
                            out.append(c)
                        }
                    blockDepth > 0 -> {
                        if (pair == "/*") {
                            blockDepth++
                            i++
                        } else if (pair == "*/") {
                            blockDepth--
                            i++
                        }
                        if (c == '\n') out.append(c)
                    }
                    inTriple ->
                        if (triple == "\"\"\"") {
                            inTriple = false
                            i += 2
                        }
                    inString -> {
                        if (pair == "\\\"" || pair == "\\\\") {
                            i++
                        } else if (c == '"') {
                            inString = false
                        }
                    }
                    inChar -> {
                        if (pair == "\\'" || pair == "\\\\") {
                            i++
                        } else if (c == '\'') {
                            inChar = false
                        }
                    }
                    triple == "\"\"\"" -> {
                        inTriple = true
                        out.append(' ')
                        i += 2
                    }
                    c == '"' -> {
                        inString = true
                        out.append(' ')
                    }
                    c == '\'' -> {
                        inChar = true
                        out.append(' ')
                    }
                    pair == "//" -> {
                        inLine = true
                        out.append(' ')
                    }
                    pair == "/*" -> {
                        blockDepth = 1
                        out.append(' ')
                        i++
                    }
                    else -> out.append(c)
                }
                i++
            }
            return out.toString()
        }

        val castPattern = Regex("""\bas\??\s""")

        fun countCasts(source: String): Int {
            val code =
                stripNonCode(source)
                    .lineSequence()
                    .filterNot { it.trimStart().startsWith("import ") }
                    .joinToString(separator = "\n")
            return castPattern.findAll(code).count()
        }

        "the scanner recognizes real casts and ignores comments, strings, chars, and import aliases" {
            countCasts("val x = payload as SlashInvocation") shouldBe 1
            countCasts("val x = submission as? InboundSubmission.AddParticipant") shouldBe 1
            countCasts("val x = payload as\n    SlashInvocation") shouldBe 1
            countCasts("val x = payload as/* why */ String") shouldBe 1
            countCasts("val quote = '\"'\nval x = payload as String") shouldBe 1
            countCasts("val c = 'a'\nval x = 1") shouldBe 0
            countCasts("val c = '\\''\nval x = 1") shouldBe 0
            countCasts("// treated as Foo\nval x = 1") shouldBe 0
            countCasts("/* cast as Foo\n as? Bar */ val x = 1") shouldBe 0
            countCasts("val s = \"reads as text\"") shouldBe 0
            countCasts("val s = \"\"\"multiline as? Foo\"\"\"") shouldBe 0
            countCasts("import a.b.C as AliasedC") shouldBe 0
            countCasts("val escaped = \"quote \\\" as Foo\"") shouldBe 0
            countCasts("val s = \"\${payload as String}\"") shouldBe 0
        }

        "domain/command carries no explicit cast expressions beyond the shrinking baseline" {
            commandMain.exists() shouldBe true
            val actual =
                commandMain
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .associate { file ->
                        file.relativeTo(commandMain).path to countCasts(file.readText())
                    }.filterValues { it > 0 }
            actual shouldContainExactly baseline
        }
    })
