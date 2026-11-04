# K-STORAGE-REVIEW-001 — Storage Concurrency & Durability Review — Report

## Status
Complete

## Reviewer
Claude Opus 4 — adversarial review

## Scope

Read-only engineering review of `katabasis-storage-json` and its contracts in `katabasis-core`. Targets: the public port (`DownloadStateStore`, `DownloadSnapshot`, `SegmentSnapshot`), the storage API surface (`StorageKey`, `VersionedCodec`, `StorageDocument`, `CheckpointCoordinator`, `DownloadStateStores`, `CodecException`), internal implementations (`FileDownloadStateStore`, `JsonEnvelopeCodec`, `MigrationRegistry`, `Migration`, `UriAdapter`, `PathAdapter`), the JPMS `module-info.java`, the test suite, and ADR-009. Six review areas were investigated: concurrency and lock lifecycle, revision consistency, filesystem atomicity and durability, corruption handling, codec and boundary integrity, and the `CheckpointCoordinator`.

## Executive Assessment

The module is well-structured, legible, and consistent with the house style used elsewhere in Katabasis (interface + factory, records, JPMS encapsulation, pure migrations). The envelope format is cleanly specified and the codec validates it defensively. The test suite is unusually thorough for a first iteration (53 tests across three classes), including concurrent-save fuzzing and migration round-trips.

That said, this review finds **two concurrency defects that break documented invariants, one path-traversal defect on the read/delete paths, one documented-but-undelivered architectural claim in ADR-009, and several missing durability guarantees that the ADR does acknowledge in part**. The highest-impact defect is the `FileDownloadStateStore` lock-map cleanup race (STOR-001): `delete()` removes the lock while still holding it, which allows two concurrent writers for the same download ID to execute under different lock instances — breaking the "per-download serialization" contract in the Javadoc. The second is in `CheckpointCoordinator` (STOR-006): a scheduled flush can race with a terminal event and resurrect a manifest after it has been deleted, producing a ghost checkpoint that `findRecoverable()` will return at the next startup.

The "revision" field in the envelope is described in `StorageDocument` as "a monotonic counter managed by the storage layer for **optimistic concurrency**", but the implementation does nothing of the sort — it is a free-running read-then-increment counter inside a mutex, which gives serializability but not optimistic concurrency control. This is a contract/implementation gap, not a runtime bug, and should either be downgraded in the Javadoc or implemented properly.

Finally, ADR-009 asserts "It does **not** depend on `katabasis-core`", but `module-info.java` declares `requires jsanca.katabasis.core` and both the factory and the file store import `DownloadStateStore` and `DownloadSnapshot` from core. The actual dependency direction (storage → core) is sensible, but the ADR is now stale and should be amended.

What works well: the envelope schema validation is explicit and rejects unsupported versions, future versions, and malformed JSON; `StorageKey` uses a safe-character regex; migrations are pure; no JSON-B types leak across the port boundary; and the concurrent-save tests actually exercise the lock path. The findings below are ordered by severity, not by review area.

## Findings

### STOR-001 — Lock map cleanup allows concurrent writers for the same ID
**Classification:** Confirmed defect
**Severity:** High
**Area:** 1 (Concurrency and Lock Lifecycle)
**Location:** `katabasis-storage-json/src/main/java/jsanca/katabasis/storage/json/internal/store/FileDownloadStateStore.java:148-160`, and `:164-166`
**Contract/Invariant:** The Javadoc at lines 33-35 states: *"Per-download `ReentrantLock`s serialize concurrent saves and deletes for the same download ID."* `DownloadStateStore.save`'s contract at `DownloadStateStore.java:25-26` repeats: *"Concurrent saves for the same ID are serialized by the implementation."*
**Failure Scenario:**
1. Thread A calls `save("abc")`; `lockFor("abc")` returns lock `L1`; A acquires `L1`.
2. Thread B calls `delete("abc")`; `lockFor("abc")` returns the same `L1`; B blocks on `L1.lock()`.
3. A finishes, unlocks. B acquires `L1`, deletes the file, then executes `locks.remove("abc")` (line 154) **while still holding `L1`**. B unlocks.
4. Thread C calls `save("abc")` after step 3's `remove`; `locks.computeIfAbsent(...)` returns a **new** lock `L2` (because the map no longer contains the entry). C acquires `L2`.
5. Thread D calls `save("abc")` concurrently with step 4 but had already obtained a reference to `L1` via `lockFor` *before* B's `remove` in step 3 (e.g., D entered `lockFor` just before B's `remove` ran and `computeIfAbsent` returned the still-present `L1`). D acquires `L1`.
6. C (holding `L2`) and D (holding `L1`) now execute `atomicWrite` on the same target path concurrently.

Two concurrent `Files.write(tmp, bytes)` and `Files.move(...)` operations racing over the same target can produce: a lost update (last-writer-wins without revision monotonicity), a leftover `.tmp` file, or (on Windows NTFS) an `AccessDeniedException` because the rename sees the file open.

The window is small but reachable under terminal-event + resume-request bursts (e.g., a user cancels, then immediately re-submits the same ID; the second save races with the first).
**Evidence:**
```java
// FileDownloadStateStore.java:148-160
public void delete(final String downloadId) {
    Objects.requireNonNull(downloadId, "downloadId must not be null");
    final ReentrantLock lock = lockFor(downloadId);
    lock.lock();
    try {
        Files.deleteIfExists(resolve(downloadId));
        locks.remove(downloadId);        // <-- removed while held
    } catch (final IOException e) {
        throw new UncheckedIOException("Failed to delete snapshot for: " + downloadId, e);
    } finally {
        lock.unlock();
    }
}
```
There is no "poison" or "tombstone" check on re-entry via `lockFor`; `computeIfAbsent` simply manufactures a fresh lock the moment the entry is gone.
**Recommended Fix:** Either (a) do **not** remove from the map inside `delete()` and accept unbounded growth (fine for this workload — one entry per active download ID), (b) use `locks.computeIfPresent` / a `MapEntryLock` pattern that validates the lock is still the authoritative one for the key before use, or (c) remove under a global map lock after checking that no other thread acquired a reference. Option (a) is the minimal fix:

