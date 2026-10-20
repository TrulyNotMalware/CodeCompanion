<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-20 -->

# domain/common (test)

## Purpose
Specs for the validation DSL in `domain/common/` — the `validate { }` / `validateAndReturn { }`
builders and the infix matchers every entity (`Meeting`, `Routine`, `StandupSession`, ...) uses in
its `init` block.

## Key Files
| File | Description |
|------|-------------|
| `SafeTextTest.kt` | `escapeMarkup`: `<!channel>` and `<url\|label>` become literal, `&` is escaped first so an existing entity is not decoded back into markup, plain emphasis passes through unchanged. `takeSurrogateSafe`: a limit between the two halves of a surrogate pair drops the pair, a limit right after it keeps it, a text within the limit comes back unchanged even when it already ends in a lone high surrogate, and a zero limit gives `""`. `takeEntitySafe`: a text that fits comes back unchanged (even ending in `&am`), a cut inside `&amp;` drops the partial entity, a whole entity ending exactly at the limit is kept, a shorter `&lt;` ending at the limit is kept by its `;` alone (its `&` is inside the window), an `&` with no `;` exactly 5 characters back (`ab&cdef`) is kept by the strict window boundary alone, a lone `&` at the cut is dropped, an `&` further back than one entity with no `;` (`R&D not`) is kept, and a lone high surrogate at the cut is dropped. `splitLineBreaks`: LF, CR, CRLF, U+2028, U+2029, U+0085, U+000B and U+000C each split `a<break>b` into `[a, b]`; two CRLFs in a row give `[a, "", b]` (CRLF is one break); `"a\n"` keeps its trailing empty element (`[a, ""]`, unlike Java's `String.split`); a text without breaks stays one element; `""` gives `[""]`. `isBlankAcrossLineBreaks`: a NEL (U+0085) alone is blank, NELs mixed with spaces, tabs and U+2028 are blank, NELs around real text are not, and `""` / spaces stay blank as with `isBlank` |
| `ValidationBuilderTest.kt` | `BehaviorSpec` over the DSL: `validate` throws `ValidationException` while `validateAndReturn` collects a `List` of errors (`fieldName`, `value`, `reason`); `and` / `or` chaining (full `or` truth table, plus an earlier failing field followed by an `or` inside `shouldNotBeNullAnd` / `ifNotNull` that must leave the earlier error in place, the same field failing `shouldSatisfy("must be even")` before an `and` / `ifNotNull` / `shouldNotBeNullAnd` block whose inner `or` passes (only "must be even" remains), a `Field` stored in a `val` and OR-ed after another field failed (right passes / left passes and right fails / both fail), an equal error from another field before a satisfied `or`, and an `and` block counted as the left operand); strings (`notBlank { }`, `shouldNotBeNullAnd`, `ifNotNull`, `shouldBeLongerThan` / `ShorterThan`, `shouldBeEmail`, `shouldMatchPattern` with `Regex` or `String`, `shouldBeOneOf` for `String` and `Int`); integers (`shouldBePositive` / `Negative` / `NonNegative`, `GreaterThan[OrEqualTo]`, `LessThan[OrEqualTo]`, `shouldBeBetween`); `LocalDateTime` (`shouldBeAfter` / `Before`, `shouldBeInFuture` / `InPast`); collections (`shouldHaveSize`, `MinSize`, `MaxSize`, `shouldNotBeEmpty`); `shouldSatisfy` with the default reason "does not satisfy the required condition" and with a custom message; an error added to a builder after `validate()` threw does not appear in the thrown exception's `details`, for both a blank class name (`ValidationException`) and a named one (`ValidationExceptionWithName`), each type asserted. No fixtures |

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
