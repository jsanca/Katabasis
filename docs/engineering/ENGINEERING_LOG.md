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

## Maintenance

- Keep one row per logical engineering task and preserve completed, superseded, and cancelled history.
- Use only recorded stable task IDs in **Depends On**; do not infer dependency from chronology.
- Link authoritative artifacts instead of reproducing their content.
- Add multiple links only when they belong to the same logical task.
- Put durable current project knowledge—not ordinary implementation history—in **Knowledge**.
- Update an existing row when a newly created durable artifact establishes a recorded relationship.
- Preserve existing valid historical rows and use `—` for fields that cannot be established without inference.