```java
try {
    Files.deleteIfExists(resolve(downloadId));
    // Do not remove from `locks`; subsequent saves reuse the same lock.
} finally {
    lock.unlock();
}
```

If unbounded growth is a concern, gate removal on the `ReentrantLock.getQueueLength() == 0 && !isLocked()` check performed under a dedicated map-wide lock, or move to a `Striped<Lock>` from Guava. **Preferred: option (a). The map grows at most by the active download-ID set, which is already memory-bounded.**
**Regression Test:** Stress test: N threads alternating `save(id)` and `delete(id)` for a small set of IDs; assert no `IOException`, no leftover `.tmp` files, and `findById` either returns Optional.empty or a valid document. Specifically interleave `save` after `delete` has removed the map entry; the race is not visible to the concurrent-save-only fuzz test currently in the suite (which never deletes).
**Confidence:** High

---

### STOR-002 — Path traversal via findById / delete bypasses StorageKey validation
**Classification:** Confirmed defect
**Severity:** High
**Area:** 5 (Codec and Boundary Integrity) — materializes in the file store
**Location:** `FileDownloadStateStore.java:112-124` (`findById`), `:148-160` (`delete`), `:168-170` (`resolve`)
**Contract/Invariant:** `StorageKey` Javadoc (lines 11-14) states: *"Both components are restricted to `[a-zA-Z0-9][a-zA-Z0-9_-]*` to prevent **path traversal**, injection, or ambiguous encoding in storage backends."* The `save` path respects this (it calls `storageKey(id)` at line 99, which constructs a validated `StorageKey` and throws on bad input). But `findById`, `findRecoverable` (via directory listing it is safe), and `delete` do **not** construct a `StorageKey`; they call `resolve(downloadId)` directly, which performs `directory.resolve(downloadId + ".json")`.
**Failure Scenario:** A caller or upstream bug passes a `downloadId` of `"../../etc/passwd"`:
- `store.findById("../../etc/passwd")` → reads `directory.resolve("../../etc/passwd.json")` → traverses outside the storage directory.
- `store.delete("../../../tmp/victim")` → attempts `Files.deleteIfExists` outside the storage directory.
`DownloadStateStore` has no documented restriction on the `downloadId` string; the burden is entirely on the implementation.
**Evidence:**
```java
// FileDownloadStateStore.java:112-117
public Optional<DownloadSnapshot> findById(final String downloadId) {
    Objects.requireNonNull(downloadId, "downloadId must not be null");
    final Path file = resolve(downloadId);     // no validation
    if (!Files.exists(file)) { return Optional.empty(); }
    ...
}

// :168-170
private Path resolve(final String downloadId) {
    return directory.resolve(downloadId + EXTENSION);
}
```
Only `save` validates (indirectly, via `storageKey(id)` throwing on characters outside the safe pattern — `StorageKey.java:18-30`).
**Recommended Fix:** Validate the `downloadId` against the `StorageKey` safe pattern on every entry point, or normalise and assert containment under `directory`:
```java
private Path resolve(final String downloadId) {
    // Equivalent validation to StorageKey, independent of codec call site.
    if (!SAFE_ID.matcher(downloadId).matches()) {
        throw new IllegalArgumentException("Invalid downloadId: " + downloadId);
    }
    return directory.resolve(downloadId + EXTENSION);
}
```
Centralising validation in `resolve` fixes all three code paths at once.
**Regression Test:** `findById("../evil")`, `delete("../evil")`, and `save` on an invalid ID should all throw `IllegalArgumentException`; `findById("ok-id")` should still work.
**Confidence:** High

---

### STOR-003 — CheckpointCoordinator scheduled flush can resurrect a deleted manifest
**Classification:** Confirmed defect
**Severity:** High
**Area:** 6 (CheckpointCoordinator)
**Location:** `CheckpointCoordinator.java:169-178` (`handleCompleted`, `handleCancelled`), `:194-198` (`flushPending`)
**Contract/Invariant:** Class Javadoc at lines 42-45: *"On `DownloadCompletedEvent` or `DownloadCancelledEvent` the stored snapshot is **deleted**."* Implicitly, after a terminal deletion the manifest should stay deleted — otherwise `findRecoverable()` will return a ghost snapshot at the next startup.
**Failure Scenario:**
1. Scheduler thread enters `flushPending`. It evaluates `pending.values()` and obtains an iterator that includes the snapshot for `"abc"`.
2. Event thread (JavaFX/caller) publishes `DownloadCompletedEvent` for `"abc"`. `handleCompleted` runs on the event thread: `pending.remove("abc")`, then `deleteQuietly("abc")` — file on disk is deleted.
3. Scheduler thread continues iteration, picks the already-dereferenced snapshot for `"abc"` (the iterator weakly reflects the removal in `ConcurrentHashMap` — `pending.values()` returns a weakly-consistent view), and calls `saveQuietly(snap)` → manifest recreated on disk.

