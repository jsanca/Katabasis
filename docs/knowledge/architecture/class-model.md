# Class Model

Static structure of the public API, the event contract, and the internal collaborators that execute a download.

**Last verified:** 2026-11-18  
**Baseline:** `f999777`  
**Evidence:** `katabasis-core` and `katabasis-storage-json` source at the baseline.

## Public API — management and checkpoint ports

```mermaid
classDiagram
    direction LR

    class DownloadManagers {
        <<factory>>
        +createDefault() DownloadManager
        +createDefault(DownloadConfig) DownloadManager
        +createDefault(DownloadConfig, Consumer) DownloadManager
    }

    class DownloadManager {
        <<interface, AutoCloseable>>
        +download(DownloadRequest) DownloadSubmissionResult
        +cancel(String) DownloadControlResult
        +pause(String) DownloadControlResult
        +resume(String) DownloadControlResult
        +addListener(Consumer) void
        +removeListener(Consumer) void
        +close() void
    }

    class CheckpointSourceProvider {
        <<interface>>
        +findSource(String) Optional
    }

    class CheckpointSource {
        <<interface>>
        +buildSnapshot(Instant) DownloadSnapshot
    }

    class DownloadStateStore {
        <<interface>>
        +save(DownloadSnapshot) void
        +findById(String) Optional
        +findRecoverable() List
        +delete(String) void
    }

    class DownloadRequest {
        <<record>>
        URI sourceUri
        Path targetPath
        Optional~RetryConfig~ retryConfig
    }

    class DownloadConfig {
        <<record>>
        int maxConcurrentDownloads
        int segmentCount
        long segmentMinSizeBytes
        Duration checkpointInterval
        RetryConfig retryConfig
        HttpTimeoutConfig httpTimeoutConfig
    }

    class RetryConfig {
        <<record>>
        Kind kind
        int maxAttempts
        Duration initialDelay
        Duration maxDelay
        double jitterFactor
    }

    class HttpTimeoutConfig {
        <<record>>
        Optional~Duration~ connectTimeout
        Optional~Duration~ responseTimeout
        Optional~Duration~ readInactivityTimeout
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
    }

    class DownloadSnapshot {
        <<record>>
        String downloadId
        URI sourceUri
        Path targetPath
        Path partialPath
        String etag
        long expectedTotalBytes
        long confirmedBytes
        DownloadStatus status
        List~SegmentSnapshot~ segments
        Instant createdAt
        Instant checkpointAt
        +isRecoverable() boolean
        +effectivePartialPath() Path
    }

    class SegmentSnapshot {
        <<record>>
        int index
        long startByte
        long endByte
        long confirmedBytes
    }

    DownloadManagers ..> DownloadManager : creates
    DownloadManagers ..> DownloadConfig : uses
    DownloadManager ..> DownloadRequest : accepts
    DownloadManager ..> DownloadSubmissionResult : returns
    DownloadManager ..> DownloadControlResult : returns
    DownloadManager ..> DownloadEvent : emits
    DownloadControlResult --> DownloadTaskSnapshot
    DownloadTaskSnapshot --> DownloadInfo
    DownloadSnapshot --> SegmentSnapshot
    CheckpointSource ..> DownloadSnapshot : builds
    CheckpointSourceProvider ..> CheckpointSource : finds
    DownloadStateStore ..> DownloadSnapshot : persists
```

## Public API — event hierarchy

```mermaid
classDiagram
    direction TB

    class DownloadEvent {
        <<interface>>
        +info() DownloadInfo
        +status() DownloadStatus
        +occurredAt() Instant
    }

    class DownloadStartedEvent { <<record>> }
    class DownloadProgressEvent {
        <<record>>
        long downloadedBytes
        long totalBytes
    }
    class DownloadPausedEvent { <<record>> }
    class DownloadResumedEvent { <<record>> }
    class DownloadCompletedEvent {
        <<record>>
        Path targetPath
        long totalBytes
        Duration duration
    }
    class DownloadCancelledEvent { <<record>> }
    class DownloadFailedEvent {
        <<record>>
        String message
        DownloadErrorCode errorCode
    }
    class DownloadCheckpointFailedEvent {
        <<record>>
        String message
        Throwable cause
    }

    DownloadEvent <|.. DownloadStartedEvent
    DownloadEvent <|.. DownloadProgressEvent
    DownloadEvent <|.. DownloadPausedEvent
    DownloadEvent <|.. DownloadResumedEvent
    DownloadEvent <|.. DownloadCompletedEvent
    DownloadEvent <|.. DownloadCancelledEvent
    DownloadEvent <|.. DownloadFailedEvent
    DownloadEvent <|.. DownloadCheckpointFailedEvent
    DownloadEvent --> DownloadInfo
    DownloadEvent --> DownloadStatus
```

