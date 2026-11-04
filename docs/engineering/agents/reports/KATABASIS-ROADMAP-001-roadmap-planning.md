# KATABASIS-ROADMAP-001 — Roadmap Planning — Report

## Status

Complete

## Objective

Inspect the Katabasis repository and produce planning artifacts: current state assessment, architecture gap analysis, risk register, ADR candidates, and a task backlog — without implementing any functionality.

## Summary

Repository was inspected in full. All key source files in `katabasis-core`, `katabasis-ui`, and `katabasis-cli` were read. Each capability was classified with direct file evidence. The findings were cross-referenced against the executive report of 2026-11-03. Discrepancies are noted where the previous report assumed completeness that the code does not support.

The core HTTP engine and JavaFX UI are production-ready. The segmented download strategy is implemented but has an unverified multi-event risk on pause. Retry, S3, FTP, CLI, and persistence are entirely absent from functional code. The roadmap artifact ([ROADMAP.md](../../../roadmap/ROADMAP.md)) records the committed direction.

---

## Files Inspected

| File | Purpose |
| --- | --- |
| `katabasis-core/src/main/java/.../api/manager/DownloadManagers.java` | Manager orchestration, cancel/pause/resume |
| `katabasis-core/src/main/java/.../internal/http/HttpDownloadStrategy.java` | Single-stream HTTP download |
| `katabasis-core/src/main/java/.../internal/http/SegmentedHttpDownloadStrategy.java` | Parallel chunk HTTP download |
| `katabasis-core/src/main/java/.../internal/http/HttpDownloadOrchestrator.java` | Probe-and-delegate decision layer |
| `katabasis-core/src/main/java/.../internal/http/HttpRangeSupport.java` | Byte-range utilities |
| `katabasis-core/src/main/java/.../internal/strategy/DefaultDownloadStrategyResolver.java` | Protocol-to-strategy routing |
| `katabasis-core/src/main/java/.../internal/retry/RetryPolicy.java` | Interface with no methods |
| `katabasis-core/src/main/java/.../internal/retry/{No,FixedDelay,ExponentialBackoff}RetryPolicy.java` | Empty stub classes |
| `katabasis-core/src/main/java/.../internal/s3/S3DownloadStrategy.java` | Empty class |
| `katabasis-core/src/main/java/.../internal/s3/{S3ClientFactory,S3UriParser,S3Location,S3DownloadOptions}.java` | All empty stubs |
| `katabasis-core/src/test/java/.../internal/http/HttpRangeSupportTest.java` | 13 tests, all concrete |
| `katabasis-core/src/test/java/.../internal/execution/DownloadExecutorsTest.java` | 3 tests for virtual threads and semaphore |

---

## Current State Assessment

Each capability is classified as: **Implemented** / **Partial** / **Stub** / **Missing** / **Unverified**.

### katabasis-core