The window exists because `pending.values()` is a weakly-consistent view: an element observed by the iterator at time X and removed from the map at time X+ε may still be yielded and saved. Per-ID locks in the store do **not** serialize the sequence "delete then save": the event thread's `delete` and the scheduler's later `save` both acquire the lock cleanly, but the order is "delete, then save" — the save wins.
**Evidence:**
```java
// :169-172
private void handleCompleted(final DownloadCompletedEvent e) {
    pending.remove(e.info().downloadId());
    deleteQuietly(e.info().downloadId());
}

// :194-198
private void flushPending() {
    for (final DownloadSnapshot snap : pending.values()) {
        saveQuietly(snap);                       // no re-check of pending.containsKey
    }
}
```
`ConcurrentHashMap.values()` returns a weakly-consistent iterator (Javadoc). The iterator captured before the terminal event can still deliver the removed entry.
**Recommended Fix:** Re-check inside the flush loop that the ID is still pending, under the same per-ID lock, before saving. For example:
```java
pending.forEach((id, snap) -> {
    // compute-and-save atomically; if deleted, skip
    pending.computeIfPresent(id, (k, s) -> {
        saveQuietly(s);
        return s;              // keep in pending
    });
});
```
Then make `handleCompleted` / `handleCancelled` do the deletion under the same `compute*` idiom:
```java
pending.compute(id, (k, s) -> {
    deleteQuietly(id);
    return null;               // removes
});
```
`ConcurrentHashMap.compute` serializes against other `compute`/`computeIfPresent` calls for the same key — the save and the delete can no longer interleave in a resurrection order. (The store-level lock still serializes the actual file I/O.)
**Regression Test:** Fire 100 `(Started, Progress, Completed)` sequences in rapid succession on a coordinator with a 1 ms flush interval against a real `FileDownloadStateStore`; assert the storage directory is empty at the end. Today this test will fail intermittently.
**Confidence:** High

---

### STOR-004 — ADR-009 architectural claim contradicts actual dependency direction
**Classification:** Confirmed defect (documentation / design record)
**Severity:** High (architectural truthfulness)
**Area:** 5 (Codec and Boundary Integrity)
**Location:** `docs/engineering/adr/ADR-009-storage-json-module.md:22`, and `katabasis-storage-json/src/main/java/module-info.java:5`
**Contract/Invariant:** ADR-009 Decision section states: *"The module depends on `jakarta.json.bind`, `jakarta.json` (compile), and `org.eclipse.yasson`, `org.eclipse.parsson` (runtime only). It does **not** depend on `katabasis-core`."*
**Failure Scenario:** The actual `module-info.java` declares `requires jsanca.katabasis.core`. `DownloadStateStores` imports `jsanca.katabasis.core.api.manager.DownloadStateStore` and `jsanca.katabasis.core.api.model.DownloadSnapshot`; `FileDownloadStateStore` implements `DownloadStateStore` directly; `CheckpointCoordinator` imports six core event types and `DownloadManager`. The dependency direction is **storage → core**, not independent.

This is not a runtime defect, but it is an architectural one: the ADR is the stated agreement about dependency direction. Future reviewers reading the ADR will believe the module is a reusable, core-agnostic codec library. It is not — `CheckpointCoordinator` and the file store are tightly coupled to core's event and manager API. (The codec *itself* — `JsonEnvelopeCodec` — is core-agnostic. The module is not.)
**Evidence:**
- `module-info.java:5`: `requires jsanca.katabasis.core;`
- `DownloadStateStores.java:3-4`: imports `DownloadStateStore` and `DownloadSnapshot` from core
- `CheckpointCoordinator.java:3-13`: imports six event types, `DownloadManager`, `DownloadStateStore`, `DownloadInfo`, `DownloadSnapshot`, `DownloadStatus`
**Recommended Fix:** Amend ADR-009 with a follow-up note (do not modify the accepted decision text) acknowledging that K-0.1-T03C broadened the module to include the file-backed store and checkpoint coordinator, introducing a dependency on `katabasis-core`. If keeping the codec core-free remains desirable, split `katabasis-storage-json` into:
- `katabasis-storage-codec` — pure codec, no core dependency (matches original ADR intent)
- `katabasis-storage-json` — file store + checkpoint coordinator, depends on both codec and core

Decision: raise a new ADR or amendment proposal rather than modifying the current accepted one.
**Regression Test:** n/a — this is a documentation reconciliation issue. A `jdeps` check on CI could assert the current module graph against the ADR.
**Confidence:** High

---

### STOR-005 — `revision` is a serialized counter, not optimistic concurrency control
**Classification:** Missing guarantee
**Severity:** Medium
**Area:** 2 (Snapshot Revision and Consistency)
**Location:** `StorageDocument.java:9-11`, `FileDownloadStateStore.java:93-109` and `:180-192`
**Contract/Invariant:** `StorageDocument` Javadoc: *"`revision` is a monotonic counter managed by the storage layer for **optimistic concurrency**; zero means 'new document'."*
**Failure Scenario:** The `save` implementation reads the current on-disk revision inside the per-ID lock, increments by one, and writes. It never checks whether the caller's intent was based on a specific prior revision. There is no `save(snapshot, expectedRevision)` overload, no CAS semantics, and no `ConcurrentModificationException`. The field behaves exactly like an internal sequence counter.

Consequence: two logically-stale calls `store.save(oldSnapshot)` followed by `store.save(olderSnapshot)` will persist `olderSnapshot` with revision = N+2. **A stale snapshot can overwrite a newer one** as long as the callers are serialized. Monotonic-progress regression is likewise unchecked — `save` will persist a snapshot with `confirmedBytes = 10` after one with `confirmedBytes = 1000`.

