<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# infrastructure/src/testFixtures/kotlin/dev/notypie/dto

## Purpose
Request / response shapes for the live jsonplaceholder API used by `impl/command/RestClientRequesterTest`.
They mirror `https://jsonplaceholder.typicode.com/posts` and exist only so that spec can type its calls.

## Key Files
| File | Description |
|------|-------------|
| `PostDomainCreateRequestBody.kt` | `data class PostDomainCreateRequestBody(title, body, userId: Int)` with `companion.getDefault()` = `("foo", "bar", 1)` |
| `PostDomainUpdateRequestBody.kt` | `data class PostDomainUpdateRequestBody(title, body, userId: Int, id: Int)` with `companion.getDefault()` = `("foo", "bar", 1, 1)` |
| `PostDomainResponse.kt` | `data class PostDomainResponse(userId: Int, id: Int, title, body)` — the `/posts/{id}` reply; also read as `Array<PostDomainResponse>` for the list endpoint |

## For AI Agents

### Working In This Directory
- These are wire shapes for a third-party demo API, not project DTOs; nothing in `:application` uses them and
  they must not gain project-specific fields.
- `getDefault()` values are the ones documented in the jsonplaceholder guide; the requester spec asserts the
  echo, so changing them changes expected responses.
- If `RestClientRequesterTest` ever moves to a loopback `HttpServer` stub, these three classes move with it
  or are deleted with it.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.impl.command.RestClientRequesterTest'
```
The only consumer; requires outbound network access.

### Common Patterns
- Plain data classes with a `companion object { fun getDefault() }` — the older fixture style; newer
  fixtures use top-level `create*` builders with default parameters.

## Dependencies

### Internal
None.

### External
None.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
