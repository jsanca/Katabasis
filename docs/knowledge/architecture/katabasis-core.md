# Katabasis — Architecture (Current State)

**Last verified:** 2026-11-03  
**Evidence source:** [KATABASIS-ROADMAP-001 planning report](../../engineering/agents/reports/KATABASIS-ROADMAP-001-roadmap-planning.md)

**Visual models:** [Module map](module-map.md) · [Class model](class-model.md) · [Download lifecycle](../flows/download-lifecycle.md)

---

## Module Layout

Multi-module Maven project. Java 25 required. JPMS modules enforced.

| Maven module | JPMS module | Responsibility |
| --- | --- | --- |
| `katabasis-core` | `jsanca.katabasis.core` | Download engine: lifecycle, events, strategies, concurrency |
| `katabasis-ui` | `jsanca.katabasis.ui` | JavaFX desktop GUI |
| `katabasis-cli` | `jsanca.katabasis.cli` | CLI stub — not yet wired to core |

Dependency direction: `katabasis-cli` → `katabasis-core` ← `katabasis-ui`.  
`katabasis-cli` does not yet declare `requires jsanca.katabasis.core`.

---

## JPMS API Boundary

`katabasis-core` exports only `api.*` packages:

- `api.event` — event types
- `api.exception` — public exceptions
- `api.manager` — `DownloadManager` interface and `DownloadManagers` factory
- `api.model` — request, config, status, result records

All `internal.*` packages are module-private and must not be referenced from `katabasis-ui` or `katabasis-cli`.

---

## Interface + Factory Pattern

Every domain concept follows: `Xxx` interface + `Xxxs` / `XxxManagers` static factory.  
Implementations are `private static final` inner classes of the factory.  
Concrete types must never appear in public API signatures.

---

## Key Components

### DownloadManager

Public orchestration interface. Factory: `DownloadManagers.createDefault()`.  
Implementation: `DefaultDownloadManager` (hidden inside `DownloadManagers`).

Operations: `download(DownloadRequest)`, `cancel(id)`, `pause(id)`, `resume(id)`, `addListener(Consumer<DownloadEvent>)`, `removeListener(...)`.

`download()` registers a task, enqueues it on a virtual thread executor, and returns a `DownloadSubmissionResult` immediately (non-blocking submit).

### DownloadExecutor

Concurrency layer. `DownloadExecutors.createVirtualThreadExecutor(config)` produces an executor backed by virtual threads with a `Semaphore` capping concurrent downloads to `config.maxConcurrentDownloads()` (default: 4).

### DownloadStrategy

Internal interface. Encapsulates how a protocol download runs.  
Current implementations:

| Class | Handles | Location |
| --- | --- | --- |
| `HttpDownloadStrategy` | HTTP/HTTPS single-stream | `internal.http` |
| `SegmentedHttpDownloadStrategy` | HTTP/HTTPS parallel chunks via byte ranges | `internal.http` |
| `HttpDownloadOrchestrator` | Decides between single and segmented by probing server | `internal.http` |

`S3DownloadStrategy` is an empty stub. No FTP strategy exists.

### HttpDownloadOrchestrator

Sends a HEAD request via `HttpRangeSupport.probe()`. If the server responds with `Accept-Ranges: bytes` and content length ≥ `segmentMinSizeBytes` (default 5 MB), delegates to `SegmentedHttpDownloadStrategy`. Otherwise uses `HttpDownloadStrategy`. Probe failure silently falls back to single-stream.

### SegmentedHttpDownloadStrategy

Splits file into N chunks via `HttpRangeSupport.split()`. Opens a `FileChannel` with `TRUNCATE_EXISTING` and uses `StructuredTaskScope` to download all chunks in parallel. Each chunk writes at its absolute byte position — no reassembly required. Expects HTTP 206 (Partial Content) per chunk request; throws on other status codes.

### DefaultDownloadStrategyResolver

Routes by URI scheme via `Scheme.from(scheme)`. Current cases:
- `HTTP`, `HTTPS` → `HttpDownloadOrchestrator`
- Anything else → `IllegalArgumentException`

### DownloadTask (internal)

Mutable runtime state for a live download. Holds: `DownloadInfo` (id, sourceUri, targetPath), `CancellationToken`, `PauseToken`, `AtomicReference<DownloadStatus>`, `AtomicLong` for downloaded/total bytes, and timestamps. Never exposed publicly; callers receive `DownloadTaskSnapshot` (immutable record) through `DownloadManager` control results.

After a download reaches a terminal state (complete, failed, cancelled), `DefaultDownloadManager` removes it from `DownloadRegistry` in the `finally` block. Final state is not queryable after removal.