## Internal collaborators

```mermaid
classDiagram
    direction TB

    class DownloadManager {
        <<interface>>
    }
    class DefaultDownloadManager {
        <<package-private>>
        -DownloadRegistry registry
        -DownloadExecutor executor
        -PauseCoordinator pauseCoordinator
        -DownloadStrategyResolver resolver
        -Set shutdownCancelledIds
        -Set naturallyTerminatedDuringShutdown
    }
    class DownloadRegistry
    class DownloadExecutor {
        <<interface>>
    }
    class VirtualThreadDownloadExecutor {
        -Semaphore semaphore
    }
    class DownloadStrategyResolver {
        <<interface>>
    }
    class DefaultDownloadStrategyResolver
    class DownloadStrategy {
        <<internal interface>>
        +download(DownloadTask, DownloadExecutionContext, Consumer)
    }
    class HttpDownloadOrchestrator
    class HttpDownloadStrategy
    class SegmentedHttpDownloadStrategy
    class RetryExecutor
    class HttpFailureClassifier
    class ResourceIdentity
    class DownloadTask {
        -DownloadInfo info
        -AtomicReference~DownloadStatus~ status
        -AtomicLong downloadedBytes
        -AtomicLong totalBytes
        -CancellationToken cancellationToken
        -PauseToken pauseToken
        -CheckpointSource checkpointSource
        +snapshot() DownloadTaskSnapshot
    }
    class PauseCoordinator
    class CancellationToken
    class PauseToken

    DefaultDownloadManager ..|> DownloadManager
    DefaultDownloadManager ..|> CheckpointSourceProvider
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
    HttpDownloadStrategy --> RetryExecutor
    SegmentedHttpDownloadStrategy --> RetryExecutor
    RetryExecutor --> HttpFailureClassifier
    SegmentedHttpDownloadStrategy --> ResourceIdentity
    DownloadRegistry o-- DownloadTask
    DownloadTask --> CancellationToken
    DownloadTask --> PauseToken
    DownloadTask ..> CheckpointSource : registers
    DownloadTask ..> DownloadTaskSnapshot : creates
```

## Notes the diagrams cannot show

- `DefaultDownloadManager` is a package-private nested class of `DownloadManagers`; callers see only `DownloadManager`, and the checkpoint coordinator sees it through `CheckpointSourceProvider`.
- `download()` returns immediately with `PENDING`; the worker runs on a virtual thread bounded by a semaphore.
- Core owns the checkpoint ports and domain records; `katabasis-storage-json` owns the JSON adapter and `CheckpointCoordinator` (see [checkpointing.md](checkpointing.md)).
- Retry is functional (`RetryExecutor`, `HttpFailureClassifier`); the old empty `RetryPolicy` family is now an internal strategy contract, not a stub.
- Removed by K-CLEANUP-001 and absent from this model: `DownloadScheduler`, `ExecutionSlotController`, `ProgressReporter`, `QueuedDownloadTask`, all `internal.s3.*` placeholders, `DownloadIdGenerator`, and `PercentageCalculator`.
- Retained by K-CLEANUP-001 as public API vocabulary but behaviorless: `DownloadCapabilities`, `DownloadCommand`, `DownloadProgress`, `DownloadEventPublisher`, `DefaultDownloadEventPublisher`. They are not active components.

## Related

- [Architecture: current state](katabasis-core.md)
- [Checkpointing](checkpointing.md)
- [Module map](module-map.md)
- [Download lifecycle](../flows/download-lifecycle.md)
