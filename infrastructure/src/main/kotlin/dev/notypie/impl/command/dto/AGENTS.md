<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-13 -->

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
| `SlackUserProfileDto.kt` | `SlackUserProfileDto(ok: Boolean, profile: Profile)`; `Profile` maps `title`, `phone`, `skype`, `real_name`, `real_name_normalized`, `display_name`, `display_name_normalized`, `fields: Map<String, Field>`, `status_*`, `avatar_hash`, `email` (`String? = null`), `first_name`, `last_name`, `image_24` … `image_512`, `status_text_canonical`; `Field(value, alt)` |

## For AI Agents

### Working In This Directory
- **Every `Profile` property except `statusEmojiDisplayInfo` and `email` is non-null with no default.** Slack omits
  fields depending on scope, account and workspace settings (`first_name` / `last_name` and `fields` can be absent),
  and a missing non-null constructor parameter fails the whole decode: `SlackUserProfileResolver` then degrades to the
  bare `<@id>` mention. `email` is `String? = null` since 2026-10-13 because Slack leaves it out without
  `users:read.email` or when the workspace's Email Display setting hides it; as `String` it failed every such decode
  with `KotlinInvalidNullException` (measured with the shared `jsonMapper` and through the `restRequester` bean). When
  another field turns up missing, make it nullable here rather than widening the scope request.
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
decodes a `users.profile.get` body without `email` with the shared `jsonMapper`, `templates/SlackUserEmailResolverTest`
decodes `users.info` bodies (with unmodelled keys, without `email`, blank, `ok = false`), and `:application`'s
`ApplicationContextSmokeTest` decodes an email-less profile body through Boot's mapper on the `restRequester` bean.

### Common Patterns
- Snake-case wire names mapped explicitly with `@field:JsonProperty`; camelCase Kotlin properties.

## Dependencies

### Internal
- Consumer: `templates/ModalTemplateBuilder`

### External
Jackson annotations (`com.fasterxml.jackson.annotation`).

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
