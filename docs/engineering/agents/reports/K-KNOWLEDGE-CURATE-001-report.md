# K-KNOWLEDGE-CURATE-001 — Architecture Knowledge Refresh — Report

## Status

Complete

## Baseline

- Commit: `f99977715c3d2643d3e7fcd25119ed5617d5cd50`
- Subject: K-0.2-T10-HARDEN-001: Coordinated graceful shutdown & final checkpoint
- Date: 2026-11-17
- Reported full-reactor baseline: 364 passing tests, zero failures (from K-0.2-T10-HARDEN-001; not re-run because this task changed no code).
- Isolated worktree: `/Users/jonathan/.local/share/opencode/worktree/ddc975/k-knowledge-curate-001`.

## Objective

Reconcile architectural knowledge with the implemented 0.2 system after T01–T10, T10-HARDEN-001, K-CLEANUP-001, K-JAVA27-001, and the ADR-002/ADR-004 corrections.

## Inventory — stale or inaccurate items found

| Item | Before | Classification | Resolution |
| --- | --- | --- | --- |
| Java version | "Java 25 required" in architecture and project context | Stale | Updated to Java 27 |
| Module count | Three modules; no `katabasis-storage-json` | Stale | Four modules with real dependencies |
| CLI status | "stub — not yet wired to core" | Stale | Documented implemented picocli CLI |
| Dependency direction | CLI had no core edge; storage absent | Incorrect | CLI/UI → core + storage; storage → core; core independent |
| Retry | "Interface with zero methods; not functional" | Stale | Functional `RetryConfig` + `RetryExecutor` + classifier |
| S3 | `S3DownloadStrategy` shown as an empty stub in the component table | Removed code | Documented as future capability; placeholders removed by K-CLEANUP-001 |
| `DownloadProgress` | Empty placeholder described as unused | Partly stale | Still exists; retained API vocabulary with no behavior |
| `DownloadStateStore` | "placeholder only" | Stale | Core port implemented by `FileDownloadStateStore` |
| Graceful shutdown | "No graceful shutdown mechanism" | Stale | `AutoCloseable`, T10-HARDEN ordering documented |
| Checkpoint architecture | Absent | Missing | New `architecture/checkpointing.md` |
| Persistence guarantees | Absent from knowledge | Missing | Summarised from corrected ADR-002 |
| CLI lifecycle | Absent | Missing | New `flows/cli-lifecycle.md` |
| Domain model | Missing `RetryConfig`, `HttpTimeoutConfig`, `DownloadSnapshot`, `SegmentSnapshot` | Missing | Added |
| Pause event storm | "risk R-001, unverified" | Stale | Removed; fixed by K-0.1-T01 |
| Module diagram (Mermaid) | Three modules | Incorrect | Four modules, five `requires` edges |
| Module diagram (editorial HTML) | Three modules | Incorrect | Redrawn with four modules |
| Class model | No checkpoint ports or retry collaborators | Incomplete | Updated |
| Lifecycle flow | No checkpoint/retry interaction | Incomplete | Updated |
| Shutdown sequence | Absent | Missing | Added Mermaid sequence |
| `docs/PROJECT.md` | 0.1-era state, Java 25, three modules | Stale | Updated |

## Changes

### Updated

- `docs/knowledge/architecture/katabasis-core.md` — rewritten current-state architecture: four modules, execution, retry, timeouts, events, checkpoint architecture, exact graceful-shutdown order, CLI and UI, domain model, non-implemented capabilities, reconciliation notes.
- `docs/knowledge/architecture/module-map.md` — four-module Mermaid map and JPMS exports.
- `docs/knowledge/architecture/class-model.md` — public API/checkpoint ports, event hierarchy including `DownloadCheckpointFailedEvent`, internal collaborators including retry and resource identity.
- `docs/knowledge/flows/download-lifecycle.md` — checkpoint registration and save interaction, retry loop, explicit cancellation manifest deletion, failure retention, shutdown distinction.
- `docs/knowledge/architecture/README.md` — added checkpointing page.
- `docs/knowledge/flows/README.md` — added CLI lifecycle page.
- `docs/knowledge/architecture/diagrams/module-map.html` — redrawn for four modules.
- `docs/knowledge/flows/diagrams/download-lifecycle.html` — description clarified that checkpointing/retry are omitted from the editorial happy path.
- `docs/PROJECT.md` — Java 27, four modules, current 0.2 state, repository map, dependencies.

### Created

- `docs/knowledge/architecture/checkpointing.md` — ownership, coordinator triggers, manifest format, guarantees, shutdown checkpointing, known discrepancy.
- `docs/knowledge/flows/cli-lifecycle.md` — commands, options, interactive controls, shutdown versus cancellation, exit codes, structured errors.
- `docs/engineering/agents/tasks/K-KNOWLEDGE-CURATE-001.md`
- `docs/engineering/agents/reports/K-KNOWLEDGE-CURATE-001-report.md`
- `docs/engineering/ENGINEERING_LOG.md` row.

