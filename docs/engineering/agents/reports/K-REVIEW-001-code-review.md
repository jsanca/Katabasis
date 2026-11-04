# K-REVIEW-001 — Code Review: K-0.1-T03D Retry System — Report

## Status

Complete

## Reviewer

Opus 4 — high effort

## Scope

Full review of the files introduced and modified by K-0.1-T03D (the retry policy and execution engine): `api.model.RetryConfig`, all classes under `internal.retry.*`, `internal.http.HttpFailureClassifier`, the integration points in `HttpDownloadStrategy` / `SegmentedHttpDownloadStrategy` / `DownloadExecutionContext` / `DownloadManagers`, the `DownloadRequest` override field, and the two retry test classes.

## Overall Assessment

The feature is cleanly factored: the `RetryPolicy` / `FailureClassifier` / `RetryExecutor` split is tidy, `RetryConfig` is a well-validated data record with convenient factories, and the integration into both HTTP strategies looks correct at first glance. Tests cover the happy paths and the major policy modes. The main concerns are (a) a real bug in `SegmentedHttpDownloadStrategy` where `totalDownloaded` is not reset between chunk retries, (b) a latent long-shift overflow in `ExponentialBackoffRetryPolicy`, (c) interrupt handling that converts an interrupt during retry sleep into a user-facing failure event, and (d) a few API-shape issues that violate project conventions.

## Issues

### `totalDownloaded` is not rewound when a segmented chunk retries, letting progress exceed contentLength
**Severity:** BUG
**Location:** `SegmentedHttpDownloadStrategy.doChunkAttempt` (lines 182–233) vs. the `retryExecutor.execute(...)` wrapper in `downloadChunk` (lines 156–180).
**Finding:** Inside `doChunkAttempt`, `position` is a local that correctly re-initialises to `chunk.start()` on every attempt (so the file region is re-overwritten), and the per-attempt local `buffer` is fresh. However, `totalDownloaded` is the *shared* `AtomicLong` from the caller, and `totalDownloaded.addAndGet(bytesRead)` is called **inside the read loop on every attempt**. If an attempt reads, say, 1 MB of a 5 MB chunk and then fails mid-stream (transient I/O error), on retry the file bytes are re-written at the same positions but `totalDownloaded` has already accumulated the 1 MB from the aborted attempt. After a successful retry it will have accumulated `1 MB + 5 MB = 6 MB` for a 5 MB chunk. Across the whole file the progress counter can exceed `contentLength`, producing `DownloadProgressEvent` with `downloadedBytes > totalBytes`, and inflating the final summed "downloaded" by whatever was transferred before the failure. The `HttpDownloadSupport.onComplete` call still emits `contentLength` correctly, so the completion event masks it, but the progress stream is wrong whenever any chunk retries.
**Recommendation:** Track per-attempt bytes locally (`long chunkDownloaded = 0`) and only publish deltas on *successful* flush, or rewind the shared counter on failure. Simplest fix: inside `doChunkAttempt`, compute `long attemptDelta` locally, and at the catch site (or just before returning success) atomically reconcile via `totalDownloaded.addAndGet(attemptDelta)`. Alternatively, on retry entry subtract the previously-added-but-later-aborted bytes before re-entering the read loop. Add a focused test with an InputStream that throws mid-stream and assert that `max(progress.downloaded) <= contentLength`.

### Long-shift overflow in exponential backoff when `failedAttempts` is large
**Severity:** BUG
**Location:** `ExponentialBackoffRetryPolicy.decide` (lines 66–74).
**Finding:** The delay is computed as `Math.min(initMs << shift, maxMs)` where `shift = Math.min(failedAttempts, 62)`. For non-trivial `initMs` (e.g., 100 ms) `100L << 62` overflows and wraps to a negative or zero long. `Math.min(negative, maxMs)` then returns the negative value, so `baseMs` is negative, `delayMs` becomes negative, and `Duration.ofMillis(delayMs)` makes it into `RetryDecision.retryAfter(...)` where the compact constructor of `RetryDecision` throws `IllegalArgumentException("delay must be >= 0 ...")`. The `maxDelay` cap doesn't rescue you because the overflow happens *before* the `min`. This is latent (you need many attempts or a large `initialDelay`) but it's a real correctness bug, not just theoretical: a config like `exponentialBackoff(20, Duration.ofMillis(100), Duration.ofSeconds(60))` reaches overflow around attempt 57+ due to the `62` cap being too permissive.
**Recommendation:** Compute the capped multiplier in a way that saturates, e.g. check if `shift >= Long.numberOfLeadingZeros(initMs)` and short-circuit to `maxMs`, or use `Math.multiplyExact` inside a try/catch that returns `maxMs` on overflow, or simply iterate `baseMs = min(baseMs * 2, maxMs)` from `initMs`. Add a test that calls `decide(60, ...)` with `initialDelay=100ms` and verifies the delay equals `maxDelay` and is never negative.

