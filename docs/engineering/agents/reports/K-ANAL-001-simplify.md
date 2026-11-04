# K-ANAL-001 — Simplification Analysis — Report

## Status

Complete

## Summary

The retry subsystem is well-structured and mostly earns its keep, but two concrete dead-weight classes exist: `HttpDownloadClientFactory` and `HttpDownloadOptions` are empty placeholder types that add noise without providing any value. Additionally, there is a subtle layering redundancy where `RetryPolicies.executorFor()` acts as a thin bridge factory when `DownloadExecutionContext.retryExecutorFor()` already provides the only real call-site. The `RetryableOperation` functional interface duplicates `Callable<Void>` with no additional contract. Validation logic is also duplicated symmetrically between `RetryConfig` factory methods and the `FixedDelayRetryPolicy` / `ExponentialBackoffRetryPolicy` constructors. Finally, the `handlePauseRequest` methods in `HttpDownloadStrategy` and `SegmentedHttpDownloadStrategy` are structurally identical but cannot currently be unified because pause-event deduplication differs between single-stream and multi-segment contexts.

---

## Findings

### Finding 1 — Empty placeholder classes `HttpDownloadClientFactory` and `HttpDownloadOptions`
**Location:** `internal/http/HttpDownloadClientFactory.java`, `internal/http/HttpDownloadOptions.java`
**Issue:** Both files contain only a Javadoc comment and an empty class body. They are referenced by no other class in the codebase. They produce zero compilation artefacts beyond their own class files and add cognitive overhead when navigating the `internal/http` package.
**Suggestion:** Delete both files. If a client factory or options type is needed in the future, create them at that time.
**Risk:** Low
**Benefit:** Two fewer files to read and maintain; reduces false impression of functionality in the HTTP package.

---

### Finding 2 — `RetryableOperation` duplicates `java.util.concurrent.Callable<Void>`
**Location:** `internal/retry/RetryableOperation.java`
**Issue:** The interface declares a single `void execute() throws Exception` method. This is functionally equivalent to `Callable<Void>` minus its return value, or more precisely to `java.util.concurrent.Callable` where the caller ignores the return. `RetryExecutor.execute()` could accept `Callable<Void>` or a plain `ThrowingRunnable` expressed as a lambda. The interface contributes no additional contract — no marker, no default method, no documentation distinction beyond "one attempt."
**Suggestion:** Replace `RetryableOperation` with a package-private `@FunctionalInterface` alias `() -> void throws Exception` expressed directly as a lambda at the two call sites (`HttpDownloadStrategy` line 83, `SegmentedHttpDownloadStrategy` line 168) by changing `RetryExecutor.execute(RetryableOperation, BooleanSupplier)` to `RetryExecutor.execute(Callable<Void>, BooleanSupplier)` and dropping the `RetryableOperation.java` file. If the `Callable<Void>` idiom is considered unreadable, a one-line `@FunctionalInterface interface RetryAttempt { void run() throws Exception; }` as a private inner type of `RetryExecutor` removes the separate file while keeping the name.
**Risk:** Low
**Benefit:** One fewer file; no loss of expressiveness; call sites already use lambdas.

---

### Finding 3 — Duplicated validation logic between `RetryConfig` and policy constructors
**Location:** `api/model/RetryConfig.java` lines 80–87, 118–130; `internal/retry/FixedDelayRetryPolicy.java` lines 21–27; `internal/retry/ExponentialBackoffRetryPolicy.java` lines 38–53
**Issue:** Both `RetryConfig.fixedDelay()` and `FixedDelayRetryPolicy` validate `maxAttempts > 0` and `delay` is positive. Both `RetryConfig.exponentialBackoff()` and `ExponentialBackoffRetryPolicy` validate `maxAttempts`, `initialDelay`, `maxDelay >= initialDelay`, and `jitterFactor`. Because `RetryPolicies.fromConfig()` is the only path from a `RetryConfig` to a policy instance, the policy constructors will never see an invalid `RetryConfig`. The double-validation is defensive but adds dead code paths.
**Suggestion:** Since `RetryConfig` already validates all fields in its factory methods and compact constructor, change `FixedDelayRetryPolicy` and `ExponentialBackoffRetryPolicy` constructors to use `Objects.requireNonNull` only (no range checks). This clearly expresses that the config layer owns validation and the policy layer trusts it. Add a comment in each policy constructor: `// Preconditions already enforced by RetryConfig factory methods.` Optionally add a package-private `Preconditions.requirePositive(Duration, String)` helper to avoid the `isZero() || isNegative()` idiom appearing in multiple places.
**Risk:** Low
**Benefit:** Removes ~15 lines of duplicated guard logic; makes the trust boundary between API and internal layer explicit.

---

