# Class Model

Static structure of the public API and of the internal collaborators that execute a download.

**Last verified:** 2026-11-03
**Evidence:** `katabasis-core/src/main/java/jsanca/katabasis/core/api/**` and `internal/**`; `katabasis-ui` for the consumer side.

## Public API — management surface

Every type below is exported by `jsanca.katabasis.core.api.*`. Records are immutable; enums are closed sets.

```mermaid
classDiagram
    direction LR

    class DownloadManagers {
        <<factory>>
        +createDefault() DownloadManager
        +createDefault(DownloadConfig) DownloadManager
    }

    class DownloadManager {
        <<interface>>
        +download(DownloadRequest) DownloadSubmissionResult
        +cancel(String) DownloadControlResult
        +pause(String) DownloadControlResult
        +resume(String) DownloadControlResult
        +addListener(Consumer) void
        +removeListener(Consumer) void
    }

    class DownloadRequest {
        <<record>>
        URI sourceUri
        Path targetPath
    }

    class DownloadConfig {
        <<record>>
        int maxConcurrentDownloads
        int segmentCount
        long segmentMinSizeBytes
    }

    class DownloadInfo {
        <<record>>
        String downloadId
        URI sourceUri
        Path targetPath
    }

    class DownloadSubmissionResult {
        <<record>>
        String downloadId
        DownloadStatus status
        Instant createdAt
    }

    class DownloadControlResult {
        <<record>>
        String downloadId
        DownloadControlAction action
        DownloadControlStatus status
        String message
        DownloadTaskSnapshot snapshot
    }

    class DownloadTaskSnapshot {
        <<record>>
        DownloadInfo info
        DownloadStatus status
        long downloadedBytes
        long totalBytes
        Instant createdAt
        Instant updatedAt
        String errorMessage
    }

    class DownloadStatus {
        <<enumeration>>
        PENDING
        IN_PROGRESS
        PAUSED
        COMPLETED
        FAILED
        CANCELLED
    }

    class DownloadControlAction {
        <<enumeration>>
        CANCEL
        PAUSE
        RESUME
    }

    class DownloadControlStatus {
        <<enumeration>>
        ACCEPTED
        REQUESTED
        NOT_FOUND
        INVALID_STATE
        ALREADY_DONE
        UNSUPPORTED
        FAILED
    }

    DownloadManagers ..> DownloadManager : creates
    DownloadManagers ..> DownloadConfig : uses
    DownloadManager ..> DownloadRequest : accepts
    DownloadManager ..> DownloadSubmissionResult : returns
    DownloadManager ..> DownloadControlResult : returns
    DownloadControlResult --> DownloadTaskSnapshot
    DownloadControlResult --> DownloadControlAction
    DownloadControlResult --> DownloadControlStatus
    DownloadTaskSnapshot --> DownloadInfo
    DownloadTaskSnapshot --> DownloadStatus
    DownloadSubmissionResult --> DownloadStatus
```

## Public API — event hierarchy

All events are records implementing the `DownloadEvent` interface.

```mermaid
classDiagram
    direction TB

    class DownloadEvent {
        <<interface>>
        +info() DownloadInfo
        +status() DownloadStatus
        +occurredAt() Instant
    }

    class DownloadStartedEvent {
        <<record>>
    }
    class DownloadProgressEvent {
        <<record>>
        long downloadedBytes
        long totalBytes
    }
    class DownloadPausedEvent {
        <<record>>
    }
    class DownloadResumedEvent {
        <<record>>
    }
    class DownloadCompletedEvent {
        <<record>>
        Path targetPath
        long totalBytes
        Duration duration
    }
    class DownloadCancelledEvent {
        <<record>>
    }
    class DownloadFailedEvent {
        <<record>>
        String message
        DownloadErrorCode errorCode
    }

    DownloadEvent <|.. DownloadStartedEvent
    DownloadEvent <|.. DownloadProgressEvent
    DownloadEvent <|.. DownloadPausedEvent
    DownloadEvent <|.. DownloadResumedEvent
    DownloadEvent <|.. DownloadCompletedEvent
    DownloadEvent <|.. DownloadCancelledEvent
    DownloadEvent <|.. DownloadFailedEvent
    DownloadEvent --> DownloadInfo
    DownloadEvent --> DownloadStatus
```

