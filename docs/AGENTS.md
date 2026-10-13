<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-13 -->

# docs

## Purpose
Human-facing project documentation that is not tied to a single directory: the project wiki and the Slack app
manifest. Per-directory guidance stays in the `AGENTS.md` tree, and user-facing setup lives in the root `README.md`.

## Key Files
| File | Description |
|------|-------------|
| `slack-app-manifest.yaml` | Slack app manifest for "Create from manifest": the eight slash commands mapped to `/api/slash/*` (`/ask` since 2026-10-12) (`/calendar connect\|disconnect\|status` has its own command and `/api/slash/calendar` since 2026-10-08), bot scopes (`users:read` + `users:read.email` since 2026-10-13: `/calendar connect` reads the user's e-mail with `users.info` and compares it with the Google account; Slack grants the second only together with the first), `app_mention` event, interactivity URL, all under a `https://<your-host>` placeholder. Its header explains the `local` Socket Mode dev app and that the Google OAuth redirect is registered in Google Cloud, not here. Keep it in step with `SlashCommandController`, `AppConfig.Socket` and the Slack Web API methods the dispatcher calls |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `wiki/` | Design philosophy, layering rules, outbox/event model, coding style, decision log and history (see `wiki/AGENTS.md`) |

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