For the current single-process `CheckpointCoordinator` this does not matter much (the coordinator always assembles the snapshot from the latest event), but the Javadoc overstates the invariant.
**Evidence:** `FileDownloadStateStore.java:101-102`:
```java
final long revision = currentRevision(target);
final byte[] bytes  = codec.encode(key, snapshot, revision + 1);
```
No comparison with any caller-supplied expected revision.
**Recommended Fix:** Either (a) downgrade the Javadoc to *"an internal monotonic counter; not used for optimistic concurrency at this time"*, or (b) add `save(DownloadSnapshot snapshot, long expectedRevision)` that throws if `currentRevision(target) != expectedRevision`. Option (a) is lower-cost and matches current reality. If (b) is chosen, also expose the revision via `findById` so callers can propagate it.

Additionally, consider enforcing `newSnapshot.confirmedBytes >= existing.confirmedBytes` inside save for the same ID and the same partialPath — but note this closes off legitimate retries that reset progress after a corrupt partial.
**Regression Test:** Save two snapshots with decreasing `confirmedBytes`; confirm whichever behavior is codified. Today, both succeed.
**Confidence:** High

---

### STOR-006 — Missing `fsync` on tmp file and parent directory
**Classification:** Missing guarantee
**Severity:** Medium
**Area:** 3 (Filesystem Atomicity and Durability)
**Location:** `FileDownloadStateStore.java:202-214` (`atomicWrite`)
**Contract/Invariant:** `FileDownloadStateStore` Javadoc (lines 59-63) acknowledges: *"`fsync` on the directory entry is not performed. Journaling filesystems (ext4, APFS) preserve rename atomicity. On non-journaling filesystems a power failure during the rename may lose the most recent checkpoint but will not corrupt the previous checkpoint."* This is accurate as stated but understates the issue: without `fsync` on the **tmp file's contents**, even on journalled FS the rename can commit an inode whose data blocks are not yet durable — after power loss the target file may appear with zero bytes or old content under certain mount options (e.g., `data=writeback` on ext4).
**Failure Scenario:** Power loss immediately after `Files.move(tmp, target, ATOMIC_MOVE)` returns but before the FS has flushed the tmp's data blocks. On restart:
- The file exists at the new name.
- Its content may be empty, truncated, or a mix of old and new blocks.
- `findRecoverable` reads it, codec throws `CodecException`, the entry is silently skipped (see STOR-010). The download becomes unrecoverable with no signal.
**Evidence:** No `FileChannel.force(true)` call on the tmp file; no `fsync` on the parent directory after the rename. `Files.write(tmp, bytes)` with no `StandardOpenOption.DSYNC` does not force durability.
**Recommended Fix:** After `Files.write(tmp, bytes)`:
```java
try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
    ch.force(true);
}
```
and after the rename:
```java
try (FileChannel dir = FileChannel.open(target.getParent(), StandardOpenOption.READ)) {
    dir.force(true);
} catch (UnsupportedOperationException ignored) { /* e.g. Windows */ }
```
The ADR is correct that this is a tradeoff; make the call explicit and document the chosen point on the durability/performance curve. At minimum, correct the Javadoc to acknowledge the tmp-content durability gap, not only the rename.
**Regression Test:** Not easily unit-testable (requires power-loss simulation). A fault-injection test with a wrapper FileSystem that records writes and rejects post-rename reads can model the risk.
**Confidence:** Medium

---

### STOR-007 — Leftover `.tmp` file after a mid-write I/O failure is not cleaned up
**Classification:** Confirmed defect
**Severity:** Medium
**Area:** 3 (Filesystem Atomicity and Durability), 4 (Corruption and Failure Handling)
**Location:** `FileDownloadStateStore.java:202-214`
**Contract/Invariant:** Class Javadoc: *"Temporary files use the `.json.tmp` suffix and are **removed or renamed atomically on each write**"* (line 29-31).
**Failure Scenario:** `Files.write(tmp, bytes)` fails partway through (disk-full `IOException`, interrupted thread, filesystem error). The `save` method propagates the `IOException` to the caller via `UncheckedIOException`. The half-written `.tmp` file remains on disk forever. There is no cleanup in a `finally` block, and no startup scan removes orphaned `.tmp` files.

Consequences:
- Disk space leak (small, but persistent).
- `findRecoverable` is unaffected (it filters by `.json` suffix).
- A subsequent save of the same ID overwrites the tmp via `Files.write(tmp, bytes)` (truncating-write by default), so the leak is bounded per-ID — **but only if a subsequent save happens**. If the download failed terminally, the orphan persists.

The existing test `interruptedWriteSimulation_existingFilePreservedWhenTmpLeftBehind` (test file lines 204-220) explicitly documents this behavior as acceptable (the tmp is overwritten by the next save). But this only holds under the "next save happens" assumption.
**Evidence:**
```java
// :202-213
private void atomicWrite(final Path target, final byte[] bytes) throws IOException {
    final Path tmp = target.getParent().resolve(target.getFileName() + TMP_EXTENSION);
    Files.write(tmp, bytes);           // may fail, leaves partial tmp
    try {
        Files.move(tmp, target, ...);
    } catch (final AtomicMoveNotSupportedException e) {
        ...
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }
    // no cleanup if Files.write above threw
}
```
**Recommended Fix:**
```java
private void atomicWrite(final Path target, final byte[] bytes) throws IOException {
    final Path tmp = target.getParent().resolve(target.getFileName() + TMP_EXTENSION);
    try {
        Files.write(tmp, bytes);
        try {
            Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            log.warning(...);
            Files.move(tmp, target, REPLACE_EXISTING);
        }
    } catch (IOException e) {
        Files.deleteIfExists(tmp);
        throw e;
    }
}
```
Additionally, scan for and remove `*.json.tmp` files in the constructor (startup recovery).
**Regression Test:** Inject an `IOException` on `Files.write` (via a test-only `FileSystem` or spy), call `save`, assert the `.tmp` file no longer exists after the failed call returns.
**Confidence:** High

---