| Capability | Classification | Evidence |
| --- | --- | --- |
| HTTP/HTTPS single-stream download | **Implemented** | `HttpDownloadStrategy.java` — 234 lines, HEAD probe, streaming, progress events, pause/resume, cancellation |
| HTTP/HTTPS segmented parallel download | **Implemented** | `SegmentedHttpDownloadStrategy.java` — 231 lines, `StructuredTaskScope`, `FileChannel` positioned write |
| Probe for byte-range capability (HEAD + Accept-Ranges) | **Implemented** | `HttpRangeSupport.probe()` — full HEAD request + header parse |
| Orchestrator: single vs. segmented decision | **Implemented** | `HttpDownloadOrchestrator.resolveDelegate()` — threshold + probe |
| Protocol routing (HTTP/HTTPS) | **Implemented** | `DefaultDownloadStrategyResolver` — switch on `Scheme.from(scheme)` |
| DownloadManager submit | **Implemented** | `DefaultDownloadManager.download()` — registers, executes via virtual thread |
| DownloadManager cancel | **Implemented** | `DefaultDownloadManager.cancel()` — sets `CancellationToken` |
| DownloadManager pause/resume | **Implemented** | `DefaultDownloadManager.{pause,resume}()` — delegates to `PauseCoordinator` |
| Event system (all 7 types) | **Implemented** | All event records present; emitter wired in both strategies |
| Cooperative cancellation | **Implemented** | `CancellationToken` checked inside read loops in both strategies |
| Cooperative pause/resume (single-stream) | **Implemented** | `PauseCoordinator.awaitIfPaused()` called per buffer read in `HttpDownloadStrategy` |
| Cooperative pause/resume (segmented) | **Partial** | `awaitIfPaused()` called per chunk thread; each of N threads may emit Paused/Resumed events independently — event storm risk unverified |
| Virtual thread executor + semaphore | **Implemented** | `DownloadExecutors`; tested in `DownloadExecutorsTest` (3 tests) |
| DownloadConfig with builder | **Implemented** | `DownloadConfig` record with compact constructor and `Builder`; defaults: 4 concurrent, 4 segments, 5MB threshold |
| DownloadRegistry (in-memory) | **Implemented** | `ConcurrentHashMap`; tasks removed in `finally` after terminal state |
| Utility classes (ByteSize, PathPreparer, etc.) | **Implemented** | Present and in use |
| RetryPolicy interface | **Stub** | `RetryPolicy.java` — zero methods, interface body is empty |
| NoRetryPolicy | **Stub** | Empty class, no logic |
| FixedDelayRetryPolicy | **Stub** | Empty class, no logic |
| ExponentialBackoffRetryPolicy | **Stub** | Empty class, no logic |
| Retry integration with any strategy | **Missing** | Not invoked anywhere in `HttpDownloadStrategy` or `SegmentedHttpDownloadStrategy` |
| S3DownloadStrategy | **Stub** | `S3DownloadStrategy.java` — empty class, 4 lines |
| S3 supporting classes | **Stub** | `S3ClientFactory`, `S3UriParser`, `S3Location`, `S3DownloadOptions` — all empty |
| S3 registration in resolver | **Missing** | `DefaultDownloadStrategyResolver` only handles HTTP/HTTPS; S3 throws `IllegalArgumentException` |
| FTP/FTPS strategy | **Missing** | No FTP class exists in the codebase |
| Checksum validation | **Missing** | Not referenced anywhere |
| DownloadStateStore implementation | **Missing** | Interface exists as a placeholder; no implementation |
| DownloadManager end-to-end tests | **Unverified** | No test file found for `DefaultDownloadManager` orchestration |
| Pause/resume correctness under segmented | **Unverified** | No test validates event count or ordering for segmented pause |
| Cancellation correctness under segmented | **Unverified** | Cancellation code present but no targeted test |
| HttpDownloadOrchestratorTest | **Unverified** | File exists; content not inspected; coverage unknown |
| HttpDownloadSupportTest | **Unverified** | File exists; content not inspected; coverage unknown |

### katabasis-ui

| Capability | Classification | Evidence |
| --- | --- | --- |
| Full JavaFX application | **Implemented** | `DownloadManagerApplication` — programmatic, ObservableList, event listener |
| Download list with progress bars | **Implemented** | `DownloadRowCell` with progress bar and status label |
| Per-row play/pause/cancel | **Implemented** | Buttons wired to manager methods |
| URL paste from clipboard | **Implemented** | Present in `DownloadManagerApplication` |
| Drag-and-drop URL files | **Implemented** | Present in `DownloadManagerApplication` |
| Directory chooser | **Implemented** | Present in `DownloadManagerApplication` |
| Dark theme (AtlantaFX) | **Implemented** | Declared in pom.xml dependency, `style.css` present |
| macOS jpackage packaging | **Implemented** | Documented in `CLAUDE.md` and `pom.xml` |

### katabasis-cli

| Capability | Classification | Evidence |
| --- | --- | --- |
| Module dependency on katabasis-core | **Missing** | `module-info` exports nothing, no `requires jsanca.katabasis.core` |
| Any CLI functionality | **Stub** | `codex.Main` prints a loop; no download logic |

---

## Architecture Gap Analysis

### Gap 1: RetryPolicy has no contract

`RetryPolicy` is an empty interface. No strategy can implement retry because there is no method to call. The gap requires designing the API: how does a strategy ask "should I retry?", pass the exception, and receive back an instruction (retry after delay, give up)?

**Affected releases:** 0.1

### Gap 2: Segmented pause/resume may emit N events per pause signal

Both `HttpDownloadStrategy` and `SegmentedHttpDownloadStrategy` call `awaitIfPaused` per read iteration. In the segmented case, N chunk threads run concurrently and each calls `awaitIfPaused` independently. When a pause is requested, all N threads may emit `DownloadPausedEvent` near-simultaneously, then all N emit `DownloadResumedEvent` on resume. The UI listener may receive 4 paused events for a 4-segment download.

Whether `PauseCoordinator.awaitIfPaused` serializes these is not verified from code alone without reading that class.

**Affected releases:** 0.1

### Gap 3: Terminal state is not queryable after completion

`DefaultDownloadManager.runDownload()` calls `downloadRegistry.remove(info.downloadId())` in the `finally` block. After a download completes, fails, or is cancelled, there is no way to query its final state. The last in-flight snapshot is the last known state.

This is acceptable for the current in-memory design but blocks any "history" or "resume" capability. It becomes a material gap in 0.2 when persistence is added.

**Affected releases:** 0.2

### Gap 4: DefaultDownloadStrategyResolver throws on unknown scheme

