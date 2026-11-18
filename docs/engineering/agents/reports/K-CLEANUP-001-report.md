# K-CLEANUP-001 — Dead Code Removal & Public API Hygiene

## Status

Complete

## Objective

Remove confirmed dead code introduced since the initial commit: empty internal placeholder
classes, unused utility methods, and unreachable constructors. Leave the public API surface
intact where types form committed future vocabulary.

## Classification Table

| Candidate | Category | Decision | Rationale |
|---|---|---|---|
| `DownloadScheduler` | Internal placeholder (empty) | DELETE | No body, no callers |
| `ExecutionSlotController` | Internal placeholder (empty) | DELETE | No body, no callers |
| `ProgressReporter` | Internal placeholder (empty) | DELETE | No body, no callers |
| `QueuedDownloadTask` | Internal placeholder (empty) | DELETE | No body, no callers |
| `S3ClientFactory` | Internal placeholder (empty) | DELETE | No body, no callers |
| `S3DownloadOptions` | Internal placeholder (empty) | DELETE | No body, no callers |
| `S3DownloadStrategy` | Internal placeholder (empty) | DELETE | No body, no callers |
| `S3Location` | Internal placeholder (empty) | DELETE | No body, no callers |
| `S3UriParser` | Internal placeholder (empty) | DELETE | No body, no callers |
| `DownloadIdGenerator` | Internal utility | DELETE | No callers; `UUID.randomUUID()` inlined where needed |
| `PercentageCalculator` | Internal utility | DELETE | No callers |
| `ByteSize.TWO_MB/THREE_MB/FOUR_MB/TEN_MB` | Unused constants | DELETE | Only `KB`, `MB`, `GB`, `FIVE_MB` are referenced |
| `DownloadTask.markStarted()` | Unused method | DELETE | Status managed via `updateProgress` / `requestPause` / `requestResume` |
| `DownloadTask.markCompleted()` | Unused method | DELETE | No callers in engine |
| `DownloadTask.markFailed()` | Unused method | DELETE | No callers in engine |
| `DownloadTask.markCancelled()` | Unused method | DELETE | No callers in engine |
| `DownloadTask.pauseToken()` | Unused accessor | DELETE | `PauseToken` accessed only internally via `requestPause/Resume` |
| `DownloadRegistry.findSnapshot()` | Unused method | DELETE | No callers; `findTask()` + `.snapshot()` used directly where needed |
| `DownloadRegistry.snapshotAll()` | Unused method | DELETE | No callers |
| `Preconditions.check(bool, String)` | Unused overload | DELETE | Only `reject()` is called |
| `Preconditions.check(bool, Supplier<String>)` | Unused overload | DELETE | Only `reject()` is called |
| `DefaultDownloadStrategyResolver()` (no-arg) | Unused constructor | DELETE | Only the `(DownloadConfig)` and `(DownloadStrategy)` constructors are called |
| `DownloadException` | Public API exception | KEEP | Domain vocabulary, public boundary |
| `DownloadExecutionException` | Public API exception | KEEP | Domain vocabulary, public boundary |
| `InvalidDownloadRequestException` | Public API exception | KEEP | Domain vocabulary, public boundary |
| `StrategyNotFoundException` | Internal exception | KEEP | Reserved internal signal |
| `DownloadEventPublisher` | Public API (empty interface) | KEEP | Committed future abstraction |
| `DefaultDownloadEventPublisher` | Public API (empty class) | KEEP | Committed future implementation |
| `DownloadCapabilities` | Public API (empty class) | KEEP | Capability flags for resume/range (0.3+) |
| `DownloadCommand` (enum) | Public API (empty enum) | KEEP | Future command model |
| `DownloadProgress` | Public API (empty class) | KEEP | Future progress value type |
| `DownloadStatus.isTerminal()` | Public domain method | KEEP | Useful API vocabulary |
| `DownloadStatus.isActive()` | Public domain method | KEEP | Useful API vocabulary |
| `DownloadCompletedEvent.hasChecksum()` | Public convenience method | KEEP | Used once checksum is emitted (K-0.2-T12) |
| `DownloadFailedEvent.hasCause()` | Public convenience method | KEEP | Convenience for error inspection |
| `DownloadSnapshot.effectivePartialPath()` | Recovery API | DEFER | Required by K-0.2-T11 (resume); defer until then |
| `DownloadErrorCode.NETWORK_ERROR/CONNECTION_REFUSED` | Public enum | KEEP | Domain vocabulary; mapped by FTP/0.3+ |
| `DownloadControlStatus.ACCEPTED/INVALID_STATE/ALREADY_DONE/UNSUPPORTED/FAILED` | Public enum | KEEP | Domain vocabulary for future protocol handlers |

## Files Deleted

- `internal/execution/DownloadScheduler.java`
- `internal/execution/ExecutionSlotController.java`
- `internal/execution/ProgressReporter.java`
- `internal/execution/QueuedDownloadTask.java`
- `internal/s3/S3ClientFactory.java`
- `internal/s3/S3DownloadOptions.java`
- `internal/s3/S3DownloadStrategy.java`
- `internal/s3/S3Location.java`
- `internal/s3/S3UriParser.java`
- `internal/util/DownloadIdGenerator.java`
- `internal/util/PercentageCalculator.java`

## Methods / Constructors Removed

- `ByteSize`: `TWO_MB`, `THREE_MB`, `FOUR_MB`, `TEN_MB` constants
- `DownloadTask`: `markStarted()`, `markCompleted(long, long)`, `markFailed(String)`, `markCancelled()`, `pauseToken()`
- `DownloadRegistry`: `findSnapshot(String)`, `snapshotAll()` and orphaned imports (`DownloadTaskSnapshot`, `Collections`, `LinkedHashMap`)
- `Preconditions`: both `check()` overloads
- `DefaultDownloadStrategyResolver`: no-arg constructor

## Tests

| Suite | Count | Result |
|---|---|---|
| `katabasis-core` | 179 | PASS |
| `katabasis-storage-json` | 88 | PASS |
| `katabasis-cli` | 93 | PASS |
| **Total** | **360** | **PASS** |

No test changes required — none of the removed items had test coverage.

## Notes

- The `internal.s3` package directory is now empty; it will not appear in the compiled
  output. It is not referenced in `module-info.java` (only `api.*` packages are exported).
- `codex/Main.java` deletion (the old CLI stub) was part of K-0.2-T01 and is included in
  the working tree diff but was already logically superseded; it is committed here for
  cleanliness.
