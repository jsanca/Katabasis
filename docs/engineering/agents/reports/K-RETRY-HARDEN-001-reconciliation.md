# K-RETRY-HARDEN-001 — Retry Hardening: Fix Review Bugs and Apply Simplifications — Report

## Status

Complete

## Summary

Fixed three confirmed correctness bugs from K-REVIEW-001, improved two test cases, and
applied five justified simplifications from K-ANAL-001. 90 katabasis-core tests pass
(+3 new regression tests). Full project build: SUCCESS.

---

## Correctness Fixes

### Fix 1 — Exponential backoff long-shift overflow
**File:** `internal/retry/ExponentialBackoffRetryPolicy.java`

Replaced `initMs << Math.min(failedAttempts, 62)` with a `saturatingShift()` helper that
returns `Long.MAX_VALUE` when the shift would overflow, so the subsequent `Math.min(..., maxMs)`
always clamps to `maxDelay`. The overflow condition is: `shift >= Long.SIZE - 1` or
`value > (Long.MAX_VALUE >> shift)`.

Before:
```java
final long shift  = Math.min(failedAttempts, 62);
final long baseMs = Math.min(initMs << shift, maxMs);
```

After:
```java
final long baseMs = Math.min(saturatingShift(initMs, failedAttempts), maxMs);
// ...
private static long saturatingShift(long value, int shift) {
    if (shift <= 0 || value <= 0) return value;
    if (shift >= Long.SIZE - 1 || value > (Long.MAX_VALUE >> shift)) return Long.MAX_VALUE;
    return value << shift;
}
```

**Regression test added:** `exponentialBackoffDelayNeverNegativeAtLargeAttemptCounts` — calls
`decide(i, ...)` for `i` in `[0, 100)` with `initialDelay=100ms`, `maxDelay=60s`, asserts
delay is always `>= 0` and `<= maxDelay`.

---

### Fix 2 — `totalDownloaded` double-counting on chunk retry
**File:** `internal/http/SegmentedHttpDownloadStrategy.java`

Added a local `long attemptBytes = 0` counter inside `doChunkAttempt()`. On any exception
other than `DownloadCancelledException`, the per-attempt bytes are subtracted from the shared
`AtomicLong totalDownloaded` before rethrowing, so progress is accurate if the chunk retries.
Cancellation is explicitly excluded from the rewind because the cancelled event uses
`totalDownloaded` to report how far the download got.

```java
long attemptBytes = 0;
try (InputStream inputStream = response.body()) {
    while ((bytesRead = inputStream.read(buffer)) != -1) {
        // ... write, track attemptBytes, addAndGet to totalDownloaded ...
    }
} catch (DownloadCancelledException e) {
    throw e; // terminal — no rewind
} catch (Exception e) {
    totalDownloaded.addAndGet(-attemptBytes); // rewind phantom bytes
    throw e;
}
```

---

### Fix 3 — `InterruptedException` during retry sleep swallowed interrupt flag
**File:** `internal/retry/RetryExecutor.java`

The `sleep()` call inside the retry loop was not catching `InterruptedException` itself.
When interrupted, the exception propagated out of `execute()` as a checked `Exception`,
the interrupt flag was never restored, and callers could see the thread as uninterrupted.

Fixed by wrapping the `sleep()` call: on `InterruptedException`, the flag is restored with
`Thread.currentThread().interrupt()` and the exception is rethrown immediately without
further retry consultation.

```java
try {
    sleep(decision.delay());
} catch (final InterruptedException ie) {
    Thread.currentThread().interrupt();
    throw ie;
}
```

