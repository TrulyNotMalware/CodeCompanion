<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-30 -->

# docs/wiki

## Purpose
The project wiki: why the system is shaped the way it is, which rules are enforced, and which decisions were made
with what rationale. Written in Korean for people; the `AGENTS.md` tree is the English, per-directory how-to for
agents. The two link to each other and must not duplicate each other — the wiki explains, `AGENTS.md` instructs.

## Key Files
| File | Description |
|------|-------------|
| `index.md` | Operating rules of the wiki plus the page catalog (one line per page). Update it whenever a page is added, renamed, or removed |
| `log.md` | Append-only chronicle (`## [YYYY-MM-DD] create|update|lint | …`). Never edit past entries |
| `architecture-overview.md` | Module responsibilities, dependency direction, the path a request takes |
| `ddd-layering.md` | Domain purity and transport neutrality: what is forbidden, how the guard test enforces it, deliberate leaks |
| `command-pipeline.md` | Slack payload → `InboundCommand` → `Command`/`CommandIntent` → `OutboundMessage`; how to add a command |
| `events-and-outbox.md` | Transactional outbox, relay modes, CAS patterns, idempotency, honest delivery guarantees |
| `error-handling-and-validation.md` | `ErrorCode` / `exceptionDetails {}` / `validate {}`, exception ownership per layer, known gaps |
| `coding-style.md` | Kotlin conventions, commit message format, comment/null/file rules (promoted from the git-ignored local style guide) |
| `testing-guide.md` | Kotest/MockK style, testFixtures factories, guard and regression specs, module commands |
| `dev-environment.md` | Toolchain, profile matrix, local run recipes, migration and secret conventions, CI/CD summary |
| `decisions.md` | Numbered decision records with status (`유지` / `열림` / `폐기`) and evidence |
| `history.md` | Milestones from 2024-06 and the design turning points, with verified commit hashes |

## For AI Agents

### Working In This Directory
- Follow the rules in `index.md`: Korean prose, identifiers and paths verbatim from code, relative markdown links
  (no `[[wikilinks]]`), no YAML frontmatter, header `_type: … · updated: YYYY-MM-DD_` + one-sentence summary quote.
- **Every technical claim needs evidence.** Verify against the current source before writing; list the source paths
  under `## 근거`; mark anything unverified `(미확인)`. Several pages record doc/code mismatches on purpose — do not
  "fix" the wiki to match a stale `AGENTS.md`; fix the stale file.
- Record deltas only (project-specific decisions, constraints, traps, reasons). Framework tutorials, restated
  official docs, and narration of what code does line by line do not belong here.
- When a decision changes, edit its entry in `decisions.md` in place (status + a dated note) rather than deleting it,
  bump the page's `updated` date, and append a line to `log.md`.
- No secrets, tokens, or hostnames beyond what `README.md` already shows. `dev-environment.md` names config keys,
  never values.
- Changes here are documentation: lint, test and deploy exclude `**/*.md`, so nothing under `docs/` builds or
  deploys. `security_check.yaml` still runs its gitleaks job on every push/PR to `main` (secrets can hide in
  Markdown); its CodeQL and dependency jobs skip docs-only changes.

### Testing Requirements
- Walk `docs/wiki/*.md`, resolve every relative link target against the wiki directory, and confirm each page
  except `index.md` / `log.md` keeps the header shape (`# title`, `_type … · updated …_`, `> summary`) and the
  `## 근거` / `## 관련 페이지` sections. A short Python loop is enough; there is no committed script.
- Before publishing, scan the directory for leaked secrets:
  `docker run --rm -v "$PWD:/repo" -w /repo zricethezav/gitleaks:latest dir /repo/docs/wiki --redact`.

### Common Patterns
- Page skeleton: title → `_type · updated_` → summary quote → topical sections → `## 근거` → `## 관련 페이지`.
- Tables for matrices (profiles, CAS patterns, decisions); prose for reasoning; code blocks only for real commands.
- Cross-links both ways: a wiki page links the `AGENTS.md` files it draws on, and those files may point back here
  for the "why".

## Dependencies

### Internal
- `README.md` and the root `AGENTS.md` link to `index.md`; the module and package `AGENTS.md` files are the primary
  evidence sources.
- The git-ignored planning documents (`Refactor.md`, `Handoff.md`, `CveBotPlan.md`, `RealTestSetup.md`,
  `STYLE_GUIDE.local.md`) exist only in the maintainer's working tree; `history.md` and `decisions.md` carry their
  substance into the repository.

### External
None — plain Markdown rendered by GitHub.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