### STOR-008 — CheckpointCoordinator close() does not wait for in-flight writes
**Classification:** Potential risk
**Severity:** Medium
**Area:** 6 (CheckpointCoordinator)
**Location:** `CheckpointCoordinator.java:118-124`
**Contract/Invariant:** Javadoc at lines 114-117: *"Stops the periodic flush, deregisters the event listener, and flushes any remaining pending snapshots."* By common convention, `close()` on an `AutoCloseable` resource should return only after all pending work is persisted — otherwise users have no safe shutdown point.
**Failure Scenario:**
```java
public void close() {
    flushTask.cancel(false);        // does not interrupt in-flight flush
    manager.removeListener(listener);
    scheduler.shutdown();           // does not await termination
    flushPending();                 // runs on caller thread, in parallel with any still-running scheduler flush
}
```
- The scheduled `flushTask` may be mid-execution when `cancel(false)` is called; `cancel(false)` only prevents future runs.
- `scheduler.shutdown()` initiates orderly shutdown but does not wait.
- `flushPending()` then runs on the caller thread, iterating `pending.values()` while the scheduler's final iteration is still active on another thread.

Both threads will serialize at the per-ID lock in the store, so the data is safe — **but** after `close()` returns, the scheduler thread may still be executing `saveQuietly`, writing to disk after the caller believes shutdown is complete. If the JVM exits immediately (e.g., shutdown hook), that write is lost.
**Evidence:** Lines 118-124 quoted above. No `awaitTermination`.
**Recommended Fix:**
```java
public void close() {
    manager.removeListener(listener);   // stop new events first
    flushTask.cancel(false);
    scheduler.shutdown();
    try {
        if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
            log.warning("Scheduler did not terminate in time");
        }
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }
    flushPending();                     // final flush on caller thread
}
```
Also deregister the listener **first** to avoid new pending entries arriving mid-close.
**Regression Test:** Register a store whose `save` blocks on a latch; publish an event; call `close()`; release the latch; assert `close()` returned only after save completed. Today this test will fail.
**Confidence:** High

---

### STOR-009 — Readers do not acquire locks; on Windows this can cause save to fail
**Classification:** Potential risk
**Severity:** Medium
**Area:** 1, 3
**Location:** `FileDownloadStateStore.java:112-124`, `:127-145`, `:202-214`
**Contract/Invariant:** Javadoc at lines 36-38: *"Read operations (`findById`, `findRecoverable`) do not acquire locks and may observe a snapshot that is concurrently being replaced; this is acceptable because readers tolerate decoding failures gracefully."*
**Failure Scenario:** On POSIX filesystems (macOS APFS, Linux ext4/xfs) this is correct — `ATOMIC_MOVE` replaces the inode and open readers retain the old inode. On Windows NTFS, `Files.move` with `REPLACE_EXISTING` fails with `AccessDeniedException` if the target file is currently open by another handle. `Files.readAllBytes` opens, reads, and closes the file in one call; the window is brief but non-zero. Under load (many readers, frequent checkpoints), the save can intermittently throw.

Secondary consequence: `findRecoverable` on a very busy store can return a mix of old and new documents from the same batch, though each individual document decodes consistently.
**Evidence:** No locking, no retry on `ATOMIC_MOVE` failure due to open readers. The only fallback is `AtomicMoveNotSupportedException` handling — not `AccessDeniedException`.
**Recommended Fix:** Document Windows limitation explicitly, or retry `Files.move` with bounded backoff on `AccessDeniedException` on Windows. A per-ID read-write lock (readers share, writer exclusive) would eliminate the race entirely at the cost of some reader contention.
**Regression Test:** Platform-specific; not easily portable. A targeted Windows CI run with a reader thread doing `findById` in a tight loop against a writer doing `save` should be able to trigger it.
**Confidence:** Medium

---

### STOR-010 — Corrupt manifest is silently skipped by `findRecoverable` and masked by next successful save
**Classification:** Missing guarantee
**Severity:** Medium
**Area:** 4 (Corruption and Failure Handling)
**Location:** `FileDownloadStateStore.java:127-145`, `:111-124`, `DownloadStateStore.java:48`
**Contract/Invariant:** `DownloadStateStore.findRecoverable` Javadoc states: *"Corrupt or unreadable documents are **silently skipped**."* This is the documented contract but amounts to data loss with no escalation path.
**Failure Scenario:** A download failed and its manifest is the only evidence. The file is truncated (power loss, see STOR-006). Startup recovery:
1. `findRecoverable` lists the directory, tries to decode the corrupt file, hits `CodecException`.
2. The manifest is logged at WARNING level only (line 138) and dropped from the result list.
3. The user sees no entry for the failed download. Nothing distinguishes "no download existed" from "the manifest was unreadable".
4. On the next save for any ID, that corrupt file is left alone — but if the same ID is retried, `save` overwrites it (losing the evidence entirely).

Secondary: `findById` on a corrupt file returns `Optional.empty()` — indistinguishable from "no such ID" (line 121-123).
**Evidence:**
```java
// :137-139
} catch (final CodecException | IOException e) {
    log.warning("Skipping unreadable snapshot file " + file + ": " + e.getMessage());
}
```
No quarantine, no emitted recovery event, no caller-visible signal.
**Recommended Fix:** Options:
- (a) On corrupt-file detection, rename it to `<id>.json.corrupt.<timestamp>` to preserve evidence out of the active set. Log at ERROR, not WARNING.
- (b) Expose a `findAll()` / `listCorrupt()` method so UI can surface the problem.
- (c) At minimum, emit a structured event (future `RecoveryEvent.CorruptManifest`) rather than only logging.
**Regression Test:** Write a corrupt manifest, call `findRecoverable`, assert the corrupt file is quarantined and a warning is emitted.
**Confidence:** High

---