### DownloadRegistry

`ConcurrentHashMap<String, DownloadTask>` keyed by download ID. Provides: `register`, `findTask`, `findSnapshot`, `contains`, `remove`, `snapshotAll`, `clear`.

### PauseCoordinator

Central pause/resume coordinator. `requestPause(id)` / `requestResume(id)` set the `PauseToken` for the given download. `awaitIfPaused(id, cancellationToken, pauseListener)` blocks the calling thread if a pause is active, calling `pauseListener.onPaused()` and `pauseListener.onResumed()` at the transition boundaries.

Both `HttpDownloadStrategy` and `SegmentedHttpDownloadStrategy` call `awaitIfPaused` per read buffer iteration. In the segmented case, N chunk threads all call it independently — this may cause N `DownloadPausedEvent` emissions per pause signal (risk R-001, unverified).

### CancellationToken

Cooperative flag checked by strategies in the read loop. `cancel()` sets the flag; strategies throw `DownloadCancelledException` when they observe it.

### RetryPolicy

Interface with **zero methods**. Three stub classes exist (`NoRetryPolicy`, `FixedDelayRetryPolicy`, `ExponentialBackoffRetryPolicy`) but contain no logic. No strategy invokes any `RetryPolicy`. Retry is not functional.

---

## Concurrency Model

- **Virtual Threads (Project Loom)** — blocking I/O is cheap; strategies block naturally without async pipelines
- **StructuredTaskScope** — `SegmentedHttpDownloadStrategy` uses `Joiner.awaitAllSuccessfulOrThrow()` for coordinated parallel chunks
- **ScopedValue** preferred over `ThreadLocal` (convention, not enforced in current code)
- **Semaphore** inside `DownloadExecutors` bounds total concurrent downloads
- Cooperative cancellation via `CancellationToken` (no thread interrupts)
- Cooperative pause via `PauseCoordinator` / `PauseToken`

---

## Domain Model

All public data types are Java records with compact constructors validating nulls and copying mutable collections.

| Record | Purpose |
| --- | --- |
| `DownloadRequest` | Caller input: URI + target path |
| `DownloadConfig` | Engine configuration with `Builder`; default: 4 concurrent, 4 segments, 5MB threshold |
| `DownloadInfo` | Immutable download identity: id, sourceUri, targetPath |
| `DownloadSubmissionResult` | Returned by `download()`; carries download ID |
| `DownloadTaskSnapshot` | Point-in-time immutable view of task state |
| `DownloadControlResult` | Returned by cancel/pause/resume; carries action, status, snapshot |

`DownloadProgress` exists in `api.model` as an empty placeholder class and is not used; progress values are carried by `DownloadProgressEvent`.

---

## Event System

`DownloadEvent` is an interface. All events are records implementing it.

| Event | When emitted |
| --- | --- |
| `DownloadStartedEvent` | When a virtual thread begins executing the download |
| `DownloadProgressEvent` | Per read buffer (8 KB default) |
| `DownloadPausedEvent` | When strategy observes a pause request and blocks |
| `DownloadResumedEvent` | When strategy unblocks after resume |
| `DownloadCompletedEvent` | On successful completion; includes duration |
| `DownloadCancelledEvent` | On cooperative cancellation |
| `DownloadFailedEvent` | On any exception; includes `DownloadErrorCode` and duration |

`DefaultDownloadManager.emit()` iterates `CopyOnWriteArrayList<Consumer<DownloadEvent>>` and calls each listener synchronously on the download thread.

---

## katabasis-ui Architecture

Single-screen JavaFX application, no FXML.

- `KatabasisLauncher` — JPMS-compatible entry point
- `DownloadManagerApplication` — `Application` subclass; owns `ObservableList<DownloadRow>`, `DownloadManager`; marshals events to FX thread via `Platform.runLater()`
- `DownloadRow` — inner record; data model per row
- `DownloadRowCell extends ListCell` — renders progress bar, status, action buttons, right-click menu
- Drag-and-drop of URL files, clipboard paste, directory chooser all handled inline
- Styling: AtlantaFX dark theme + `style.css` + Ikonli/Material Design 2 icons

---

## What Is Not Implemented

| Capability | Status |
| --- | --- |
| Retry (any policy) | Stub only — interface has no methods |
| S3 protocol | Stub classes — no logic, no AWS SDK dependency |
| FTP/FTPS protocol | Not present in codebase |
| Checksum validation | Not referenced anywhere |
| `DownloadStateStore` | Interface placeholder only |
| CLI wired to core | `katabasis-cli` is a stub with no core dependency |
| `DownloadManager.close()` | No graceful shutdown mechanism |
