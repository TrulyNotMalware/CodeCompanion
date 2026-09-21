<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# domain/src/testFixtures/kotlin/dev/notypie/domain/dto

## Purpose
Assertion helpers over `CommandOutput`, the value every `Command.handleEvent()` returns, so specs compare
outputs structurally instead of field by field.

## Key Files
| File | Description |
|------|-------------|
| `Utils.kt` | `CommandOutput.isEmpty()`, `infix CommandOutput.isSame(expected)`, `infix CommandOutput.shouldMatchExpected(TestValidationData)`, `data class TestValidationData(commandDetailType, commandType, commandBasicInfo)` |

## For AI Agents

### Working In This Directory
- `isEmpty()` encodes what `CommandOutput.empty()` looks like (blank ids, `NOTHING` / `SIMPLE`,
  `DO_NOTHING`, `ok = false`). Consumers: `AbstractCommandContextTest`, `EmptyContextTest`. Update it when
  `CommandOutput.empty()` gains a field, or the check silently weakens.
- `isSame` compares everything except `commandType`; `shouldMatchExpected` asserts a **successful** output
  against the basic info and types captured in `TestValidationData`. Neither has a consumer today — specs
  moved to Kotest matchers on individual properties. Delete them or start using them; do not add a third
  variant.
- All three return `Boolean`; wrap in `shouldBe true` (or convert to a Kotest `Matcher`) at the call site.

### Testing Requirements
None of their own; they are assertion glue.

### Common Patterns
- Extension / infix functions on the production type so a spec reads `output isSame expected`.

## Dependencies

### Internal
- `domain/command/dto/` (`CommandBasicInfo`, `CommandOutput`, `Status`), `domain/command/entity/`
  (`CommandType`, `CommandDetailType`)

### External
None.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
