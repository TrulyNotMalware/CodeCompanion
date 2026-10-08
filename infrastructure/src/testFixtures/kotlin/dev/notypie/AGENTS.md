<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-08 -->

# infrastructure/src/testFixtures/kotlin/dev/notypie

## Purpose
Root fixture package. No files at this level; each subpackage builds the types of the matching main package.
The `TEST_USER_ID`, `TEST_CHANNEL_ID`, `TEST_TOKEN`, `TEST_APP_ID` (and similar) defaults used throughout come
from `domain/src/testFixtures/kotlin/dev/notypie/domain/Constants.kt`, not from here.

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `dto/` | Request/response shapes for the live `RestClientRequesterTest` (jsonplaceholder API) (see `dto/AGENTS.md`) |
| `impl/` | Slack payload JSON, typed `InteractionPayload`, Events API request graphs, infra event creators, Google Calendar request and error builders (see `impl/AGENTS.md`) |
| `repository/` | Snapshot-isolation transaction fake and a transactional proxy for `*Impl` unit specs (see `repository/AGENTS.md`) |
| `schema/` | JPA schema-row and record creators for the meeting, calendar-mirror and CVE lanes (see `schema/AGENTS.md`) |

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
