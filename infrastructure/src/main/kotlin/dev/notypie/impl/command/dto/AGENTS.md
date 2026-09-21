<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# infrastructure/impl/command/dto

## Purpose
Response DTO for Slack's `users.profile.get`, used by `templates/ModalTemplateBuilder.approvalTemplate` to
render the requester's display name and avatar in an approval message.

## Key Files
| File | Description |
|------|-------------|
| `SlackUserProfileDto.kt` | `SlackUserProfileDto(ok: Boolean, profile: Profile)`; `Profile` maps `title`, `phone`, `skype`, `real_name`, `real_name_normalized`, `display_name`, `display_name_normalized`, `fields: Map<String, Field>`, `status_*`, `avatar_hash`, `email`, `first_name`, `last_name`, `image_24` … `image_512`, `status_text_canonical`; `Field(value, alt)` |

## For AI Agents

### Working In This Directory
- **Every `Profile` property except `statusEmojiDisplayInfo` is non-null with no default.** Slack omits
  fields depending on scope and account (`email` needs `users:read.email`; `first_name` / `last_name`
  and `fields` can be absent), and a missing non-null constructor parameter fails deserialization inside
  `approvalTemplate` — which surfaces as a failed approval message. When you see that, make the missing
  field nullable here rather than widening the scope request.
- The call site is `RestRequester.get(uri = "users.profile.get?user=…", authorizationHeader =
  slackApiToken, responseType = SlackUserProfileDto::class.java)`; this DTO is deserialized by Spring's
  `RestClient` converters, not by `common/jsonMapper`.
- Annotations are `com.fasterxml.jackson.annotation.JsonProperty` on `@field:` — the same convention as
  `impl/command/slack`.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.templates.ModalTemplateBuilderTest'
```
The only coverage is indirect: `ModalTemplateBuilderTest` stubs `RestRequester` to return a
`SlackUserProfileDto`. There is no deserialization spec for a real `users.profile.get` body.

### Common Patterns
- Snake-case wire names mapped explicitly with `@field:JsonProperty`; camelCase Kotlin properties.

## Dependencies

### Internal
- Consumer: `templates/ModalTemplateBuilder`

### External
Jackson annotations (`com.fasterxml.jackson.annotation`).

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
