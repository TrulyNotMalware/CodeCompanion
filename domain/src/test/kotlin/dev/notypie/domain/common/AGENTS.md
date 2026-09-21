<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# domain/common (test)

## Purpose
Specs for the validation DSL in `domain/common/` — the `validate { }` / `validateAndReturn { }`
builders and the infix matchers every entity (`Meeting`, `Routine`, `StandupSession`, ...) uses in
its `init` block.

## Key Files
| File | Description |
|------|-------------|
| `ValidationBuilderTest.kt` | `BehaviorSpec` over the DSL: `validate` throws `ValidationException` while `validateAndReturn` collects a `List` of errors (`fieldName`, `value`, `reason`); `and` / `or` chaining; strings (`notBlank { }`, `shouldNotBeNullAnd`, `ifNotNull`, `shouldBeLongerThan` / `ShorterThan`, `shouldBeEmail`, `shouldMatchPattern` with `Regex` or `String`, `shouldBeOneOf` for `String` and `Int`); integers (`shouldBePositive` / `Negative` / `NonNegative`, `GreaterThan[OrEqualTo]`, `LessThan[OrEqualTo]`, `shouldBeBetween`); `LocalDateTime` (`shouldBeAfter` / `Before`, `shouldBeInFuture` / `InPast`); collections (`shouldHaveSize`, `MinSize`, `MaxSize`, `shouldNotBeEmpty`); `shouldSatisfy` with the default reason "does not satisfy the required condition" and with a custom message. No fixtures |

## For AI Agents

### Working In This Directory
- Add a case here whenever a matcher is added to the DSL; entity specs only cover the matchers they
  happen to use.
- The `LocalDateTime` cases use `now()` ± 1h, so they are wall-clock dependent but safe.
- Error `value` is the `toString()` of the checked object (`"99"`, `"CC-123"`), which is what the
  `shouldSatisfy` cases pin.

### Testing Requirements
```bash
./gradlew :domain:test --tests 'dev.notypie.domain.common.ValidationBuilderTest'
```

### Common Patterns
- `assertSoftly { }` around multi-field error lists so every mismatch is reported in one run.
- `shouldThrowExactly<ValidationException>` for the throwing entry point; `validateAndReturn(...)`
  with `size shouldBe n` for the collecting one.

## Dependencies

### Internal
- `dev.notypie.domain.common.*` (DSL), `dev.notypie.domain.common.error.ValidationException`.

### External
- Kotest (`BehaviorSpec`, `assertSoftly`, `shouldThrowExactly`, `shouldBe`).

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