### STOR-011 — PathAdapter is not portable across operating systems
**Classification:** Potential risk
**Severity:** Low
**Area:** 5 (Codec)
**Location:** `katabasis-storage-json/src/main/java/jsanca/katabasis/storage/json/internal/adapter/PathAdapter.java:8-19`
**Contract/Invariant:** The adapter serializes `Path` as `p.toString()`. On Windows this yields `C:\Users\x\...`; on Linux `/home/x/...`. Round-trips through `Path.of(s)` on the **same** OS. If a manifest is produced on Linux and read on Windows (e.g., shared storage, migration), `Path.of("/home/x/file.bin")` is legal on Windows but means a different thing (`\home\x\file.bin` relative to current drive).
**Failure Scenario:** User migrates their checkpoint directory from Linux to Windows. Downloads reference the wrong partial-file location; recovery fails or overwrites unrelated files.
**Evidence:** Direct `toString` / `Path.of` round-trip, no normalisation.
**Recommended Fix:** Store `Path` as a URI (`p.toUri().toString()` / `Path.of(URI.create(s))`) — this encodes OS-specific semantics in a portable form. Alternatively, document the cross-OS limitation explicitly.
**Regression Test:** Encode a Linux-style path, decode on a Windows FileSystem instance, assert documented behavior.
**Confidence:** Medium

---

### STOR-012 — Failed events before any Started/Progress event produce no persisted snapshot
**Classification:** Potential risk
**Severity:** Low
**Area:** 6 (CheckpointCoordinator)
**Location:** `CheckpointCoordinator.java:180-190`
**Contract/Invariant:** Javadoc at lines 42-45: *"On `DownloadFailedEvent` the snapshot is **retained** so the user can inspect the failure and decide whether to retry."*
**Failure Scenario:** `handleFailed` does:
```java
final DownloadSnapshot existing = pending.remove(e.info().downloadId());
if (existing != null) { /* build FAILED snap and save */ }
```
If the `DownloadFailedEvent` arrives before the coordinator has seen a `Started`/`Progress` event for that ID (e.g., the failure happens during URI resolution, or the listener was registered after the download began), there is no entry in `pending`. `existing` is null. Nothing is persisted. The documented "retained for inspection" behavior silently drops the record.
**Evidence:** Lines 180-190 as quoted.
**Recommended Fix:** Build a minimal FAILED snapshot from `e.info()` fields when `existing == null`:
```java
if (existing != null) { /* use existing fields */ }
else { /* build from e.info() with confirmedBytes=0 */ }
```
**Regression Test:** Publish only a `DownloadFailedEvent` (no prior Started/Progress); assert a FAILED snapshot is saved.
**Confidence:** High

---

### STOR-013 — currentRevision resets to 0 on read failure, silently breaking monotonicity
**Classification:** Potential risk
**Severity:** Low
**Area:** 2 (Revision and Consistency)
**Location:** `FileDownloadStateStore.java:180-192`
**Contract/Invariant:** Revision should be monotonically non-decreasing for a given ID.
**Failure Scenario:** If an existing manifest is temporarily unreadable (transient I/O glitch, concurrent write on Windows, codec exception during migration bug), `currentRevision` returns `0L` and the subsequent save writes revision `1`, replacing a manifest that was at revision 500. Monotonicity is silently violated, and whatever external consumer relies on the counter sees a regression.
**Evidence:**
```java
// :186-191
} catch (final CodecException | IOException e) {
    log.warning("Could not read revision from " + file + "; resetting to 0: " + e.getMessage());
    return 0L;
}
```
Warning log only; no caller signal.
**Recommended Fix:** Distinguish "file does not exist" (return 0) from "file exists but unreadable" (throw, or back off and retry). Silently resetting to 0 on transient failures is unsafe.
**Regression Test:** Pre-populate a manifest at revision 10, corrupt its envelope, then save; assert the new revision is ≥ 11 or the save throws.
**Confidence:** High

---

### STOR-014 — Segment confirmedBytes monotonicity not enforced across saves
**Classification:** Missing guarantee
**Severity:** Low
**Area:** 2
**Location:** `DownloadSnapshot.java`, `FileDownloadStateStore.java:93-109`
**Contract/Invariant:** For a given download, `segments[i].confirmedBytes` should be non-decreasing across checkpoints (bytes are confirmed, not unconfirmed, by design).
**Failure Scenario:** The store accepts any valid `DownloadSnapshot`. If a buggy caller passes a snapshot whose segment confirmed bytes are lower than the previous one, it is accepted. On recovery, the downloader resumes from the lower offset and re-downloads already-written bytes — wasted work, not correctness-breaking.
**Evidence:** No comparison with prior state in `save`.
**Recommended Fix:** Optional: enforce monotonic non-decrease for segments of the same ID under the per-ID lock. Alternative: document that enforcement is the caller's responsibility (`CheckpointCoordinator`).
**Regression Test:** Save two snapshots where the second has a lower per-segment confirmed value; assert whichever behavior is codified.
**Confidence:** Medium

---

### STOR-015 — Codec encode does deep JSON reparse of payload for every write
**Classification:** Design improvement
**Severity:** Low
**Area:** 5
**Location:** `JsonEnvelopeCodec.java:91-108`
**Contract/Invariant:** Encoding should be efficient enough not to be the bottleneck on hot checkpoint paths.
**Failure Scenario:** `encode` calls `jsonb.toJson(value)` producing a JSON string, then uses JSON-P to parse that string back into a `JsonObject`, then builds the envelope via `JsonObjectBuilder` and serializes it again with `toString()`. Three string round-trips per save. With a 1-second checkpoint cadence and tens of active downloads this is negligible, but with segment-level checkpointing at millisecond rates it is wasteful.
**Evidence:** Lines 91-104 — `toJson` → `createReader(StringReader)` → `.add(payload, payload)` → `toString()`.
**Recommended Fix:** Switch to a single-stage write via a streaming `JsonGenerator` wrapped over a `ByteArrayOutputStream`, with the payload written via `jsonb.toJson(value, generator)` as a nested object. Low-priority refactor.
**Regression Test:** Benchmark, not a correctness test.
**Confidence:** High

