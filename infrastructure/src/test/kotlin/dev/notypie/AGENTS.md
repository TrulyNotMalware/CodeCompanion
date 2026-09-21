<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-30 -->

# infrastructure/src/test/kotlin/dev/notypie

## Purpose
Root test package. The only file at this level is `TestApplication.kt`, the `@SpringBootApplication` that
Spring's test slices locate by walking up from each spec's package; every `@DataJpaTest` and `@SpringBootTest`
spec in the subtree boots from it and inherits `src/main`'s component scan (`configurations/`, `repository/`,
`impl/`). Each subpackage mirrors one main package and holds its specs.

## Key Files
| File | Description |
|------|-------------|
| `TestApplication.kt` | Bare `@SpringBootApplication class TestApplication` in `dev.notypie`. No beans, no profile, no exclusions — it exists only so test slices can find a configuration root. |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `common/` | `JPAJsonConverter`, `PartitionKeyUtil` unit specs (see `common/AGENTS.md`) |
| `exception/` | `DatabaseExceptionTest` — an empty placeholder spec (see `exception/AGENTS.md`) |
| `impl/` | Adapter specs: `agent/`, `command/` (+ `slack/`), `cve/`, `retry/` (see `impl/AGENTS.md`) |
| `repository/` | Persistence specs: `cve/`, `meeting/`, `outbox/` (+ `schema/`) (see `repository/AGENTS.md`) |
| `templates/` | `ModalBlockBuilder`, `ModalElementBuilder`, `ModalTemplateBuilder` specs (see `templates/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- Keep `TestApplication` in `dev.notypie`, the shared root of both source trees. Moving it deeper would
  drop `dev.notypie.configurations` (and therefore `JpaConfiguration`) out of the scan and every
  `@DataJpaTest` spec would stop finding the JPA repositories.
- Do not add `@Configuration` classes or `@Bean` overrides here. A spec that needs a stand-in collaborator
  constructs it directly or uses MockK; a spec that needs Spring-level overrides declares them on itself.
- New specs go in the package of the class under test. Spring-booting specs are the exception, not the
  default — most of this tree is plain Kotest with hand-wired collaborators.
- Spec styles in use: Kotest `BehaviorSpec` (`given` / `` `when` `` / `then`) everywhere except
  `repository/outbox/OutboundMessageCodecTest`, which is a `StringSpec`.

### Testing Requirements
```bash
./gradlew :infrastructure:test                                   # whole module
./gradlew :infrastructure:test --tests 'dev.notypie.<package>.*' # one package
./gradlew :infrastructure:test --tests 'dev.notypie.<package>.<SpecName>'
```
Spring-booting specs share a cached context per configuration: all `@DataJpaTest` specs share one H2, and
`impl/command/KafkaEventPublisherTest` (`@SpringBootTest` + `@EmbeddedKafka`) is its own context and the only
broker start in the module. Two specs need the network: `impl/command/RestClientRequesterTest` (live
jsonplaceholder API) and nothing else — `impl/agent` and `impl/cve` stub HTTP with the JDK `HttpServer` on
loopback.

### Common Patterns
- Spring specs: constructor injection with `@Autowired constructor(...)` plus
  `@ApplyExtension(extensions = [SpringExtension::class])` on the class.
- Collaborators: `mockk<T>()` (strict) with `every { } returns` and `verify(exactly = 1) { }`; `slot()` to
  capture arguments; `relaxed = true` only for `ApplicationEventPublisher`-style sinks.
- Identity defaults (`createCommandBasicInfo()`, `TEST_USER_ID`, `TEST_CHANNEL_ID`, `TEST_BOT_TOKEN`, …) come
  from `:domain` test fixtures; infra-typed builders come from `../../../../testFixtures/`.
- Named arguments on every Kotlin call, including fixture calls.

## Dependencies

### Internal
- `infrastructure/src/testFixtures/kotlin/dev/notypie/` — schema, payload, and event creators
- `domain/src/testFixtures/kotlin/dev/notypie/domain/` — `Constants.kt`, `createCommandBasicInfo`,
  `createMeetingDto`, `createRoutineDto`, `createApprovalContents`, …
- `infrastructure/src/test/resources/application.yaml` — test profile

### External
Kotest 6 (`kotest-runner-junit5`, `kotest-extensions-spring`, `kotest-assertions-core`), MockK,
`spring-boot-starter-test`, `spring-boot-starter-data-jpa-test`, `spring-boot-starter-kafka-test`, H2,
Slack SDK model + Gson (for Block Kit round-trips), Jackson 3.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