## Internal collaborators

The implementation lives in `internal.*` and is never exported.

```mermaid
classDiagram
    direction TB

    class DownloadManager {
        <<interface>>
        +download(DownloadRequest)
        +cancel(String)
        +pause(String)
        +resume(String)
    }

    class DefaultDownloadManager {
        <<package-private>>
        -DownloadRegistry registry
        -DownloadExecutor executor
        -PauseCoordinator pauseCoordinator
        -DownloadStrategyResolver strategyResolver
        -List~Consumer~ listeners
    }

    class DownloadRegistry {
        <<in-memory>>
        +register(DownloadTask)
        +findTask(String) Optional
        +findSnapshot(String) Optional
        +remove(String)
        +snapshotAll() List
    }

    class DownloadExecutor {
        <<interface>>
        +execute(Runnable) void
    }

    class VirtualThreadDownloadExecutor {
        -Semaphore semaphore
    }

    class DownloadStrategyResolver {
        <<interface>>
        +resolve(DownloadInfo) DownloadStrategy
    }

    class DefaultDownloadStrategyResolver

    class DownloadStrategy {
        <<internal interface>>
        +download(DownloadTask, DownloadExecutionContext, Consumer)
    }

    class HttpDownloadOrchestrator
    class HttpDownloadStrategy
    class SegmentedHttpDownloadStrategy

    class DownloadTask {
        -DownloadInfo info
        -DownloadStatus status
        -AtomicLong downloadedBytes
        -AtomicLong totalBytes
        -CancellationToken cancellationToken
        -PauseToken pauseToken
        +snapshot() DownloadTaskSnapshot
    }

    class PauseCoordinator {
        +requestPause(String)
        +requestResume(String)
        +awaitIfPaused(String, CancellationToken, PauseListener)
        +clear(String)
    }

    class CancellationToken {
        +cancel()
        +isCancelled() boolean
    }

    class PauseToken {
        +pause()
        +resume()
        +isPauseRequested() boolean
    }

    DefaultDownloadManager ..|> DownloadManager
    DefaultDownloadManager *-- DownloadRegistry
    DefaultDownloadManager *-- DownloadExecutor
    DefaultDownloadManager *-- PauseCoordinator
    DefaultDownloadManager --> DownloadStrategyResolver
    VirtualThreadDownloadExecutor ..|> DownloadExecutor
    DefaultDownloadStrategyResolver ..|> DownloadStrategyResolver
    DefaultDownloadStrategyResolver --> DownloadStrategy
    DownloadStrategy <|.. HttpDownloadOrchestrator
    DownloadStrategy <|.. HttpDownloadStrategy
    DownloadStrategy <|.. SegmentedHttpDownloadStrategy
    HttpDownloadOrchestrator --> HttpDownloadStrategy
    HttpDownloadOrchestrator --> SegmentedHttpDownloadStrategy
    DownloadRegistry o-- DownloadTask
    DownloadTask --> CancellationToken
    DownloadTask --> PauseToken
    DownloadTask ..> DownloadTaskSnapshot : creates
```

## Notes that the diagrams cannot show

- `DefaultDownloadManager` is not a top-level type: it is a package-private static nested class of `DownloadManagers`, so callers only ever see the `DownloadManager` interface.
- `DownloadRegistry` and all task state are in memory. Tasks are removed in a `finally` block when they reach a terminal state, so their final state is not queryable afterwards.
- `S3DownloadStrategy`, `S3UriParser`, and the three `RetryPolicy` placeholder classes exist in `internal.*` but contain no logic; they are intentionally absent from the collaborator diagram.
- `DownloadProgress` in `api.model` is an empty placeholder class and is not part of the live flow; progress is carried by `DownloadProgressEvent`.

## Related

- [Module map](module-map.md)
- [Architecture: current state](katabasis-core.md)
- [Download lifecycle and control flows](../flows/download-lifecycle.md)
- [Editorial version of the collaborator diagram](diagrams/core-collaborators.html)
