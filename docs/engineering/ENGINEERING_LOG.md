# Engineering Log

Navigable index of durable engineering work.

This log is a materialized project index, not the authoritative source for task or implementation details. Linked tasks, reports, reviews, checkpoints, and knowledge artifacts remain authoritative.

Only durable artifacts are indexed. Interactions that left no durable artifact are not reconstructed or inferred.

`—` means no artifact exists, no artifact was found, or no relationship is intentionally inferred.

| Task | Description | Status | Depends On | Task File | Report | Review | Fix / Checkpoint | Knowledge |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| KATABASIS-ROADMAP-001 | Consolidación y planificación multiprotocolo | DONE | — | [task](agents/tasks/KATABASIS-ROADMAP-001.md) | [report](agents/reports/KATABASIS-ROADMAP-001-roadmap-planning.md) | — | — | [architecture](../knowledge/architecture/katabasis-core.md) |
| K-0.1-T01 | Fix pause/resume event storm in segmented downloads | DONE | KATABASIS-ROADMAP-001 | [task](agents/tasks/K-0.1-T01.md) | [report](agents/reports/K-0.1-T01-pause-resume-fix.md) | — | — | — |
| K-0.1-T02 | DownloadManager end-to-end tests (complete, fail, cancel, pause/resume) | DONE | KATABASIS-ROADMAP-001 | [task](agents/tasks/K-0.1-T02.md) | [report](agents/reports/K-0.1-T02-download-manager-tests.md) | — | — | — |
| K-DOC-001 | Reconcile project documentation with current code state | DONE | — | [task](agents/tasks/K-DOC-001.md) | [report](agents/reports/K-DOC-001-documentation-reconciliation.md) | — | — | — |
| K-DOC-002 | Extract canonical agent guidance into AGENTS-CORE.md | DONE | K-DOC-001 | [task](agents/tasks/K-DOC-002.md) | [report](agents/reports/K-DOC-002-agents-core-extraction.md) | — | — | — |
| K-DOC-003 | Add module, class, and sequence diagrams for the core download flow | DONE | K-DOC-001 | [task](agents/tasks/K-DOC-003.md) | [report](agents/reports/K-DOC-003-diagrams.md) | — | — | [architecture](../knowledge/architecture/katabasis-core.md) |
| K-DOC-004 | Document undocumented core classes and public methods with Javadoc | DONE | — | [task](agents/tasks/K-DOC-004.md) | [report](agents/reports/K-DOC-004-javadoc-core.md) | — | — | — |
| K-0.1-T03A | JSON dependency & compatibility assessment — spike for DownloadStateStore persistence | DONE | KATABASIS-ROADMAP-001 | [task](agents/tasks/K-0.1-T03A.md) | [report](agents/reports/K-0.1-T03A-json-assessment.md) | — | — | — |
| K-0.1-T03B | Versioned JSON codec & storage abstraction — katabasis-storage-json module | DONE | K-0.1-T03A | [task](agents/tasks/K-0.1-T03B.md) | [report](agents/reports/K-0.1-T03B-storage-json.md) | — | — | [ADR-009](../adr/ADR-009-storage-json-module.md) |
| K-0.1-T03C | FileDownloadStateStore, DownloadSnapshot, CheckpointCoordinator | DONE | K-0.1-T03B | [task](agents/tasks/K-0.1-T03C.md) | [report](agents/reports/K-0.1-T03C-state-store.md) | — | — | — |
| K-0.1-T03D | Retry policy & execution engine — HTTP strategy integration | DONE | KATABASIS-ROADMAP-001 | [task](agents/tasks/K-0.1-T03D.md) | [report](agents/reports/K-0.1-T03D-retry.md) | [review](agents/reports/K-REVIEW-001-code-review.md) | — | — |
| K-ANAL-001 | Simplification analysis — retry system and HTTP strategies | DONE | K-0.1-T03D | — | [report](agents/reports/K-ANAL-001-simplify.md) | — | — | — |
| K-REVIEW-001 | Code review: K-0.1-T03D retry system (Opus, high effort) | DONE | K-0.1-T03D | — | [report](agents/reports/K-REVIEW-001-code-review.md) | — | — | — |
| K-RETRY-HARDEN-001 | Fix retry bugs (overflow, totalDownloaded, interrupt) and apply simplifications | DONE | K-0.1-T03D, K-REVIEW-001, K-ANAL-001 | [task](agents/tasks/K-RETRY-HARDEN-001.md) | [report](agents/reports/K-RETRY-HARDEN-001-reconciliation.md) | — | — | — |
| K-STORAGE-REVIEW-001 | Storage concurrency & durability adversarial review | DONE | — | — | [report](agents/reports/K-STORAGE-REVIEW-001-storage-review.md) | — | — | — |
| K-STOR-HARDEN-001 | Storage hardening: fix confirmed defects from K-STORAGE-REVIEW-001 | DONE | K-STORAGE-REVIEW-001 | [task](agents/tasks/K-STOR-HARDEN-001.md) | [report](agents/reports/K-STOR-HARDEN-001-storage-hardening.md) | — | — | — |
| K-0.1-T06 | Add timeout configuration and handling to HTTP strategies | DONE | K-0.1-T04 | [task](agents/tasks/K-0.1-T06.md) | [report](agents/reports/K-0.1-T06-http-timeout.md) | — | — | — |
| K-0.1-T07 | Segmented HTTP resource integrity & capability negotiation | DONE | K-0.1-T06, KATABASIS-ROADMAP-001 | [task](agents/tasks/K-0.1-T07.md) | [report](agents/reports/K-0.1-T07-integrity.md) | [review](agents/reports/K-0.1-T07-REVIEW-001.md) | — | — |
| K-0.1-T08 | HTTP resource cleanup & lifecycle verification | DONE | K-0.1-T07, K-0.1-T07-REVIEW-001 | [task](agents/tasks/K-0.1-T08.md) | [report](agents/reports/K-0.1-T08-lifecycle.md) | — | — | — |
| K-0.1-T09 | HTTP end-to-end integration verification via DownloadManager public API | DONE | K-0.1-T08 | [task](agents/tasks/K-0.1-T09.md) | [report](agents/reports/K-0.1-T09-e2e.md) | — | — | — |
| K-0.1-T10 | URL list concurrency & scheduling verification | DONE | K-0.1-T09 | [task](agents/tasks/K-0.1-T10.md) | [report](agents/reports/K-0.1-T10-concurrency.md) | — | — | — |
| K-0.2-PRE-001 | CLI & Persistence readiness assessment — component state, ADR-003/004 recommendations, recovery gap analysis, revised 0.2 backlog | DONE | K-0.1-T10 | [task](agents/tasks/K-0.2-PRE-001.md) | [report](agents/reports/K-0.2-PRE-001-readiness-assessment.md) | — | — | — |
| K-0.2-T03 | Add AutoCloseable to DownloadManager; implement graceful shutdown (3-state lifecycle, RW-lock admission gate, executor drain, event suppression) | DONE | K-0.2-PRE-001 | [task](agents/tasks/K-0.2-T03.md) | [report](agents/reports/K-0.2-T03-lifecycle.md) | — | — | [ADR-003](../adr/ADR-003-download-manager-lifecycle.md) |
| K-0.2-T01 | ADR-004 picocli decision; katabasis-cli JPMS wiring to katabasis-core; minimal picocli root command bootstrap | DONE | K-0.2-PRE-001 | [task](agents/tasks/K-0.2-T01.md) | [report](agents/reports/K-0.2-T01-cli-foundation.md) | — | — | [ADR-004](../adr/ADR-004-cli-framework.md) |
| K-0.2-T04 | download subcommand, ProgressRenderer (throttled bar/bytes, interactive/non-interactive), 13 integration tests via in-process TestHttpServer | DONE | K-0.2-T01, K-0.2-T03 | [task](agents/tasks/K-0.2-T04.md) | [report](agents/reports/K-0.2-T04-download-command.md) | [review](agents/reports/K-0.2-CLI-HANG-001-investigation.md) | — | — |
| K-0.2-CLI-HANG-001 | TC-CLI-009 hang RCA: missing `--enable-preview` in katabasis-cli Surefire argLine caused `UnsupportedClassVersionError` on `SegmentedHttpDownloadStrategy`, escaping `catch (Exception)` silently | DONE | K-0.2-T04 | — | [report](agents/reports/K-0.2-CLI-HANG-001-investigation.md) | — | — | — |
| K-0.2-T05 | `download-list` subcommand: `UrlListParser`, `OutputFilenameResolver`, batch coordination via single `DownloadManager`, 14 integration tests | DONE | K-0.2-T04 | [task](agents/tasks/K-0.2-T05.md) | [report](agents/reports/K-0.2-T05-download-list.md) | — | — | — |
| K-0.2-T05-HARDEN-001 | DownloadListCommand hardening: symlink rejection (Fix C), duplicate terminal event guard with putIfAbsent (Fix B2), RuntimeException catch on manager.download() with rejectedCount (Fix B1); barrier-based concurrency test | DONE | K-0.2-T05 | [task](agents/tasks/K-0.2-T05-HARDEN-001.md) | [report](agents/reports/K-0.2-T05-HARDEN-001-report.md) | — | — | — |
| K-0.2-T06 | CLI configuration options: `DownloadConfigMixin` (`--segments`, `--retries`, `--connect-timeout`, `--response-timeout`, `--read-timeout`), `--max-concurrent` for `download-list`, `CliDurationConverter`; `Supplier` → `Function<DownloadConfig,DownloadManager>` factory; 26 new tests | DONE | K-0.2-T04 | [task](agents/tasks/K-0.2-T06.md) | [report](agents/reports/K-0.2-T06-report.md) | — | — | — |
| K-0.2-HARDEN-001 | Surface fatal errors from download VTs: change `catch (Exception)` to `catch (Throwable)` in `DefaultDownloadManager.runDownload` so `Error` subclasses emit `DownloadFailedEvent` instead of hanging | DONE | K-0.2-CLI-HANG-001 | [task](agents/tasks/K-0.2-HARDEN-001.md) | [report](agents/reports/K-0.2-HARDEN-001-report.md) | — | — | — |
| K-0.2-T08 | CLI exit codes (0/1/2/3), `CliExitCode`, `CliErrorCode`, `CliErrorFormatter` (structured stderr, batch summary, credential redaction, escaping); 15 new tests; 3 existing tests updated | DONE | K-0.2-T04 | [task](agents/tasks/K-0.2-T08.md) | [report](agents/reports/K-0.2-T08-report.md) | — | — | — |
| K-0.2-T07 | Interactive lifecycle: p/r/c/q terminal control, cancel confirmation, SIGINT shutdown hook; `shutdownHookCapture` test seam; 14 integration tests | DONE | K-0.2-T04 | [task](agents/tasks/K-0.2-T07.md) | [report](agents/reports/K-0.2-T07-report.md) | — | — | — |
| K-JAVA27-001 | Java 27 migration: `maven.compiler.release` 25→27 across all modules; `SegmentedHttpDownloadStrategy` `FailedException`→`ExecutionException` (preview API change between Java 25 and 27) | DONE | K-0.2-T07 | — | [report](agents/reports/K-0.2-T07-report.md) | — | — | — |
| K-0.2-BUILD-FIX-001 | Full reactor compatibility: `StubDownloadManager.close()` in storage-json test; `DownloadRequest` builder fix in katabasis-ui; reactor 326/326 tests pass | DONE | K-0.2-T03, K-JAVA27-001 | — | [report](agents/reports/K-0.2-BUILD-FIX-001-report.md) | — | — | — |
| K-0.2-T09 | ADR-002: Versioned JSON persistence strategy; implementation assessment; STOR-001–015 disposition; T10/T11 handoff; schema compatibility and durability policy documented | DONE | — | [task](agents/tasks/K-0.2-T09.md) | [report](agents/reports/K-0.2-T09-report.md) | — | — | [ADR-002](../adr/ADR-002-versioned-json-persistence.md) |
| K-0.2-ADR-HARDEN-001 | ADR-002 durability corrections (journaling ≠ content durability; conditional atomic visibility; confirmedBytes semantics; shutdown vs cancellation; Option B T10 recommendation); ADR-004 Java 27 annotation and current-state update | DONE | K-0.2-T09 | — | [report](agents/reports/K-0.2-ADR-HARDEN-001-report.md) | — | — | — |
| K-0.2-T10 | Storage wiring & consistent checkpoints: `CheckpointSource` SPI, `CheckpointSourceProvider`, per-segment `AtomicLong[]` in segmented strategy, shutdown snapshot consumer, `DownloadCheckpointFailedEvent`, `CheckpointCoordinator` full overload, CLI + UI wired, 2 E2E tests (360/360 pass) | DONE | K-0.2-T09, K-0.2-ADR-HARDEN-001 | [task](agents/tasks/K-0.2-T10.md) | [report](agents/reports/K-0.2-T10-report.md) | — | — | — |

## Maintenance


- Keep one row per logical engineering task and preserve completed, superseded, and cancelled history.
- Use only recorded stable task IDs in **Depends On**; do not infer dependency from chronology.
- Link authoritative artifacts instead of reproducing their content.
- Add multiple links only when they belong to the same logical task.
- Put durable current project knowledge—not ordinary implementation history—in **Knowledge**.
- Update an existing row when a newly created durable artifact establishes a recorded relationship.
- Preserve existing valid historical rows and use `—` for fields that cannot be established without inference.
