<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-26 -->

# infrastructure/impl/command/dto

## Purpose
Response DTOs for two Slack Web API reads: `users.profile.get` (`SlackUserProfileDto`), used by
`templates/ModalTemplateBuilder.approvalTemplate` to render the requester's display name and avatar in an approval
message, and `users.info` (`SlackUserInfoDto`), read by `templates/SlackUserEmailResolver` for the e-mail that
`/calendar connect` compares with the Google account.

## Key Files
| File | Description |
|------|-------------|
| `SlackUserInfoDto.kt` | `SlackUserInfoDto(ok, error: String? = null, user: SlackUserInfo? = null)`, `SlackUserInfo(id, profile: SlackUserInfoProfile)`, `SlackUserInfoProfile(email: String? = null)`: only what the e-mail lookup reads. Every other key of the `users.info` body is ignored (Jackson 3's `FAIL_ON_UNKNOWN_PROPERTIES` defaults to `false`, in Boot's mapper and the shared `jsonMapper` alike), and an `ok = false` body (`{"ok": false, "error": "…"}`, no `user`) decodes, so its `error` code can be logged |
| `SlackUserProfileDto.kt` | `SlackUserProfileDto(ok: Boolean, error: String? = null, profile: Profile? = null)`; `Profile(realName, displayName, imageSize24)` (`real_name`, `display_name`, `image_24`, each `String? = null`): only what `SlackUserProfileResolver` reads. Every other key is ignored, and an `ok = false` body (`{"ok": false, "error": "user_not_found"}`, HTTP 200, no `profile`) decodes, so its `error` code can be logged |

## For AI Agents

### Working In This Directory
- **`Profile` models only the three keys the resolver reads, all nullable.** A required field the resolver never reads
  made Slack's `ok = false` answer (no `profile`) fail the whole decode, so the resolver logged ERROR with a stack and
  lost the error code (fixed 2026-10-26). When the resolver starts reading another key, add it here nullable with a
  default rather than widening the scope request.
- Do not read the e-mail from this DTO: Slack documents `users:read.email` for `users.info` / `users.list`, not for
  `users.profile.get`, so the identity check reads `SlackUserInfoDto` instead.
- The call sites are `RestRequester.safeGet(uri = "users.profile.get?user={user}" | "users.info?user={user}",
  authorizationHeader = slackApiToken, responseType = …, uriVariables = mapOf("user" to userId))` in
  `templates/SlackUserProfileResolver.kt`; in production both DTOs are deserialized by Spring's `RestClient`
  converters (Boot's mapper), not by `common/jsonMapper`. The specs decode with `jsonMapper`; both are Jackson 3 with the
  Kotlin module and ignore unknown keys.
- Annotations are `com.fasterxml.jackson.annotation.JsonProperty` on `@field:` — the same convention as
  `impl/command/slack`.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.templates.*'
```
No spec lives in this package. Decoding is covered from the consumers: `templates/SlackUserProfileResolverTest`
decodes a `users.profile.get` `ok = false` body, a minimal `ok = true` body and a full body with the shared `jsonMapper`, `templates/SlackUserEmailResolverTest`
decodes `users.info` bodies (with unmodelled keys, without `email`, blank, `ok = false`), and `:application`'s
`ApplicationContextSmokeTest` decodes a profile body through Boot's mapper on the `restRequester` bean.

### Common Patterns
- Snake-case wire names mapped explicitly with `@field:JsonProperty`; camelCase Kotlin properties.

## Dependencies

### Internal
- Consumer: `templates/ModalTemplateBuilder`

### External
Jackson annotations (`com.fasterxml.jackson.annotation`).

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
