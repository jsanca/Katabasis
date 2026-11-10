# ADR-003 — DownloadManager AutoCloseable and Graceful Shutdown

## Status

Accepted

## Context

Before K-0.2-T03 there was no shutdown API on `DownloadManager`. The `ExecutorService` backing
virtual-thread execution was never terminated, so a process using `DownloadManager` could not
exit cleanly without hard-killing the JVM. This also meant:

- Downloads in flight at shutdown had no cooperative stop signal.
- The `CheckpointCoordinator` (from `katabasis-storage-json`) could not be safely flushed before
  exit, because there was no hook to run before the executor terminated.
- CLI consumers would have no way to drain work before `main()` returned.

Three design questions required answers:

1. What lifecycle states are needed, and how are concurrent state transitions guarded?
2. How should multiple simultaneous `close()` callers be handled?
3. How should downloads stopped by shutdown be distinguished from user-initiated cancels in the
   event stream?

## Decision

**AutoCloseable on `DownloadManager`**: The public interface now extends `AutoCloseable` with a
narrowed `void close()` (no checked exception). This enables try-with-resources and aligns with
the Java standard lifecycle idiom.

**Three lifecycle states** (`OPEN → SHUTTING_DOWN → CLOSED`), private to `DefaultDownloadManager`.
The `SHUTTING_DOWN` state is the admission gate: once set, `download()` throws
`DownloadRejectedException` before touching the executor.

**Admission guard via `ReentrantReadWriteLock`**:
- `download()` acquires the read lock, checks the lifecycle, registers the task, and submits to
  the executor — all within the lock. Many concurrent submissions hold read locks simultaneously.
- `close()` acquires the write lock to sweep the registry and signal cancellation, then releases
  it immediately. The bounded `executor.awaitTermination(10s)` runs outside the write lock so it
  does not block concurrent `cancel()` or `pause()` calls during drain.

**Concurrent and repeated `close()` calls via `CountDownLatch(1)`**: The first caller sets
`SHUTTING_DOWN`, performs the sweep, calls `executor.close()`, sets `CLOSED`, and counts down the
latch. Subsequent callers detect a non-`OPEN` lifecycle and block on `closeLatch.await()` until
the first caller counts it down. This is safe and cheap.

**Event suppression via `ConcurrentHashMap.newKeySet()`**: A `shutdownCancelledIds` set records
download IDs swept during `close()`. The `emit()` method silently drops `DownloadCancelledEvent`
for any ID in this set. User-initiated cancels are not affected because their IDs are never added
to this set.

**`DownloadExecutor` extends `AutoCloseable`**: `close()` calls `executor.shutdown()` then
`executor.awaitTermination(10s)`. It does NOT call `shutdownNow()` — virtual threads holding
open file handles or network connections must terminate cooperatively via the cancellation token.
If the timeout expires, a warning is logged and `close()` returns. This is the documented
limitation: stalled downloads are not interrupted by default; `HttpTimeoutConfig` is the
appropriate mechanism for bounding stalled HTTP reads.

**Paused downloads**: `pauseCoordinator.clear(id)` already calls `requestResume()` internally,
unblocking any download waiting in `awaitIfPaused`. The cancellation token check inside
`awaitIfPaused` then causes the download to exit.

## Alternatives Considered

**`shutdownNow()` to interrupt stalled threads**: Rejected. Virtual threads holding open
`OutputStream` or `FileChannel` handles could leave partial files on disk.
`HttpTimeoutConfig.readInactivityTimeout` already handles runaway HTTP reads.

**`AtomicReference<ManagerLifecycle>` instead of `ReentrantReadWriteLock`**: An atomic reference
would not give the admission gate atomicity needed: checking lifecycle and registering the task
must be one indivisible unit to prevent a race where `close()` sweeps the registry between the
lifecycle check and `register()`. The read-write lock makes this atomic.

**Emitting `DownloadFailedEvent` instead of `DownloadCancelledEvent` for shutdown stops**: The
engine already emits `DownloadFailedEvent` when a cancellation exception propagates. The
suppression only targets `DownloadCancelledEvent` — the event emitted by strategies when they
detect the cancellation token and clean up cooperatively. Failure events are not suppressed.

## Consequences

**Easier:**
- CLI consumers can wrap `DownloadManager` in try-with-resources for clean exit.
- K-0.2-T10 (checkpoint-on-shutdown) can now hook into `close()` ordering: register a listener
  or extend `close()` to flush `CheckpointCoordinator` before calling `executor.close()`.
- The admission gate prevents new work from entering after shutdown begins, simplifying
  recovery bookkeeping.

**Harder / limitations:**
- Stalled downloads that are not cancelled cooperatively within 10 seconds will cause `close()` to
  return before those virtual threads exit. Use `HttpTimeoutConfig` to bound HTTP read inactivity.
- The `ManagerLifecycle` enum and all shutdown fields are private to `DefaultDownloadManager`.
  External tooling cannot inspect lifecycle state without a new API surface.
