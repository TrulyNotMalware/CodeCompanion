<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-09-22 | Updated: 2026-10-13 -->

# infrastructure/src/testFixtures/kotlin/dev/notypie/impl/command/dto

## Purpose
Builders for the Slack Web API response DTOs in `src/main/kotlin/dev/notypie/impl/command/dto`. `Profile`
has 21 required constructor fields, so specs get one from `createProfile` and override only what they assert on.

## Key Files
| File | Description |
|------|-------------|
| `SlackUserProfileCreator.kt` | `createProfile(displayName = "testuser", realName = "Test User", imageSize24 = "https://example.com/img24.png"): Profile`; `createUserProfileResponseJson(displayName = "testuser", email = "test@example.com"): String`, a `users.profile.get` body as Slack sends it (`email = null` leaves the key out, as Slack does without `users:read.email`) (snake_case keys plus keys the DTO does not model, such as `image_original`, `is_custom_image`, `huddle_state`); `createUserInfoResponseJson(userId = TEST_USER_ID, email = "test@example.com")`, a `users.info` body shaped after Slack's documented example with many keys `SlackUserInfoDto` does not model (`email = null` leaves the profile key out); `createUserInfoErrorJson(error = "user_not_found")` = `{"ok": false, "error": …}`. All three interpolate without escaping |

## For AI Agents

### Working In This Directory
- Consumers: `templates/ModalTemplateBuilderTest` (approval block), `templates/SlackUserProfileResolverTest`
  (blank display name → real name fallback, blank image → no thumbnail, a decoded body without `email`) and
  `templates/SlackUserEmailResolverTest` (the `users.info` builders); `:application`'s `ApplicationContextSmokeTest`
  serves `createUserProfileResponseJson(email = null)` to the `restRequester` bean, so the body is decoded by Boot's
  JSON mapper as in production.
- Wrap in `SlackUserProfileDto(ok = true, profile = createProfile(...))`; `ok = false` is how the resolver's
  "Slack said no" branch is exercised.

## Dependencies

### Internal
- `dev.notypie.impl.command.dto.Profile`