### Interrupt during retry sleep becomes a `DownloadFailedEvent`
**Severity:** SAFETY (arguably BUG against the stated invariant)
**Location:** `RetryExecutor.execute` (lines 48–79), `HttpDownloadStrategy.download` (lines 85–89), `SegmentedHttpDownloadStrategy.download` catch block (lines 147–153).
**Finding:** The task invariant states "transient failures must not emit `DownloadFailedEvent` — only terminal failure after retry exhaustion should." When the executing virtual thread is interrupted during `Thread.sleep(ms)` (for example at JVM shutdown, or if an upstream caller interrupts the worker), `InterruptedException` propagates out of `RetryExecutor.execute` as a plain `Exception`, is caught by the strategies' outer `catch (Exception e)`, and `HttpDownloadSupport.onError` emits a `DownloadFailedEvent`. For a legitimate cancellation driven through the `CancellationToken`/`DownloadCancelledException` path this is fine, but interrupt-based shutdown (which Loom frameworks commonly use) is indistinguishable here and will produce a user-visible failure. Also, `HttpFailureClassifier.isTransient(InterruptedException)` returns `false`, and the current thread's interrupt flag is not restored before the exception escapes the executor.
**Recommendation:** In `RetryExecutor.execute`, catch `InterruptedException` explicitly: set `Thread.currentThread().interrupt()` and rethrow without consulting the classifier/policy. In the strategy outer catch blocks, treat `InterruptedException` as cancellation (emit `DownloadCancelledEvent` with current progress) rather than failure, or propagate it so the orchestrator in `DownloadManagers.runDownload` can decide. At minimum, add an explicit interrupt test to `RetryExecutorTest`.

### `cancellationFlagStopsRetryBeforeSleep` test asserts the wrong semantics vs. code
**Severity:** BUG (documentation/behaviour mismatch — pick one)
**Location:** `RetryExecutor.execute` line 60 vs. the test at `RetryExecutorTest.cancellationFlagStopsRetryBeforeSleep` (lines 64–77) and the Javadoc at `RetryExecutor.execute` lines 42–46 ("cancelled: tested before each sleep; if true the last exception is re-thrown immediately without sleeping").
**Finding:** The Javadoc says the cancel flag is "tested before each sleep." The code tests it immediately after any thrown exception — *before* classifier and policy are consulted. That is a stricter guarantee than documented (good), but also means a classifier that would say "permanent" and a policy that would say "ABORT" are both bypassed when cancelled is true. Not wrong, just under-documented. More importantly, the test wraps the whole thing in `() -> true` ("always cancelled") and asserts `calls == 1`. Because the cancel check is *inside the catch*, the very first call still executes before any cancel check. That is what the test asserts, but the test name implies we are measuring the "before sleep" path, which is never reached with `maxAttempts=5` and `delay=60s` when the first throw already short-circuits. The assertion is right by accident.
**Recommendation:** Tighten the Javadoc to say "tested after each failure, before consulting the retry policy or sleeping." Add a separate test where cancellation flips to `true` only on the second invocation (use an `AtomicBoolean`) to actually exercise the "cancel after N failed attempts, before sleep" path.