### Reviewed and left unchanged

- `docs/knowledge/README.md` — area navigation remains correct.
- `docs/knowledge/architecture/diagrams/core-collaborators.html` — still accurate for core execution; no removed placeholder appears.
- `docs/adr/ADR-002`, `ADR-003`, `ADR-004` — reviewed; not edited (ADR-002/004 stale T10-era text is flagged below rather than rewritten).
- `docs/roadmap/ROADMAP.md` — not edited.
- `README.md` — not edited; outside the curated knowledge scope and not materially contradicted in the reviewed sections.

## Material discrepancies recorded (not silently fixed)

1. **ADR-002 T10 handoff** still says `DownloadManagers.createDefault()` has no persistence wiring and lists `CheckpointSource` as a T10 recommendation. T10 and T10-HARDEN implemented that wiring. The ADR remains authoritative for persistence guarantees and schema; its handoff sections are historical and predate the implementation.
2. **K-0.2-T10-HARDEN-001 report vs code:** the report describes a `flushPending()` guard that skips saves when a non-terminal pending entry has no source. At baseline `f999777`, `CheckpointCoordinator.flushPending()` has no such guard; the implemented protection is `notifyShutdownCheckpoint()` removing the pending entry before saving. Knowledge documents the code; this needs an ADR/report note or a code change decision.
3. **ADR-004 is untracked in `main`** and therefore absent from the isolated baseline worktree. It was read from `main` as evidence; knowledge pages do not link to it to keep link validation clean.
4. **T11 is in progress** in `main`; no recovery behavior is documented as implemented.

## Deferred until T11

- Actual recovery/resume flow and command documentation.
- Validation of ADR-002's recovery preconditions against the T11 implementation.
- Any reconciliation of T11 changes to `CheckpointCoordinator`, store discovery, or CLI recovery commands.
- Re-check of the `flushPending` discrepancy if T11 touches the coordinator.

## Validation

```
self_check.py (diagram-design)
OK docs/knowledge/architecture/diagrams/module-map.html
OK docs/knowledge/architecture/diagrams/core-collaborators.html
OK docs/knowledge/flows/diagrams/download-lifecycle.html

mermaid_extract.py
module-map.md:      1 flowchart, 4 nodes, 5 edges, 0 dangling
checkpointing.md:   1 flowchart, 7 nodes, 6 edges, 0 dangling
download-lifecycle.md: 3 sequence diagrams, 0 dangling
cli-lifecycle.md:   1 sequence diagram, 0 dangling
katabasis-core.md:  1 shutdown sequence diagram, 0 dangling

Mermaid 11 parser (class-model.md): all 3 classDiagram blocks parse OK

Relative-link check: 75 markdown files
No broken links under docs/knowledge.
Broken links reported only for artifacts absent from the isolated worktree
because they are untracked in main (ADR-004, ADR-009, K-0.2-* tasks/reports).
These are pre-existing isolation artifacts, not curation regressions.
```

Notes:

- The comprehensive sequence diagrams exceed the diagram-design editorial budget (max 12 messages); they are documentation diagrams, not editorial deliverables. The three editorial HTML diagrams remain within budget.
- No compile or test run: documentation-only change. The baseline test figure is the reported 364/364 from K-0.2-T10-HARDEN-001.

## Scope verification

Changed paths are limited to `docs/knowledge/**`, `docs/PROJECT.md`, and the engineering task/report/log. No production code, tests, ADRs, roadmap, or T11 artifacts were modified. The worktree baseline was `f999777`.

## Before/after summary

**Before:** knowledge described a three-module 0.1-era system with Java 25, a CLI stub, non-functional retry, S3 stubs, a placeholder state store, no checkpoint design, and no graceful shutdown.

**After:** knowledge describes the four-module Java 27 system at `f999777`: core with functional retry/timeouts and HTTP orchestration; storage-json implementing the checkpoint ports; CLI and UI composing both; exact T10-HARDEN shutdown ordering; explicit persistence guarantees and non-guarantees; and recovery clearly marked as pending T11.

## Related Records

- Task: [K-KNOWLEDGE-CURATE-001.md](../tasks/K-KNOWLEDGE-CURATE-001.md)
- Architecture: [katabasis-core.md](../../../knowledge/architecture/katabasis-core.md)
- Checkpointing: [checkpointing.md](../../../knowledge/architecture/checkpointing.md)
- CLI lifecycle: [cli-lifecycle.md](../../../knowledge/flows/cli-lifecycle.md)
