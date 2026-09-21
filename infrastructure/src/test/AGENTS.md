<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-30 -->

# infrastructure/src/test

## Purpose
Test source set for `:infrastructure`: Kotest specs under `kotlin/` and the test-profile Spring config under
`resources/`. Specs come in three flavours — plain Kotest unit specs (most files), `@DataJpaTest` repository
specs on an embedded H2, and one `@SpringBootTest` with `@EmbeddedKafka` — and every Spring-booting one
bootstraps from `kotlin/dev/notypie/TestApplication.kt`. Builders shared with `:application` live in
`../testFixtures/`, never here.

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `kotlin/` | Spec sources, package-mirrored to `src/main/kotlin` (see `kotlin/AGENTS.md`) |
| `resources/` | Test `application.yaml` (logging levels, Kafka serializer config) (see `resources/AGENTS.md`) |

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