### Finding 4 — `RetryPolicies.executorFor()` is a thin bridge with one call site
**Location:** `internal/retry/RetryPolicies.java` lines 61–79; `internal/execution/DownloadExecutionContext.java` line 72
**Issue:** `RetryPolicies` exposes two `executorFor()` overloads that each create a `RetryExecutor`. The only call site is `DownloadExecutionContext.retryExecutorFor()`. The bridge adds an indirection: caller → `DownloadExecutionContext.retryExecutorFor()` → `RetryPolicies.executorFor()` → `new RetryExecutor(fromConfig(...), classifier)`. The intermediate layer earns nothing because `DownloadExecutionContext` could call `new RetryExecutor(RetryPolicies.fromConfig(retryConfig), classifier)` directly in one line.
**Suggestion:** Remove both `executorFor()` overloads from `RetryPolicies`. Inline the two-step construction in `DownloadExecutionContext.retryExecutorFor()`:
```java
public RetryExecutor retryExecutorFor(final FailureClassifier classifier) {
    return new RetryExecutor(RetryPolicies.fromConfig(retryConfig), classifier);
}
```
**Risk:** Low
**Benefit:** Removes four methods (two overloads × same body pattern); reduces call depth by one frame; `RetryPolicies` shrinks to a single responsibility (config → policy, not config → executor).

---

### Finding 5 — `DownloadExecutionContext.retryConfig()` accessor is never called by strategies
**Location:** `internal/execution/DownloadExecutionContext.java` line 57
**Issue:** `retryConfig()` is a public accessor that returns the raw `RetryConfig`. Neither `HttpDownloadStrategy` nor `SegmentedHttpDownloadStrategy` calls it; they call `retryExecutorFor()` directly. The only consumer is `DownloadExecutionContext` itself internally. Exposing the raw config widens the public surface of the context unnecessarily — a strategy could bypass `retryExecutorFor()` and construct its own executor differently.
**Suggestion:** Remove the `retryConfig()` accessor. The field `retryConfig` is already `private final`; strategies should only obtain a configured executor via `retryExecutorFor()`.
**Risk:** Low
**Benefit:** Narrows the context API surface; enforces the contract that the execution context owns executor construction.

---

### Finding 6 — `HttpDownloadStrategy.onPaused()` and `onResumed()` are trivial private methods
**Location:** `internal/http/HttpDownloadStrategy.java` lines 204–230
**Issue:** `onPaused()` and `onResumed()` each wrap a single `emitter.accept(new DownloadPausedEvent(...))` or `emitter.accept(new DownloadResumedEvent(...))` call. They are only called once each from `handlePauseRequest()` via the `PauseListener` anonymous class. The methods do not reduce duplication (the segmented strategy cannot share them) and their sole purpose is to give a name to two constructor calls.
**Suggestion:** Inline both methods into the anonymous `PauseListener` in `handlePauseRequest()`. The `onPaused()` body is four lines including the event construction; `onResumed()` is similar. Removing the methods eliminates the need for `HttpDownloadStrategy.this.onPaused(...)` qualified calls, which are themselves a readability impediment.
**Risk:** Low
**Benefit:** Removes eight lines of method signatures/braces; eliminating the outer-class qualification makes the pause listener anonymous class self-contained.

---

### Finding 7 — `HttpDownloadClientFactory` is a non-final, non-abstract public class
**Location:** `internal/http/HttpDownloadClientFactory.java`
**Issue:** (Reinforcing Finding 1.) Beyond being empty, the class is declared `public class` — not `public final class`. This means it can be subclassed and instantiated, adding an accidental extension point in internal code. This is a secondary concern that disappears entirely when the file is deleted.
**Suggestion:** Delete (see Finding 1).
**Risk:** Low
**Benefit:** Eliminates accidental subclassability in internal package.

---

## Findings Not Pursued

**`NoRetryPolicy` as a separate class vs. a lambda constant.** `NoRetryPolicy` could be replaced by `(failedAttempts, cause) -> RetryDecision.ABORT` stored as a constant in `RetryPolicies`. This was not pursued because `NoRetryPolicy.INSTANCE` is a named singleton with a clear `instanceof` identity and the named type improves log readability in future diagnostics. The class is small enough that the overhead is negligible.

**Merging `HttpDownloadStrategy` and `SegmentedHttpDownloadStrategy` into `HttpDownloadOrchestrator`.** The two strategies share buffer-size fields and the `handlePauseRequest` pattern. Merging them would reduce class count but would create a 300+ line class with two distinct execution paths and unrelated concurrency models (single-stream vs. `StructuredTaskScope`). The current separation keeps each strategy focused and independently testable.

**`FailureClassifier` as a `@FunctionalInterface`.** It already is one. No change needed.

**`RetryDecision.retryAfter()` static factory.** The record could be constructed directly, but the factory name reads more clearly than `new RetryDecision(true, delay)` and costs nothing.

**Validation duplication in `RetryConfig` compact constructor vs. factory methods.** The compact constructor validates the raw fields; the factory methods validate higher-level invariants (e.g. `delay.isZero()`). These serve different purposes and should remain separate.

---

## Related Records

- Codebase reviewed: `katabasis-core` main source, revision at branch `main` (2026-11-03)
- Files read: all files under `internal/retry/`, `internal/http/`, `internal/execution/DownloadExecutionContext.java`, `api/model/RetryConfig.java`, `api/manager/DownloadManagers.java`, `internal/util/Preconditions.java`
