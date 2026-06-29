# Real-workspace test setup

How to run CodeCompanion against a real Slack app and exercise every feature. Uses the
`real` Spring profile (`application-real.yaml`): **POLLING relay + APPLICATION_EVENT** — no Kafka,
no Debezium. The app polls the `outbox_message` table every cycle and calls Slack directly.

## 1. Prerequisites
- **DB**: local orbstack MariaDB with the `code_companion` database (already up from the E2E run).
  - Bring up if needed: `docker compose -f ~/infra/mariadb/docker-compose.yml up -d`
  - Create DB if missing: `docker exec local-mariadb-primary mariadb -uroot -proot_password -e "CREATE DATABASE IF NOT EXISTS code_companion"`
  - Or point elsewhere via `DATABASE_URL` / `DATABASE_USER_NAME` / `DATABASE_USER_PWD`.
- **Tunnel** so Slack can reach `localhost:9000`: `ngrok http 9000` (or `cloudflared tunnel --url http://localhost:9000`). Note the `https://<id>.ngrok-free.app` URL — call it `$BASE`.

## 2. Slack app config (api.slack.com/apps → your app)
- **OAuth & Permissions → Bot Token Scopes**: `commands`, `chat:write`, `im:write`, `users:read`,
  `app_mentions:read`, `channels:read`, `groups:read`. Reinstall the app after changing scopes; copy
  the **Bot User OAuth Token** (`xoxb-...`).
- **Basic Information → Signing Secret**: copy it (used for request-signature verification).
- **Slash Commands** (create two):
  - `/meetup`  → Request URL `"$BASE"/api/slash/meet`
  - `/standup` → Request URL `"$BASE"/api/slash/standup`
- **Interactivity & Shortcuts**: ON → Request URL `"$BASE"/api/slack/interaction`
- **Event Subscriptions**: ON → Request URL `"$BASE"/api/slack/events` (Slack sends a
  `url_verification` challenge — the app must be running first). Subscribe to bot event `app_mention`.

## 3. Run
```bash
SLACK_API_TOKEN=xoxb-... SLACK_SIGNING_SECRET=... \
  ./gradlew :application:bootRun --args='--spring.profiles.active=real'
```
Health check: `curl -s localhost:9000/api/actuator/health`. Leave `SLACK_SIGNING_SECRET` unset to
disable signature verification (local only).

> Tip: spring-boot-devtools' restart classloader can break some startups — if boot fails oddly, add
> `--spring.devtools.restart.enabled=false`.

## 4. Feature test scenarios
Scheduler phases run on a 60s tick (standup open/dispatch/nudge/cutoff, meeting reminders, daily agenda).

1. **/meetup** — `/meetup` → fill form → approve/decline (decline opens reason modal) → `/meetup list`
   shows your meetings; host rows now show **Reschedule** + **Cancel**.
2. **Reschedule** — on `/meetup list`, click **Reschedule** → pick new date/time → participants get a
   re-notification and the meeting's reminders are re-armed for the new time.
3. **Meeting reminders** — create a meeting starting ~**16 min** out → attending participants get a
   15-min and a 5-min DM (`meeting.reminder.offsets-minutes`, default `15,5`).
4. **/standup setup** — `/standup setup` → fill modal (questions, members, summary channel, weekdays,
   trigger time, timezone, cutoff) → routine created. At the trigger time members get the prompt DM
   with a **Fill in standup** button → submit answers; at cutoff a summary posts to the summary channel.
5. **Standup nudge** — within `standup.nudge.offset-minutes` (default 30) of cutoff, members who got the
   prompt but haven't answered get a one-time reminder DM.
6. **Daily agenda** — set `meeting.agenda.send-at` to a minute or two ahead (e.g. via
   `--meeting.agenda.send-at=HH:mm`), have at least one meeting today → each attending user gets a
   morning agenda DM (once per day).

## 5. Useful config overrides (append as `--key=value` to bootRun args)
- `meeting.reminder.offsets-minutes=16,5` — shorten for quick testing
- `standup.nudge.offset-minutes=30` (0 disables)
- `meeting.agenda.send-at=09:00`, `meeting.agenda.timezone=Asia/Seoul`, `meeting.agenda.enabled=true`

## 6. Quick DB peeks
```bash
docker exec local-mariadb-primary mariadb -uroot -proot_password code_companion -e \
  "SELECT id,name,start_at FROM meetings; SELECT * FROM meeting_reminder; \
   SELECT id,name,is_active FROM standup_routine; SELECT event_id,command_detail_type,status FROM outbox_message ORDER BY created_at DESC LIMIT 10;"
```

## Notes
- Routines/meetings can also be seeded directly via SQL for fast scenario setup.
- DMs require the recipient to share the workspace and the bot to have `im:write`.
- This profile is for local real-workspace testing; production uses CDC + Kafka (the `dev`/`prod`
  profiles + the Debezium connector at `~/infra/debezium/connectors/codecompanion-outbox.json`).
