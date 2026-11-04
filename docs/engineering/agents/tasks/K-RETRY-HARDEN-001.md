# K-RETRY-HARDEN-001 — Retry Hardening: Fix Review Bugs and Apply Simplifications

## Metadata

| Field | Value |
| --- | --- |
| Task ID | K-RETRY-HARDEN-001 |
| Type | Engineering / Reliability |
| Status | DONE |
| Date | 2026-11-03 |
| Depends On | K-0.1-T03D, K-REVIEW-001, K-ANAL-001 |

## Objective

Fix all confirmed correctness bugs from K-REVIEW-001 and apply the justified
simplifications from K-ANAL-001 that reduce dead weight without changing semantics.

## Authorized Scope

### Priority 1 — Correctness (required)

- Fix `ExponentialBackoffRetryPolicy` long-shift overflow with saturating arithmetic
- Fix `SegmentedHttpDownloadStrategy.doChunkAttempt` `totalDownloaded` double-counting on retry
- Fix `RetryExecutor.execute` interrupt handling: restore interrupt flag and rethrow `InterruptedException` immediately
- Regression tests for each defect

### Priority 2 — Test quality

- Fix `cancellationFlagStopsRetryBeforeSleep` to use `AtomicBoolean` and actually test the cancel-between-retries-before-sleep path
- Add end-to-end `exponentialBackoffDelayNeverNegativeAtLargeAttemptCounts` test (attempt ≥ 57)
- Add interrupt flag preservation test

### Priority 3 — Simplification (apply justified items from K-ANAL-001)

- Delete empty placeholder classes `HttpDownloadClientFactory` and `HttpDownloadOptions`
- Remove `RetryPolicies.executorFor()` overloads; inline construction in `DownloadExecutionContext.retryExecutorFor()`
- Remove `DownloadExecutionContext.retryConfig()` accessor (never called by strategies)
- Inline `HttpDownloadStrategy.onPaused()` and `onResumed()` private methods
- Add `final` to `DownloadConfig.Builder`

### Not in scope

- `katabasis-storage-json` module changes
- Durable recovery or persistent state
- New framework dependencies
- Monotonic progress (single-stream retry progress reset is documented as a known behaviour, not a defect)

## Related Records

- Depends on: K-0.1-T03D, K-REVIEW-001, K-ANAL-001
- Report: [K-RETRY-HARDEN-001-reconciliation.md](../reports/K-RETRY-HARDEN-001-reconciliation.md)
