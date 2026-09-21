<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-09-21 -->

# domain/architecture (test)

## Purpose
The layering guard for the whole domain module. It has no production counterpart: it exists to make the
transport-agnostic refactor permanent by failing the build the moment Slack, Jackson, or Gson coupling
(or a `meet`/`standup`/`common` → `command` package cycle) creeps back in.

## Key Files
| File | Description |
|------|-------------|
| `DomainLayeringGuardTest.kt` | `StringSpec` with four checks over `src/main/kotlin/dev/notypie/domain` (see below); no fixtures used |
| `EnvelopeCastGuardTest.kt` | Phase 11 cast guard: `domain/command` may contain no explicit `as`/`as?` expression beyond a shrinking baseline (empty since A2 — the slash commands now resolve their payload through `slashInvocation()`). Scans stripped source (comments/strings/import aliases excluded, scanner self-tested); a regression brake, not a proof — routing correctness lives in `SubmissionRouterTest` and the characterization suite |

The four checks, in file order:

1. **Pure packages must not import the command package.** Walks every `.kt` file under `meet/`,
   `standup/`, and `common/` and fails on any line starting with `import dev.notypie.domain.command`.
   Dependency direction is `command → {meet, standup, common}` only.
2. **No transport or serialization coupling in source.** Regex-scans every line of every main `.kt` file
   for `com.slack`, `slack.com`, Jackson imports (`com.fasterxml.jackson` and `tools.jackson`), `@Json*`
   annotations, `com.google.gson` imports, and Slack SDK payload type names (`BlockActionPayload`,
   `ViewSubmissionPayload`, `SlashCommandPayload`, `SlackApiException`, `LayoutBlock`, `ViewState`).
   Comments count too — describing Slack origin in prose is fine, naming the SDK is not.
3. **No transport or serialization libraries on the classpath.** `Class.forName` probes for
   `tools.jackson.databind.ObjectMapper`, `com.fasterxml.jackson.databind.ObjectMapper`,
   `com.google.gson.Gson`, and `com.slack.api.Slack` on the test runtime classpath. Source scanning
   cannot see the dependency graph; the root build once injected Jackson into every subproject, and
   this check is what catches that regression.
4. **No raw Slack vocabulary in identifiers.** Fails on `responseUrl` or `triggerId` in the code part of
   a line (text before `//`). Identifiers must use the neutral handle names (`replyHandle`,
   `triggerHandle`); comments such as "was Slack trigger_id" are allowed.

Checks 1 and 2 assert `domainMain.exists()` first, so a wrong working directory fails loudly. Check 4
has no such assertion and would pass vacuously if the directory were missing — run it through Gradle,
not from an IDE with an arbitrary cwd.

Known, intentionally deferred leaks that are **not** guarded: the `CommandDetailType` routing enum and
the `slackUserId` / `slackTeamId` business identifiers (see `domain/AGENTS.md`).

## For AI Agents

### Working In This Directory
- Do not weaken this spec casually. Removing a pattern, adding an allow-list entry, or dropping the
  `exists()` assertion is an architectural decision that must be written up in `domain/AGENTS.md`
  first. The expected fix for a red guard is to change the offending domain code, not the guard.
- Extending the guard is welcome: add a `label to Regex(...)` pair to the relevant list, or a class
  name to `forbiddenClasses`. Keep the labels human-readable — they are the failure message.
- The spec reads files with `File("src/main/kotlin/dev/notypie/domain")`, i.e. relative to the
  `domain/` module directory. Gradle sets that cwd; keep it that way.

### Testing Requirements
```bash
./gradlew :domain:test --tests 'dev.notypie.domain.architecture.DomainLayeringGuardTest'
```
Runs in well under a second; there is no reason to skip it locally.

### Common Patterns
- Violations are collected into a `List<String>` of `path:line [label] -> text` and compared with
  `shouldBe emptyList()`, so a failure prints every offending line at once.
- `domainKtFiles(vararg roots)` filters non-existent roots silently; pair it with an explicit
  `exists()` assertion whenever a new check depends on a directory being present.

## Dependencies

### Internal
- None at compile time — the spec inspects `domain/src/main` as files and probes the classpath
  by name, so it never imports a production class.

### External
- `io.kotest:kotest-runner-junit5`, `io.kotest:kotest-assertions-core`, `java.io.File`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