### `DownloadRequest` exposes `Optional<RetryConfig>` as a record component
**Severity:** DESIGN
**Location:** `DownloadRequest.java` line 25.
**Finding:** Effective Java Item 55 and the project's own convention (see record example in `AGENTS-CORE.md` — "Optional fields use `String` (null = absent) in the builder; `build()` wraps with `Optional.ofNullable`" — that example illustrates returning Optional from the accessor is acceptable, but the record component itself being `Optional<T>` serialises Optional, breaks equality semantics across null-vs-empty, and is generally discouraged). The record now has a mandatory `Optional` component and callers must construct `Optional.of(...)`/`Optional.empty()` directly if they bypass the builder. The equivalent "per-request override" field on `DownloadConfig` is also handled without Optional (it uses `RetryConfig.noRetry()` as a sentinel). The two models disagree on how "absent" is spelled.
**Recommendation:** Either (a) drop Optional from the record component and use `RetryConfig retryConfig` with a nullable value, exposing `Optional<RetryConfig> retryConfig()` as an accessor method defined in the record body, or (b) keep Optional but at least mark this convention explicitly in `AGENTS-CORE.md` so other records don't diverge. Option (a) is cleaner. The builder already stores a nullable `RetryConfig` internally, so only the record shape needs to change.

### `DownloadConfig.Builder` is not `final`; inconsistent with `DownloadRequest.Builder`
**Severity:** MINOR (DESIGN)
**Location:** `DownloadConfig.java` line 77 (`public static class Builder`) vs. `DownloadRequest.Builder` which is `public static final class`.
**Finding:** The Record+Builder pattern documented in `AGENTS-CORE.md` shows `public static final class Builder`. `DownloadConfig.Builder` is missing `final`.
**Recommendation:** Add `final` for consistency and to prevent accidental subclassing.

### Progress emitted on retried single-stream download jumps backward
**Severity:** MINOR (DESIGN / behaviour)
**Location:** `HttpDownloadStrategy.doDownloadAttempt` (lines 92–122).
**Finding:** `AtomicLong downloadedBytes` is local to each attempt and `Files.newOutputStream(info.targetPath())` truncates the file on every attempt. On a transient failure partway through a stream, the retry restarts progress at 0. Listeners that track monotonic progress will observe a regression (e.g., 60% → 0% → 100%). For K-0.1-T03D this is in scope: a transient failure that *does* retry should not visibly roll back progress without any signal, since no `DownloadFailedEvent` is emitted either. The user just sees progress collapse.
**Recommendation:** Either (a) emit a dedicated "retry" progress reset event (future roadmap), or (b) have the strategy start progress for the attempt from `0` but publish `max(previousHigh, newBytes)` to keep the external counter monotonic, or (c) at minimum log at INFO when a retry resets the stream. A clear design decision here should be recorded. No fix required for this task if listeners are tolerant; flagging for roadmap.

### `RetryExecutor.sleep` relies on `Thread.sleep`; no clock injection for tests
**Severity:** MINOR
**Location:** `RetryExecutor.sleep` (lines 81–86).
**Finding:** All timing tests use real `Thread.sleep` with millisecond delays. The `exponentialBackoffRetriesUpToMaxAttempts` test uses delays of 1–10 ms, fast enough to be fine, but the pattern won't scale and makes it hard to verify a specific backoff curve without flakiness. The class is final with no seam for a Clock or Sleeper.
**Recommendation:** Extract a package-private `Sleeper` interface (default `Thread::sleep`) injected via constructor, so a test can capture the sequence of requested delays without actually sleeping. Optional for this task.

### `RetryableOperation.execute()` throws raw `Exception`
**Severity:** MINOR
**Location:** `RetryableOperation.java`.
**Finding:** Declaring `throws Exception` on a functional interface is overly broad and forces every callsite inside strategies to handle a generic checked exception. Protocol-level operations throw `IOException` and `InterruptedException` concretely; everything else is runtime.
**Recommendation:** Narrow to `throws IOException, InterruptedException`, or introduce a protocol-specific checked exception. Low priority since it's purely internal.