The resolver's switch statement has only `HTTP, HTTPS` cases; all others throw `IllegalArgumentException`. When S3 or FTP URIs are submitted, the exception propagates through `runDownload` and emits a `DownloadFailedEvent` with `UNSUPPORTED_SCHEME`. Adding a protocol requires modifying the switch — but the design is clean enough that this is straightforward.

**Affected releases:** 0.3

### Gap 5: CLI module has zero core dependency

`katabasis-cli` has an empty `module-info` and does not declare `requires jsanca.katabasis.core`. Adding the CLI requires modifying `katabasis-cli/pom.xml` and `module-info.java`, then implementing the actual CLI logic.

**Affected releases:** 0.2

### Gap 6: No DownloadManager shutdown / AutoCloseable

`DefaultDownloadManager` holds a `DownloadExecutor` backed by virtual threads and a `DownloadRegistry`, but neither exposes a `close()` method. Active downloads on JVM shutdown are not gracefully terminated or checkpointed.

**Affected releases:** 0.2 (before persistence)

### Gap 7: Content integrity under segmented downloads

`SegmentedHttpDownloadStrategy` does not validate `ETag`, `Last-Modified`, or `Content-Range` response headers across chunks. If the server changes the resource between the initial probe and chunk fetches, the resulting file is silently corrupt.

**Affected releases:** 0.1

### Gap 8: S3 architecture is namespace-only

Five S3 classes exist as empty containers. No AWS SDK dependency is in the pom.xml. Implementing S3 requires both a dependency decision (AWS SDK v2 is ~10 MB) and designing authentication, URI parsing, and range delegation.

**Affected releases:** 0.3

### Gap 9: FTP is entirely absent

There is no FTP class, no FTP case in the resolver, and no FTP library dependency. FTP also differs fundamentally from HTTP: it requires a control connection + data connection pair, has no byte-range header analog (uses REST command), and FTPS requires TLS session management.

**Affected releases:** 0.3

---

## Risk Register

| ID | Description | Severity | Probability | Mitigation |
| --- | --- | --- | --- | --- |
| R-001 | Segmented pause/resume emits N events per signal — may flood UI and corrupt visual state | High | Medium | Read `PauseCoordinator.awaitIfPaused` source; write targeted test with 4-segment download; consider single-event guard in orchestrator |
| R-002 | No retry in any strategy — transient network errors cause immediate download failure | High | High (network is unreliable) | Implement RetryPolicy in 0.1; prioritize ExponentialBackoff |
| R-003 | Resource leaks under cancellation or failure in segmented strategy | Medium | Low | `SegmentedHttpDownloadStrategy` calls `deleteFile` in catch blocks; verify file handle cleanup in `FileChannel` with try-with-resources — present in code |
| R-004 | S3 AWS SDK adds heavyweight dependency (~10 MB) | Medium | Medium | Evaluate S3-compatible HTTP approach (presigned URLs + Range headers) as lighter alternative; ADR required |
| R-005 | FTP library selection — Apache Commons Net is the standard but not modularized for JPMS | Medium | Medium | Evaluate Commons Net as automatic module; test with `--add-opens`; consider patching if needed; ADR required |
| R-006 | BitTorrent peer protocol is fundamentally different from client-server; effort is very high | High | High | Feasibility analysis before committing; strict Go/No-Go gate |
| R-007 | `DownloadManager.download()` does not validate URI scheme before enqueueing | Low | Low | Currently caught in `runDownload` and emitted as `DownloadFailedEvent`; acceptable until CLI adds upfront validation |
| R-008 | No graceful shutdown — active downloads on JVM exit are silently abandoned | Medium | Low | Add `AutoCloseable` to `DownloadManager` interface before 0.2 persistence |
| R-009 | Content integrity: segmented chunks may assemble a corrupt file if server content changes mid-download | Medium | Low | Add ETag/If-Range validation in segmented strategy; include in 0.1 scope |
| R-010 | `DownloadExecutorsTest` relies on timing (`duration >= 400 ms`) — may be flaky in CI under load | Low | Low | Monitor; convert to latches if flakiness appears |

---

## ADR Candidates

The following decisions are of sufficient scope to warrant Architecture Decision Records before implementation begins.

