<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-09-22 | Updated: 2026-09-22 -->

# infrastructure/src/testFixtures/kotlin/dev/notypie/impl/command/dto

## Purpose
Builders for the Slack Web API response DTOs in `src/main/kotlin/dev/notypie/impl/command/dto`. `Profile`
has 25 required constructor fields, so specs get one from `createProfile` and override only what they assert on.

## Key Files
| File | Description |
|------|-------------|
| `SlackUserProfileCreator.kt` | `createProfile(displayName = "testuser", realName = "Test User", imageSize24 = "https://example.com/img24.png"): Profile` |

## For AI Agents

### Working In This Directory
- Consumers: `templates/ModalTemplateBuilderTest` (approval block) and `templates/SlackUserProfileResolverTest`
  (blank display name → real name fallback, blank image → no thumbnail).
- Wrap in `SlackUserProfileDto(ok = true, profile = createProfile(...))`; `ok = false` is how the resolver's
  "Slack said no" branch is exercised.

## Dependencies

### Internal
- `dev.notypie.impl.command.dto.Profile`
