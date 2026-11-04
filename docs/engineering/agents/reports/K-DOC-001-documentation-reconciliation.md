# K-DOC-001 — Documentation Reconciliation — Report

## Status

Complete

## Objective

Fix the documentation inconsistencies found during the initial exploration of `docs/OSK.md` and `README.md`, under the Knowledge Curator role (authorized reconciliation mode). Scope: `README.md`, `CLAUDE.md`, and `AGENTS.md`.

## Authority

| Claim type | Authority |
| --- | --- |
| Current runtime behavior | Repository code under `katabasis-core`, `katabasis-ui`, `katabasis-cli` |
| Current architecture summary | [docs/knowledge/architecture/katabasis-core.md](../../../knowledge/architecture/katabasis-core.md) |
| Committed direction | [docs/roadmap/ROADMAP.md](../../../roadmap/ROADMAP.md) |

`docs/PROJECT.md` and the canonical knowledge page were audited and found consistent with the code; neither was modified. The roadmap was not modified.

## Findings and Classification

| # | Artifact | Classification | Finding | Evidence |
| --- | --- | --- | --- | --- |
| 1 | `README.md` | Stale | "Planned Features" listed cancel, pause/resume, and segmented downloads as future work although all three are implemented. | `DownloadManager.cancel/pause/resume`; `SegmentedHttpDownloadStrategy`; `HttpDownloadOrchestrator` |
| 2 | `README.md` | Stale | "Design Philosophy" presented delivered capabilities (cancellation, pause/resume, segmented) as future evolution. | Same as #1 |
| 3 | `README.md` | Missing | `DownloadStrategy` documented only `HttpDownloadStrategy`; "Current Features" omitted segmented downloads and pause/resume/cancel. | `DefaultDownloadStrategyResolver` routes HTTP/HTTPS to `HttpDownloadOrchestrator` |
| 4 | `README.md` | Conflicting | Example called `manager.submit(...)`; the public interface exposes `download(DownloadRequest)`. | `DownloadManager.java` lines 18–52 |
| 5 | `README.md` | Missing | `DownloadTask` was described as public live state; it is internal and callers receive `DownloadTaskSnapshot`. | `internal/model/DownloadTask.java` is `public final class`; snapshot record in `api/model` |
| 6 | `CLAUDE.md`, `AGENTS.md` | Conflicting | `DownloadStrategy` row cited `S3DownloadStrategy` as an implementation. | `S3DownloadStrategy.java` is an empty class: `public class S3DownloadStrategy { }` |
| 7 | `CLAUDE.md`, `AGENTS.md` | Conflicting | `RetryPolicy` described as "Pluggable retry" with three policies. | `RetryPolicy` is an empty interface; the three classes are empty and do not implement it; no production code references them |
| 8 | `CLAUDE.md`, `AGENTS.md` | Stale | "Domain types" claimed `DownloadTask` and `WebHttpResponse` are records. | `DownloadTask` is a mutable internal class; no `WebHttpResponse` type exists in the repository |
| 9 | `CLAUDE.md`, `AGENTS.md` | Conflicting | "Planned Features" listed segmented (parallel-chunk) downloads. | Implemented in `SegmentedHttpDownloadStrategy` |
| 10 | `CLAUDE.md`, `AGENTS.md` | Conflicting | Module table described `katabasis-cli` as a command-line interface and the dependency arrow implied a core dependency. | `katabasis-cli/src/main/java/module-info.java` does not require `jsanca.katabasis.core`; `codex.Main` is a stub |
| 11 | `AGENTS.md`, `CLAUDE.md` | Duplicated | Both files are near-identical copies and had already drifted (AGENTS.md carries an OSK skills block absent from CLAUDE.md). | `diff AGENTS.md CLAUDE.md` |

### Correction to the initial exploration

The initial exploration also claimed that `docs/PROJECT.md` understated S3 and retry support. That claim was **wrong** and is retracted: `PROJECT.md` line 32 ("Retry, S3, FTP, CLI, and persistence are not implemented") matches the code, and the canonical knowledge page documents both as stubs. No change was needed.

## Changes

- `README.md` — corrected the planned/current feature split; documented `SegmentedHttpDownloadStrategy` and `HttpDownloadOrchestrator`; corrected the example to `manager.download(...)`; clarified `DownloadTask` as internal and added the pause/resume/cancel events.
- `CLAUDE.md` — corrected the strategy, task, and retry rows; replaced the non-existent `WebHttpResponse` reference; clarified the CLI module status; aligned planned features with the roadmap phases.
- `AGENTS.md` — same corrections as `CLAUDE.md`.

No application code, build files, roadmap, ADRs, knowledge pages, or historical records were changed.

## Evidence

- `katabasis-core/src/main/java/module-info.java` — exports only `api.event`, `api.exception`, `api.manager`, `api.model`.
- `DownloadManager.java` — `download`, `cancel`, `pause`, `resume`, `addListener`, `removeListener`; no `submit`.
- `internal/retry/RetryPolicy.java` — `public interface RetryPolicy { }`.
- `internal/s3/S3DownloadStrategy.java` — empty class.
- `grep -rn "RetryPolicy"` over `katabasis-core/src/main/java` — declarations only, no invocation.
- `grep -rn "WebHttpResponse"` over the repository — no matches.
- `DownloadManagerApplication.java` — `DownloadRow` is a private inner record and `DownloadRowCell` a private inner class, consistent with the knowledge page.
- `DownloadConfig.defaultConfig()` — 4 concurrent downloads, 4 segments, 5 MB segment threshold, consistent with the knowledge page.

## Validation

```
python3 relative-link check over README.md, AGENTS.md, CLAUDE.md, docs/**.md
checked 21 markdown files
no broken relative links

grep -rn "WebHttpResponse|manager.submit|future pause/resume|Segmented (parallel-chunk) downloads" README.md AGENTS.md CLAUDE.md docs/
no stale references remain

git diff --stat
 AGENTS.md | 22 ++++++++++++----------
 CLAUDE.md | 22 ++++++++++++----------
 README.md | 54 ++++++++++++++++++++++++++++++++----------------------
 3 files changed, 56 insertions(+), 42 deletions(-)
```

No build or test run: this was a documentation-only change with zero code modification.

## Limitations

- Finding #11 (duplicated `AGENTS.md` / `CLAUDE.md`) was not restructured. Both files were synchronized factually to prevent further divergence, but the duplication remains a future maintenance risk; each tool reads its own file, so a shared source would require a generation step.
- `README.md` was aligned with the current state but is still a high-level introduction, not a full API reference; the canonical architecture page remains the detailed authority.
- The audit covered documentation reachable from `README.md`, `AGENTS.md`, `CLAUDE.md`, and `docs/`. Files outside that scope were not audited.

## Open Follow-Up

- Decide whether `CLAUDE.md` and `AGENTS.md` should be generated from a single shared source to avoid future drift.
- Consider adding a short "current status" pointer in `README.md` to the canonical knowledge page instead of restating component detail.

> Resolution note (2026-11-03): the duplication follow-up above was resolved by [K-DOC-002](../tasks/K-DOC-002.md), which extracted shared guidance into [AGENTS-CORE.md](../../../../AGENTS-CORE.md).

## Related Records

- Task: [K-DOC-001.md](../tasks/K-DOC-001.md)
- Canonical architecture page: [katabasis-core.md](../../../knowledge/architecture/katabasis-core.md)
- Roadmap: [ROADMAP.md](../../../roadmap/ROADMAP.md)