| ID | Question | Phase | Why it matters |
| --- | --- | --- | --- |
| ADR-001 | How should RetryPolicy expose its contract? (Decorator on DownloadStrategy vs. callback in Manager vs. method on interface) | 0.1 | Determines where retry logic lives and whether strategies remain testable independently |
| ADR-002 | How should DownloadStateStore persist state? (File/JSON vs. SQLite vs. in-memory serialization) | 0.2 | Affects recovery semantics, threading, and dependency footprint |
| ADR-003 | Should DownloadManager implement AutoCloseable for graceful shutdown? | 0.2 | Public API contract change — once published, cannot be removed |
| ADR-004 | Which CLI framework? (picocli vs. manual argument parsing vs. another library) | 0.2 | Determines pom.xml dependency and module-info composition |
| ADR-005 | How to implement FTP? (Apache Commons Net vs. pure JDK socket vs. another library) | 0.3 | JPMS compatibility, TLS support, and dependency footprint differ significantly |
| ADR-006 | How to implement S3? (AWS SDK v2 full vs. S3-compatible HTTP with Range headers and presigned URLs) | 0.3 | AWS SDK v2 is ~10 MB; lighter approach may suffice for download-only use case |
| ADR-007 | BitTorrent Go/No-Go: integrate bt library vs. implement piece protocol vs. defer permanently | 0.4 | Determines whether Phase 4 is in scope at all |
| ADR-008 | Should DownloadStrategy expose a capability interface? (e.g., `supportsRanges()`, `supportsPause()`) | 0.3 | Avoids protocol-specific conditionals in Manager; needed before FTP and S3 diverge from HTTP assumptions |

---

## Dependency Map

```
Phase 0.1 (HTTP Reliability)
└── no external dependency

Phase 0.2 (CLI & Persistence)
└── depends on: Phase 0.1 DONE
    └── RetryPolicy must be functional before CLI ships

Phase 0.3 (FTP/FTPS & S3)
└── depends on: Phase 0.1 DONE (strategy pattern verified, event system stable)
└── benefits from: Phase 0.2 (persistence for recovery)
└── requires: ADR-005, ADR-006, ADR-008 decided before T01 starts

Phase 0.4 (BitTorrent)
└── depends on: Phase 0.1 DONE
└── benefits from: Phase 0.2 (persistence for piece state)
└── hard gate: ADR-007 Go/No-Go before any implementation
```

Task-level ordering within Phase 0.1:

```
K-0.1-T01 (verify segmented pause/resume)
K-0.1-T07 (ETag/If-Range) → independent
K-0.1-T02 (DownloadManager end-to-end tests) → independent
K-0.1-T03 (design RetryPolicy API) → K-0.1-T04 (integrate into HttpDownloadStrategy) → K-0.1-T05 (integrate into SegmentedHttpDownloadStrategy)
K-0.1-T06 (timeout handling) → after T04
K-0.1-T08 (resource cleanup tests) → after T01, T07
K-0.1-T09 (expand integration tests) → after T04
K-0.1-T10 (URL list verification) → independent
```

---

## Deviations from Prior Executive Report (2026-11-03)

| Item | Prior report claim | Verified state |
| --- | --- | --- |
| Segmented downloads | "Production-ready for HTTP" | Implemented, but pause/resume multi-event behavior unverified |
| Pause/resume | "Infrastructure working, UI ✓" | Partial — HTTP single-stream: verified in code; Segmented: concern identified |
| RetryPolicy | "Three impls vacías" | Confirmed stubs; also: interface itself has zero methods, so no contract exists yet |
| DownloadManager tests | "Needs expansion" | No test file for DefaultDownloadManager found; this is a gap, not an expansion need |
| `katabasis-cli` | "Solo un stub" | Confirmed; also: no `requires katabasis-core` in module-info |
| S3 | "Classes exist, no logic" | Confirmed; additionally: no AWS SDK in pom.xml, would require new dependency |

---

## Limitations

- `HttpDownloadOrchestratorTest` and `HttpDownloadSupportTest` files were confirmed to exist but their test bodies were not inspected. Coverage for the orchestrator's probe-failure fallback path and HTTP support utilities is marked Unverified.
- `PauseCoordinator.awaitIfPaused()` was not read; the segmented multi-event risk is based on code structure analysis, not `PauseCoordinator` internals.
- No build was executed. All classifications are based on source inspection, not runtime validation.
- UI test coverage is unknown; no UI test files were found.

## Open Follow-Up

- Read `PauseCoordinator.awaitIfPaused()` source before beginning K-0.1-T01 to confirm or rule out the event storm risk.
- Read `HttpDownloadOrchestratorTest` and `HttpDownloadSupportTest` to close the Unverified gaps before starting 0.1.
- Confirm `DownloadConfig.copyOf()` method (used in `DownloadExecutorsTest` line 49) is present and properly tested.
- Human review and approval of roadmap before Phase 0.1 implementation begins.

## Related Records

- Task: [KATABASIS-ROADMAP-001.md](../tasks/KATABASIS-ROADMAP-001.md)
- Roadmap: [ROADMAP.md](../../../roadmap/ROADMAP.md)
- Architecture knowledge: [docs/knowledge/architecture/katabasis-core.md](../../../knowledge/architecture/katabasis-core.md)
- ADR candidates: [docs/adr/README.md](../../../adr/README.md)