### Minor nits
**Severity:** MINOR
- `HttpFailureClassifier` has two sequential `if` statements for `SocketException` (line 43) that could be merged into the first `||` chain; stylistic only.
- `NoRetryPolicy.decide(int failedAttempts, Throwable cause)` always returns `ABORT`; both parameters unused. Consider `@SuppressWarnings` or just leave as-is (interface conformance).
- `RetryPolicies.fromConfig(RetryConfig)` always allocates a new `java.util.Random` even for `NONE` and `FIXED_DELAY` which don't use it. Trivial waste.
- `RetryExecutor` log message `"Retry policy exhausted after {} attempt(s) — giving up"` uses `failedAttempts + 1` which is correct arithmetic but for the no-retry case ("exhausted after 1 attempt(s)") the word "exhausted" reads awkwardly when no retry was ever allowed. Consider branching the message.
- `DownloadManagers.DefaultDownloadManager.runDownload` catches `Exception` and emits `DownloadFailedEvent` — but by the time control reaches here, the strategies have *already* emitted their own `DownloadFailedEvent` via `HttpDownloadSupport.onError` in their own catch blocks. Any exception re-thrown from the strategy would therefore produce a double failure event. Not introduced by K-0.1-T03D (pre-existing), but now more relevant because retry exhaustion paths widen the surface for exceptions escaping. Verify no strategy currently rethrows after emitting, and consider adding an assertion.

## What Works Well

- `RetryConfig` validation is thorough: null checks, bounds on jitter, `maxDelay >= initialDelay`, and consistent semantics between the compact constructor and the static factories.
- `RetryConfig.Kind` + factory discriminator pattern keeps the public API free of internal policy types and respects the JPMS `api.*` boundary — internal packages are not leaked.
- `RetryDecision` is immutable, validated, with a shared `ABORT` sentinel.
- `DownloadCancelledException` is explicitly singled out and short-circuited in `RetryExecutor` — correct semantics for the "cancellation is terminal" invariant.
- `HttpFailureClassifier` correctly separates 4xx (permanent) from 429 + 5xx (transient), and the 501 carve-out matches HTTP semantics.
- The per-request override pattern (`DownloadRequest.retryConfig()` overrides `DownloadConfig.retryConfig()`) is cleanly implemented in `DownloadManagers.download`.
- `DownloadExecutionContext.retryExecutorFor(classifier)` is a nice seam: the context owns the policy, protocol strategies supply the classifier.
- Tests cover: no-retry, cancellation short-circuit, fixed delay, eventual success, permanent-failure classification, exponential cap, HTTP classifier matrix, factory wiring, and request-level override.

## Test Coverage Assessment

**Covered well:**
- Each `RetryPolicy` kind through `RetryPolicies.fromConfig`.
- `RetryExecutor` with mock classifiers (`e -> true` / `e -> false`).
- `DownloadCancelledException` short-circuit.
- `HttpFailureClassifier` for the four most interesting status-code bands.
- `DownloadRequest` builder behaviour for the retry override.

**Missing / risky:**
- No test exercises **retry integration end-to-end** inside `HttpDownloadStrategy` or `SegmentedHttpDownloadStrategy`. The key invariant "transient failures do NOT emit `DownloadFailedEvent`, only terminal exhaustion does" is not verified by any test. A fake `HttpClient` that fails once with a transient error and then succeeds, plus a listener asserting zero `DownloadFailedEvent`, is the single most important missing test.
- No test for the `totalDownloaded` rewind bug in segmented retries (see BUG above).
- No test for the overflow case in `ExponentialBackoffRetryPolicy` at large `failedAttempts`.
- No test for interrupt semantics (`Thread.interrupt()` during `sleep`).
- No test that asserts `DownloadProgressEvent.downloaded <= total` across retries.
- `cancellationFlagStopsRetryBeforeSleep` doesn't actually exercise the "cancel after transient failure, before sleep" path because the cancel supplier returns `true` from the first invocation; the first throw short-circuits before any sleep would even be scheduled.
- No test that mixes transient and permanent failures in the same operation (fail transient twice, then permanent).
- No test that verifies `Random` seeding through `RetryPolicies.fromConfig(config, random)` actually produces deterministic delays.

## Verdict

REQUEST CHANGES

The segmented retry `totalDownloaded` overflow is a real correctness bug that will emit malformed progress events as soon as any chunk retries; combined with the exponential-backoff long-shift overflow and the interrupt-becomes-failure path, these need fixes (and tests) before the retry feature can be considered trustworthy. The surrounding structure is solid and most of the fixes are localised.

## Related Records

- Task reviewed: K-0.1-T03D
- Report: docs/engineering/agents/reports/K-0.1-T03D-retry.md