The Javadoc for `execute()` was updated to accurately describe interrupt semantics and to
correct the description of when the cancellation flag is checked ("after each failure, before
consulting the retry policy or sleeping" — not "before each sleep" as previously written).

**Regression test added:** `interruptDuringRetrySleepRestoresFlagAndRethrows` — starts the
executor on a virtual thread, waits for the first attempt to complete, interrupts the thread
during the sleep, then joins and asserts the interrupt flag was set before the exception escaped.

---

## Test Quality Fixes

### Fix 4 — `cancellationFlagStopsRetryBeforeSleep` test mismatch
**File:** `internal/retry/RetryExecutorTest.java`

The original test supplied `() -> true` (always cancelled) which caused the first throw to
short-circuit before any sleep was scheduled. The assertion was correct by accident, not by
exercising the intended path.

The test was renamed to `cancellationFlagOnFirstThrowStopsImmediately` to accurately describe
what it tests. A new test `cancellationAfterFirstFailureStopsRetryBeforeSleep` was added
using `AtomicBoolean` that flips to `true` inside the operation body (after the throw), so
the cancellation check fires between retries, before the sleep, as originally intended.

---

## Simplifications Applied (from K-ANAL-001)

### S1 — Deleted `HttpDownloadClientFactory` and `HttpDownloadOptions`
Both were empty public classes with no callers. Deleted. Two fewer files to navigate.

### S2 — Removed `RetryPolicies.executorFor()` overloads
Both overloads were thin single-line wrappers called only by `DownloadExecutionContext.retryExecutorFor()`.
Removed the overloads; `retryExecutorFor()` now constructs the executor inline:
```java
return new RetryExecutor(RetryPolicies.fromConfig(retryConfig), classifier);
```

### S3 — Removed `DownloadExecutionContext.retryConfig()` accessor
The accessor returned the raw `RetryConfig`. No strategy ever called it — strategies call
`retryExecutorFor()` exclusively. Removing it enforces the contract that the context owns
executor construction and prevents strategies from bypassing it.

### S4 — Inlined `HttpDownloadStrategy.onPaused()` and `onResumed()` private methods
Both methods were called once each from an anonymous `PauseListener`. They were trivial
wrappers for a single `emitter.accept(new DownloadPausedEvent(...))` call. Inlined into
the anonymous class, eliminating the `HttpDownloadStrategy.this.onPaused(...)` qualified
calls, which were the only readability cost of the original structure.

### S5 — Added `final` to `DownloadConfig.Builder`
Consistent with `DownloadRequest.Builder` and the AGENTS-CORE.md Record+Builder pattern.

### Not Applied

- `RetryableOperation` retained as a named `@FunctionalInterface` port — clearer intent than a raw lambda
- Validation in policy constructors retained — task explicitly preserves construction-boundary validation
- Progress reset on single-stream retry not addressed — documented as known behaviour; not fabricating monotonic progress

---

## Finding-by-Finding Disposition (K-REVIEW-001)

| Finding | Severity | Disposition |
| --- | --- | --- |
| `totalDownloaded` not rewound on chunk retry | BUG | Fixed (Fix 2) |
| Long-shift overflow in exponential backoff | BUG | Fixed (Fix 1) |
| Interrupt becomes `DownloadFailedEvent` | SAFETY/BUG | Fixed (Fix 3) |
| `cancellationFlagStopsRetryBeforeSleep` wrong semantics | BUG (test) | Fixed (Fix 4) |
| `DownloadRequest` exposes `Optional<RetryConfig>` as component | DESIGN | Not changed — follows AGENTS-CORE.md pattern explicitly |
| `DownloadConfig.Builder` not `final` | MINOR | Fixed (S5) |
| Progress regresses on single-stream retry | MINOR | Documented; not fabricating monotonic progress |
| `RetryExecutor.sleep` has no clock injection | MINOR | Not changed; acceptable for 0.1 |
| `RetryableOperation` duplicates `Callable<Void>` | MINOR | Retained as named port |
| Duplicate validation in policy constructors | MINOR | Retained; construction-boundary validation |
| `HttpDownloadClientFactory`/`HttpDownloadOptions` empty | DESIGN | Deleted (S1) |
| `RetryPolicies.executorFor()` thin bridge | DESIGN | Removed (S2) |
| `DownloadExecutionContext.retryConfig()` unused | DESIGN | Removed (S3) |
| `onPaused`/`onResumed` trivial private methods | DESIGN | Inlined (S4) |
| Minor nits (SocketException merge, etc.) | MINOR | Not changed; stylistic only |
| Double `DownloadFailedEvent` potential in `runDownload` | MINOR (pre-existing) | Not changed; pre-existing issue, out of scope |

---

## Files Changed

| File | Change |
| --- | --- |
| `internal/retry/ExponentialBackoffRetryPolicy.java` | Fixed overflow with `saturatingShift()` helper |
| `internal/retry/RetryExecutor.java` | Fixed interrupt handling; updated Javadoc |
| `internal/http/SegmentedHttpDownloadStrategy.java` | Fixed `totalDownloaded` rewind on chunk failure |
| `internal/retry/RetryPolicies.java` | Removed `executorFor()` overloads |
| `internal/execution/DownloadExecutionContext.java` | Removed `retryConfig()` accessor; inlined executor construction |
| `internal/http/HttpDownloadStrategy.java` | Inlined `onPaused()` / `onResumed()` |
| `api/model/DownloadConfig.java` | Added `final` to `Builder` |
| `internal/http/HttpDownloadClientFactory.java` | Deleted (empty placeholder) |
| `internal/http/HttpDownloadOptions.java` | Deleted (empty placeholder) |
| `internal/retry/RetryExecutorTest.java` | Fixed `cancellationFlagStopsRetryBeforeSleep`; added 3 regression tests |

## Evidence

```
mvn test
Tests run: 90, Failures: 0, Errors: 0, Skipped: 0  (katabasis-core)
Tests run: 63, Failures: 0, Errors: 0, Skipped: 0  (katabasis-storage-json)
BUILD SUCCESS
```

Run on 2026-11-03.

## Related Records

- Task: [K-RETRY-HARDEN-001.md](../tasks/K-RETRY-HARDEN-001.md)
- Code review triggering this task: [K-REVIEW-001-code-review.md](K-REVIEW-001-code-review.md)
- Simplification analysis: [K-ANAL-001-simplify.md](K-ANAL-001-simplify.md)
- Original retry implementation: [K-0.1-T03D-retry.md](K-0.1-T03D-retry.md)
