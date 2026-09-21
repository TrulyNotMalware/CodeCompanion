<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# application/src/testFixtures/kotlin/dev/notypie/docs

## Purpose
A small Kotlin DSL over Spring REST Docs' `FieldDescriptor` so a request/response field can be declared as
`"meeting.title" type STRING means "Meeting title" example "Sprint Planning"`. It carries three custom
snippet attributes (`sample`, `format`, `default`) that a custom `.snippet` template can render as extra
columns. The package has no main counterpart and, as of this generation, no consumer: no spec imports
`dev.notypie.docs`, and `spring-restdocs-mockmvc` is only on the `testFixturesImplementation` classpath.

## Key Files
| File | Description |
|------|-------------|
| `DSL.kt` | `DocsFieldType` sealed hierarchy (`ARRAY`, `BOOLEAN`, `NUMBER`, `STRING`, `OBJECT`, `NULL`, `ANY` → `VARIES`, `DATE`, `DATETIME`, `ENUM<T>`), the `String.type(...)` infix entry points, and the `Field` wrapper with its infix modifiers |
| `Utils.kt` | `RestDocsUtils` (`DATE_FORMAT`, `DATETIME_FORMAT`, attribute factories), `RestDocsAttributeKeys` (`sample` / `format` / `default`), `EnumFormattingUtils.enumFormat` |

## For AI Agents

### Working In This Directory
- Entry point is `infix fun String.type(DocsFieldType): Field`. It wraps `PayloadDocumentation.fieldWithPath`,
  attaches empty `sample` / `format` / `default` attributes and an empty description, and marks the field
  **optional**. `DATE` and `DATETIME` additionally set `format` to `yyyy-MM-dd` / `yyyy-MM-dd'T'HH:mm:ss`.
- The `ENUM<T>` overload is the exception: it marks the field **required** and sets `format` to the
  constants joined with ` | `. `ENUM(SomeEnum::class)` enumerates via reflection; `ENUM(listOf(...))`
  takes an explicit subset.
- `Field` modifiers are all `infix` and return `this` for chaining: `means` / `description`, `example`,
  `formattedAs`, `withDefaultValue`, `isOptional`, `isIgnored`, `attributes { }`. `isOptional(false)` and
  `isIgnored(false)` are no-ops — a descriptor cannot be un-marked once optional or ignored.
- `Field.descriptor` is public so a spec can pass `fields.map { it.descriptor }` into `responseFields(...)`.
  Because the restdocs artifact is `testFixturesImplementation` only, a spec that names `FieldDescriptor`
  itself also needs a `testImplementation("org.springframework.restdocs:...")` line in
  `application/build.gradle.kts`.
- Rendering the custom attributes needs snippet templates under
  `src/test/resources/org/springframework/restdocs/templates/` — none exist yet.

### Testing Requirements
There are no specs for the DSL itself. If it gains a consumer (a MockMvc + REST Docs controller spec), keep
the DSL here and put the snippet templates in `src/test/resources`; do not move restdocs onto the main
classpath.

### Common Patterns
- `object` singletons for parameterless types so `"x" type STRING` reads as a literal; `ENUM` is a
  `data class` because it carries the constant list.
- `when (docsFieldType) { is DATE -> ...; is DATETIME -> ... }` on the sealed type — add a branch when a new
  type needs a default `format`.

## Dependencies

### Internal
None.

### External
- `org.springframework.restdocs:spring-restdocs-mockmvc` (`FieldDescriptor`, `JsonFieldType`,
  `PayloadDocumentation`, `Attributes`)
- `kotlin.reflect.KClass` for the `ENUM(clazz)` constructor

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
