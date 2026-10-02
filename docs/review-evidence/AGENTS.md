<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-10-02 | Updated: 2026-10-02 -->

# docs/review-evidence

## Purpose
Primary sources that the root `review.md` cites: the review-lane reports, the fix-review reports, the Codex review
transcripts and the build logs behind its verdicts. They were produced under the git-ignored `.omc/artifacts/` and
copied here so the evidence travels with the branch. This is a frozen record: `review.md` is the document to read and
edit; these files back its claims.

Paths were normalised on copy (absolute home and scratch paths became repo-relative, `~` or `<scratchpad>`).
Worker reports and logs are otherwise verbatim. Codex transcripts are trimmed: the duplicate `Final prompt` section,
the repeated user prompt and MCP transport noise are dropped, and each `exec` block keeps its command, exit status
and the first 12 output lines; the reviewer's narration and final report are unchanged. Sources of chapters 12–13
(`review-2026-09-24/`, `review-2026-09-28/` and two Codex runs) were not preserved, which `review.md` marks
`(원문 미보존)`.

## Key Files
| File | Description |
|------|-------------|
| `codex-2026-09-21-final-independent.md` | First independent Codex review of `main` |
| `codex-2026-09-22-final-independent.md` | Codex independent review cited in `review.md` chapter 10 |
| `lane1-outbox.md` … `lane7-mcp-command.md` | Chapter 14 (third cross-review) lane reports: outbox, dispatch, meeting, security, ops, CVE/standup, MCP/command |
| `build.log` | Chapter 14 baseline build of `feature/review-critical-fixes` |
| `codex-2026-09-28-third-pass-interrupted.md` | Chapter 14 Codex pass, stopped by a usage limit |
| `codex-2026-09-30-third-pass.md` | Chapter 14 Codex pass, completed |
| `fixrev1-outbox-dispatch-deploy.md`, `fixrev2-security-meeting-standup.md`, `fixrev3-cve-templates.md` | Chapter 15 reviews of the first fix round |
| `codex-2026-10-01-fix-review.md` | Chapter 15 Codex review of the first fix round (called `fixrev-codex.md` during the work) |
| `codex-2026-10-02-second-fix-round-partial.md` | Chapter 15.6 Codex review of the second fix round, stopped by a usage limit before its final table |
| `build-wave1*.log`, `build-wave2*.log`, `build-round2.log`, `build-final-rerun.log`, `flaky-check.log` | Chapter 15 builds per integration step; `build-final-rerun.log` is the final `--rerun-tasks` run |

## For AI Agents

### Working In This Directory
- Do not edit these files to match later code; they record what was observed at the time. Correct `review.md`
  instead, and add a new file here only when `review.md` cites it.
- Normalise absolute paths before adding a file; the repository is public.

### External
None — plain Markdown and text logs.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