---

## Confirmed Defects vs. Hypotheses

| ID | Classification | Severity | Summary |
| --- | --- | --- | --- |
| STOR-001 | Confirmed defect | High | `delete()` removes the lock while held; concurrent writers can hold different locks for the same ID |
| STOR-002 | Confirmed defect | High | `findById` / `delete` bypass `StorageKey` validation, enabling path traversal via the download ID |
| STOR-003 | Confirmed defect | High | Scheduled flush can resurrect a manifest after terminal `delete()` because `ConcurrentHashMap.values()` is weakly consistent |
| STOR-004 | Confirmed defect | High | ADR-009 states the module has no `katabasis-core` dependency; `module-info.java` declares one |
| STOR-005 | Missing guarantee | Medium | `revision` is a serialized counter; the "optimistic concurrency" Javadoc is misleading |
| STOR-006 | Missing guarantee | Medium | No `fsync` on tmp content; power-loss can leave zero-byte or torn manifests |
| STOR-007 | Confirmed defect | Medium | Leftover `.tmp` after mid-write I/O failure is not cleaned; no startup scan |
| STOR-008 | Potential risk | Medium | `close()` does not `awaitTermination`; in-flight writes can outlive it |
| STOR-009 | Potential risk | Medium | Lockless readers can cause intermittent `AccessDeniedException` on Windows |
| STOR-010 | Missing guarantee | Medium | Corrupt manifests silently dropped from `findRecoverable`; no caller signal |
| STOR-011 | Potential risk | Low | `PathAdapter` is not portable across OS filesystem syntaxes |
| STOR-012 | Potential risk | Low | `handleFailed` with no prior `Started`/`Progress` silently drops the FAILED snapshot |
| STOR-013 | Potential risk | Low | `currentRevision` resets to 0 on read failure, silently violating monotonicity |
| STOR-014 | Missing guarantee | Low | Per-segment `confirmedBytes` monotonicity across saves is not enforced |
| STOR-015 | Design improvement | Low | `encode()` does three string round-trips per save |

## Existing Test Evidence

The `FileDownloadStateStoreTest` suite (23 tests) covers:
- Round-trip save/find, delete idempotency, overwrite, segmented snapshot fields, atomic replacement cleanup, corrupt-file handling for `findById` and `findRecoverable`, future-schema rejection, revision increment, concurrent saves for same and different IDs, module boundary assertion.

The `JsonEnvelopeCodecTest` suite (20 tests) covers:
- Round-trip, envelope field presence, metadata extraction, migration v1→v2, malformed-JSON / missing-field / wrong-envelope-version / non-object-payload / zero-version / negative-revision / future-version rejection, null-input guards.

The `CheckpointCoordinatorTest` suite (10 tests) covers:
- Listener registration/deregistration, pause → immediate save, completion/cancellation → delete, failed → retained FAILED save, progress coalescing, close-flush, invalid interval rejection.

**What the tests miss** (not exhaustive, mapped to findings):
- No test exercises `save` racing with `delete` (STOR-001). The existing concurrent-save fuzz uses the same ID with no deletes.
- No test passes an invalid `downloadId` (`"../x"`) to `findById` / `delete` (STOR-002).
- No test interleaves a terminal event with a scheduled flush (STOR-003). The flush test uses `Duration.ofMinutes(60)` so the scheduler never fires during the test.
- No test exercises `close()` with a slow store (STOR-008).
- No test publishes `DownloadFailedEvent` without a prior `Started` event (STOR-012).
- No test corrupts an existing manifest between saves and asserts revision monotonicity (STOR-013).
- No test asserts `.tmp` cleanup when `Files.write` fails (STOR-007).
- No portability test for `PathAdapter` across OSes (STOR-011).

## Proposed Corrective Backlog

| Task ID | Description | Depends On | Priority |
| --- | --- | --- | --- |
| K-STOR-001 | Fix lock-map race in `FileDownloadStateStore.delete`; either keep the lock in the map or use `computeIfPresent` with validated identity | — | High |
| K-STOR-002 | Validate `downloadId` against the `StorageKey` safe pattern in `resolve`; close path traversal on `findById` / `delete` / `findRecoverable` | — | High |
| K-STOR-003 | Make `CheckpointCoordinator` resurrection-safe by combining `pending.compute` with store operations; delete-and-flush must be ordering-safe | — | High |
| K-STOR-004 | Amend ADR-009 (via follow-up ADR or amendment note) to reflect the actual dependency direction, or split the module to restore the original invariant | — | High |
| K-STOR-005 | Reconcile revision Javadoc with implementation: either downgrade to "internal counter" or add CAS-based `save(snapshot, expectedRevision)` | — | Medium |
| K-STOR-006 | Add `fsync` on tmp content and parent directory in `atomicWrite`; update durability Javadoc accordingly | — | Medium |
| K-STOR-007 | Cleanup leftover `.tmp` on mid-write failure (`try/catch/deleteIfExists`); scan and remove orphaned `.tmp` at startup | — | Medium |
| K-STOR-008 | `CheckpointCoordinator.close()` must `awaitTermination` and deregister listener before cancelling the flush task | — | Medium |
| K-STOR-009 | Document Windows `AccessDeniedException` risk for concurrent readers, or add bounded retry on the atomic move | — | Low |
| K-STOR-010 | Quarantine corrupt manifests (rename to `*.corrupt.<ts>`) and expose a recovery-failure signal rather than silent log-and-skip | — | Medium |
| K-STOR-011 | Switch `PathAdapter` to URI form, or document the cross-OS limitation and add a guard rail | — | Low |
| K-STOR-012 | Persist a FAILED snapshot from `DownloadFailedEvent` even when no prior entry exists in `pending` | — | Low |
| K-STOR-013 | Distinguish "file missing" from "file unreadable" in `currentRevision`; do not silently reset to 0 | K-STOR-005 | Low |
| K-STOR-014 | Decide and codify per-segment `confirmedBytes` monotonicity policy | K-STOR-005 | Low |
| K-STOR-015 | Collapse `encode()` to a single streaming write via `JsonGenerator` | — | Low |

## Architecture Boundary Assessment

The JPMS boundary **as enforced by the module system** works:
- `exports jsanca.katabasis.storage.json.api;` only.
- Internal packages (`.codec`, `.migration`, `.adapter`, `.store`) are not exported; no caller can reference `JsonEnvelopeCodec`, `FileDownloadStateStore`, `UriAdapter`, or `MigrationRegistry` directly.
- The `DownloadStateStore` port in `katabasis-core` is free of JSON or filesystem types — this is clean. `DownloadSnapshot` and `SegmentSnapshot` are pure records of `java.base` types.
- No `jakarta.json.*` type leaks into the port or into `katabasis-core`.

**Where it drifts from the ADR:**
- ADR-009 explicitly states `katabasis-storage-json` does not depend on `katabasis-core`. `module-info.java` declares `requires jsanca.katabasis.core`. The codec portion (`JsonEnvelopeCodec` + migration + adapters) is still core-agnostic and could be extracted. The file store and the checkpoint coordinator — both added in K-0.1-T03C — are not.
- Dependency direction (`katabasis-storage-json → katabasis-core`) is consistent with the roadmap note at the end of ADR-009 (*"`DownloadStateStore` (in `katabasis-core`) will depend on `katabasis-storage-json` when persistence is implemented"*) — but note that in practice the direction was **inverted**: storage depends on core, not core on storage. This is actually the correct direction for a port-and-adapter architecture (the adapter depends on the port, not the other way round). The ADR's final paragraph predicted the wrong direction. This is recorded as STOR-004 and deserves an amendment.

No leakage of implementation types into the public API was found.

## Remaining Recovery Limitations

Recovery guarantees that are **live today**:
- Encode/decode of all `DownloadSnapshot` and `SegmentSnapshot` fields (verified by tests).
- Per-save atomic replacement on POSIX filesystems that support `ATOMIC_MOVE`.
- Per-ID save/delete serialization (modulo STOR-001 and STOR-003).
- Migration from older schema versions on decode (verified by tests; no migrations currently registered — chain is empty).
- `findRecoverable` returning IN_PROGRESS and PAUSED entries.

Recovery guarantees that are **not yet live** or deferred:
- Segment-level checkpointing from inside the HTTP strategy. `CheckpointCoordinator` builds snapshots from event data only and always writes `segments = List.of()` and `etag = null`, `partialPath = null` (CheckpointCoordinator.java:233-234). The class Javadoc explicitly acknowledges this at lines 51-54 (roadmap 0.2).
- ETag revalidation on resume.
- Partial-file byte-accurate confirmation (requires integration with `HttpDownloadOrchestrator`).
- Post-restart resume execution. `findRecoverable` returns snapshots but nothing consumes them yet; `DownloadManager` has no `resumeAll(List<DownloadSnapshot>)` method.
- Durable persistence under power loss (see STOR-006).
- Cross-process coordination (explicitly rejected by the module Javadoc, "multi-process sharing not supported").
- Corruption escalation (STOR-010).

## ADR Notes

An amendment to **ADR-009** (or a follow-up ADR, e.g. ADR-010) is justified by STOR-004. The amendment should record that:
- K-0.1-T03C expanded the module to include `FileDownloadStateStore` and `CheckpointCoordinator`, which depend on `katabasis-core` events and the port.
- The actual dependency direction is `katabasis-storage-json → katabasis-core`, not independent as originally stated.
- If core-agnostic codec reuse is still a goal, `katabasis-storage-codec` should be split off (per the originally-rejected alternative in ADR-009), leaving `katabasis-storage-json` as a core-dependent persistence module.

No other finding justifies a new ADR; the rest are implementation defects or guarantee tightenings.

## Related Records

- Port: [DownloadStateStore.java](../../../../katabasis-core/src/main/java/jsanca/katabasis/core/api/manager/DownloadStateStore.java)
- Snapshot record: [DownloadSnapshot.java](../../../../katabasis-core/src/main/java/jsanca/katabasis/core/api/model/DownloadSnapshot.java)
- Store implementation: [FileDownloadStateStore.java](../../../../katabasis-storage-json/src/main/java/jsanca/katabasis/storage/json/internal/store/FileDownloadStateStore.java)
- Coordinator: [CheckpointCoordinator.java](../../../../katabasis-storage-json/src/main/java/jsanca/katabasis/storage/json/api/CheckpointCoordinator.java)
- Codec: [JsonEnvelopeCodec.java](../../../../katabasis-storage-json/src/main/java/jsanca/katabasis/storage/json/internal/codec/JsonEnvelopeCodec.java)
- ADR: [ADR-009-storage-json-module.md](../../adr/ADR-009-storage-json-module.md)
- Task report: [K-0.1-T03B-storage-json.md](K-0.1-T03B-storage-json.md)
- Task report (T03C): [K-0.1-T03C-state-store.md](K-0.1-T03C-state-store.md)
