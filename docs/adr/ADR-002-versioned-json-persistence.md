# ADR-002 — Versioned JSON Persistence Strategy

## Status

Accepted

## Context

Katabasis 0.2 requires persistent download state so interrupted downloads can be recovered
after process restart. The core module defines a `DownloadStateStore` port; an implementation
must be chosen and specified.

The choice must address:

- How individual download snapshots are stored and recovered.
- How schema evolution is handled without breaking existing manifests.
- What atomicity and durability guarantees are realistic on a standard filesystem.
- How concurrent in-process writers are coordinated.
- What conditions would justify adopting a different adapter in the future.

An implementation in the `katabasis-storage-json` module already exists, was adversarially
reviewed (K-STORAGE-REVIEW-001), and was hardened (K-STOR-HARDEN-001). This ADR formally
records the strategy established by that work.

---

## Architecture

```
CLI / JavaFX / Future API
         |
         v
   DownloadManager
   (katabasis-core)
         |
         v
  DownloadStateStore
     (core port)
         |
         v
FileDownloadStateStore
    (JSON adapter)
   katabasis-storage-json
         |
         v
  Versioned JSON manifests
  one .json file per download
```

**Module boundaries:**

- `katabasis-core` owns the `DownloadStateStore` port and the `DownloadSnapshot` / `SegmentSnapshot`
  domain records. No JSON or filesystem types appear in these interfaces.
- `katabasis-storage-json` owns the JSON adapter, the envelope codec, the migration registry,
  and the `CheckpointCoordinator`. It depends on `katabasis-core`; the dependency is unidirectional.
- Protocol adapters (`katabasis-cli`, `katabasis-ui`, future HTTP/FTP/S3 strategies) must not
  own persistent download lifecycle state. Persistence is the responsibility of the core
  orchestration layer, coordinated through `CheckpointCoordinator`.

---

## Decision

**Versioned JSON files, one per download, in a configurable directory** are the initial
persistence strategy for Katabasis 0.2.

This decision reflects the existing hardened implementation, not a new selection. The rationale
and alternatives follow.

---

## Manifest Structure

Each manifest is a JSON envelope wrapping a typed payload:

```json
{
  "envelopeVersion": "1",
  "type": "download-snapshot",
  "id": "<downloadId>",
  "schemaVersion": 1,
  "revision": 7,
  "payload": {
    "downloadId": "...",
    "sourceUri": "https://...",
    "targetPath": "/downloads/file.bin",
    "partialPath": null,
    "etag": "\"33a64df551425fcc55e4d42a148795d9f25f89d4\"",
    "expectedTotalBytes": 104857600,
    "confirmedBytes": 36700160,
    "status": "IN_PROGRESS",
    "segments": [
      { "index": 0, "startByte": 0, "endByte": 26214399, "confirmedBytes": 26214400 },
      { "index": 1, "startByte": 26214400, "endByte": 52428799, "confirmedBytes": 10485760 }
    ],
    "createdAt": "2026-11-17T12:00:00Z",
    "checkpointAt": "2026-11-17T12:01:30Z"
  }
}
```

### Envelope fields

| Field | Type | Purpose |
|-------|------|---------|
| `envelopeVersion` | string | Envelope format version; currently `"1"` |
| `type` | string | Document type discriminator; `"download-snapshot"` for snapshots |
| `id` | string | Matches `downloadId`; enables lookup without decoding payload |
| `schemaVersion` | integer ≥ 1 | Payload schema version; drives migration |
| `revision` | long ≥ 0 | Monotonic write counter; used to detect revision regressions |
| `payload` | object | JSON-B serialization of `DownloadSnapshot` |

### Snapshot fields

| Field | Type | Required | Purpose |
|-------|------|----------|---------|
| `downloadId` | string | Yes | Stable unique identifier |
| `sourceUri` | URI | Yes | Original download source |
| `targetPath` | Path | Yes | Final destination path |
| `partialPath` | Path | No | In-progress file; `null` means same as `targetPath` |
| `etag` | string | No | Strong ETag for resource revalidation before resume |
| `expectedTotalBytes` | long | Yes | Content length; `-1` if unknown |
| `confirmedBytes` | long | Yes | Bytes durably written to the partial file |
| `status` | enum | Yes | `IN_PROGRESS`, `PAUSED`, `FAILED` (see below) |
| `segments` | array | Yes | Segment boundaries and confirmed offsets; empty for single-stream |
| `createdAt` | Instant | Yes | When the download was originally submitted |
| `checkpointAt` | Instant | Yes | When this snapshot was taken |

**`SegmentSnapshot` fields (per element of `segments`):**

| Field | Type | Invariant |
|-------|------|-----------|
| `index` | int ≥ 0 | Zero-based segment index |
| `startByte` | long ≥ 0 | Inclusive start byte offset |
| `endByte` | long ≥ startByte | Inclusive end byte offset |
| `confirmedBytes` | long ∈ [0, endByte−startByte+1] | Bytes from startByte durably written |

**Persisted statuses:** `IN_PROGRESS` and `PAUSED` are recoverable (see `isRecoverable()`).
`FAILED` is retained for inspection. `COMPLETED` and `CANCELLED` trigger manifest deletion.

**Current limitation (T10):** `partialPath`, `etag`, and `segments` are always null/empty in
snapshots produced by the current `CheckpointCoordinator` because these values come from
internal execution state not exposed by public events. Populating them is deferred to K-0.2-T10.
Until then, only single-stream recovery from byte offset 0 is possible.

---

## Schema Compatibility Policy

| Stored version | Action |
|---------------|--------|
| equals current | Decode directly; no migration |
| older than current | Apply registered migrations in version order before deserialization |
| newer than current | Throw `CodecException`; do not attempt to read; log the version mismatch |
| `envelopeVersion ≠ "1"` | Throw `CodecException`; unrecognised envelope format |
| `schemaVersion < 1` | Throw `CodecException`; malformed document |

**Migration chain rules:**
- Each `Migration` transforms a `JsonObject` from version N to version N+1 as a pure function.
- Migrations must form a contiguous chain (no gaps, no duplicates); `MigrationRegistry` enforces
  this at construction time.
- Migrations operate on raw JSON; they do not invoke JSON-B deserialization.

**Missing required fields:** JSON-B deserialization will fail; `findById` returns `Optional.empty()`;
`findRecoverable` skips the document and logs a warning.

**Invalid enum values:** JSON-B deserialization will fail; treated as corrupt (same as above).

**Unknown future versions:** `CodecException` is thrown; `findRecoverable` skips and logs.
The application should not silently accept or invent data it did not write.

---

## Atomicity and Durability

### Write path

1. Encode `DownloadSnapshot` to bytes via `JsonEnvelopeCodec`.
2. Write bytes to `<id>.json.tmp` (a sibling of the target).
3. Move `<id>.json.tmp` to `<id>.json`:
   - Attempt `ATOMIC_MOVE` first.
   - If the filesystem reports `AtomicMoveNotSupportedException`, fall back to a non-atomic
     replace and log a `WARNING`.
4. On any `IOException` during write or rename, delete the `.tmp` file and rethrow.
5. On process startup, `FileDownloadStateStore` scans for and removes orphaned `.tmp` files.

### Atomic visibility

Atomic visibility is **conditional on filesystem support for atomic rename**.

When `ATOMIC_MOVE` succeeds: a reader opening `<id>.json` observes either the previous complete
document or the new complete document — never a partially written intermediate state. The rename
replaces the directory entry atomically at the VFS level on POSIX filesystems.

When `ATOMIC_MOVE` is not supported and the implementation falls back to a non-atomic
`REPLACE_EXISTING` move: a reader that opens the target file during the replacement may observe
a partially written or zero-length file. The fallback is logged at `WARNING` level.
`findById` and `findRecoverable` tolerate this by treating decode failures as absent manifests,
so a reader during a non-atomic replacement will get an empty result rather than corrupt data.

**Limitation (STOR-009):** On Windows, `Files.move(ATOMIC_MOVE)` can fail under concurrent
readers because NTFS open handles prevent rename. Windows support is not a phase 0.2 requirement.

### Durability — Five levels distinguished

| Level | What it means | Current status |
|-------|--------------|----------------|
| 1. OutputStream write | Bytes passed to `OutputStream.write()` or `FileChannel.write()` | Implemented; flushes to OS page cache, not to storage medium |
| 2. OS flush | Bytes visible to other processes reading the same file | Implicit for `FileChannel.write()`; for `OutputStream` only after `flush()` or close |
| 3. Process-crash resilience | Bytes survive a JVM crash (`kill -9`, `OutOfMemoryError`) | Yes — OS page cache survives process termination |
| 4. Kernel/OS-crash resilience | Bytes survive a kernel panic or hard reboot | **Not guaranteed** — no `fsync` is performed |
| 5. Power-loss durability | Bytes survive a sudden power failure | **Not guaranteed** — no `fsync` on manifest file or parent directory |

**Journaling clarification:** journaling filesystems (ext4, APFS, ZFS) journal filesystem
_metadata_ (directory entries, inode tables). They do NOT journal file _content_ unless
data-journaling mode is explicitly enabled (rare; not the default on any mainstream filesystem).
An atomic rename survives a power failure in the sense that the directory entry is consistent
after journal recovery — but the file content pointed to by that entry may still be lost if
it was in the OS page cache when power was removed. Do not equate "journaling filesystem"
with "durable file content".

**`fsync` limitation (STOR-006, deferred):** `fsync` is not performed on the manifest file or
its parent directory. File content committed to the OS page cache may be lost on kernel crash
or power failure regardless of filesystem type. Full durability requires:
- `fsync` on the `.tmp` file after `Files.write(tmp, bytes)` (content durability)
- `fsync` on the parent directory after the rename (directory-entry durability)
Both are deferred beyond phase 0.2.

**Phase 0.2 durability guarantee:** Checkpoints survive normal process termination (including
`kill -9`) and are protected against partial-write corruption by the write-to-tmp-then-rename
pattern. Checkpoints are **not** guaranteed to survive a kernel crash or power failure.
Recovery from a lost checkpoint (re-downloading from byte 0 or the previous persisted offset)
is acceptable for phase 0.2.

---

## Concurrency and Consistency

### Per-download write serialization

`FileDownloadStateStore` maintains a `ConcurrentHashMap<String, ReentrantLock>` keyed by
download ID. Writers (save, delete) acquire the per-ID lock before proceeding. Locks are
retained for the lifetime of the store instance and never removed (STOR-001 fix).

Concurrent writes for the same download ID are serialized. Concurrent writes for different
IDs proceed without contention.

### Lock-free reads

`findById` and `findRecoverable` do not acquire locks. A reader may observe a document that
is simultaneously being replaced. If the read yields partial or corrupt data, `CodecException`
is caught and the document is skipped (returned as `Optional.empty()` or omitted from the
recoverable list). This is acceptable because recovery decisions are made at process startup,
not during live execution.

### Revision monotonicity

Each successful write increments the revision counter. `currentRevision` reads the existing
document before writing; if the existing document is corrupt, it throws rather than resetting
to 0 (STOR-013 fix). A stale checkpoint must not replace a newer committed revision.

### Terminal state ordering

`CheckpointCoordinator` uses `compute`/`computeIfPresent` on the pending map for terminal
events (STOR-003 fix). A `DownloadCompletedEvent` or `DownloadCancelledEvent` removes the
entry from the pending map and deletes the manifest. A subsequent periodic flush uses
`computeIfPresent` and will not resurrect a deleted entry.

**Invariants:**

- A terminal state (completed, user-cancelled) must not be followed by a checkpoint write.
- `confirmedBytes` must be monotonically non-decreasing within a download session.
- A checkpoint must not claim completed segments whose data is not yet in the OS page cache.
  (Full disk durability is not guaranteed; see Durability section above.)
- `close()` deregisters the listener first, then awaits scheduler termination (up to 30 s),
  then performs a final flush on the calling thread (STOR-008 fix). No new events enter after
  listener removal.

### `confirmedBytes` semantics

`confirmedBytes` in a checkpoint represents bytes confirmed by the OS write API — not bytes
guaranteed on durable storage.

| Strategy | Source of `confirmedBytes` | Durable? |
|----------|--------------------------|----------|
| Single-stream (`HttpDownloadStrategy`) | `OutputStream.write()` counter — bytes in JVM buffers; `flush()` is called only on download completion, not at each checkpoint | At checkpoint time: **no** (may still be in JVM heap buffer) |
| Segmented (`SegmentedHttpDownloadStrategy`) | `FileChannel.write()` return value — bytes accepted by the OS | At checkpoint time: in OS page cache; **not** guaranteed durable without `fsync` |

**Recovery invariant:** a recovery process must not treat `confirmedBytes` as proof that the
corresponding bytes are durably stored on disk. Before reusing any byte range:

1. Verify the partial file exists and its size ≥ `confirmedBytes` for the range being reused.
2. For segmented downloads: validate each segment's confirmed contiguous prefix independently.
   File length alone is insufficient — concurrent VT writes produce a sparse file; a segment
   that starts at byte 10 MB does not imply bytes 0–10 MB are correct.
3. Revalidate remote resource identity (ETag or content-length) before resuming any range.
4. If validation fails for any segment, restart that segment from its `startByte` (not from
   file position 0, unless the whole file is suspect).

### Shutdown versus explicit user cancellation

`DownloadManager.close()` and explicit user cancellation (`manager.cancel(id)`) are distinct
operations with different persistence consequences.

**Explicit user cancellation:** `DownloadCancelledEvent` is emitted and dispatched to all
listeners including `CheckpointCoordinator`. The coordinator removes the manifest. The download
is not recoverable.

**Shutdown-initiated cancellation:** `DownloadManager.close()` adds in-flight download IDs to
`shutdownCancelledIds` before signalling cancellation. The `emit()` method suppresses
`DownloadCancelledEvent` for IDs in that set. The coordinator receives no cancellation event;
the manifest remains with `IN_PROGRESS` status and the last checkpointed progress. The download
is recoverable at the next process start.

**Close ordering requirement:** the caller must close `CheckpointCoordinator` **after**
`DownloadManager.close()` returns. If the coordinator is closed first, it deregisters its
listener and misses the final progress events from the manager's shutdown sequence.
`CheckpointCoordinator.close()` performs a final flush on the calling thread; that flush must
execute after the manager has finished signalling all in-flight tasks.

**Executor timeout limitation:** `DownloadExecutors.VirtualThreadDownloadExecutor.close()`
waits up to 10 seconds for running virtual threads to terminate. If a download does not
respond to cancellation within that window, the executor logs a warning and proceeds. The
in-flight task may still be running when the caller's finally block executes. Any checkpoint
produced after `CheckpointCoordinator.close()` returns will not be flushed. This is an
accepted limitation for phase 0.2; a production-quality shutdown would require a explicit
cancellation-acknowledged barrier before the coordinator is closed.

### Multi-process sharing

Not supported. Multiple JVM processes writing to the same directory will race without
coordination. Callers must use separate directories per process or external file locking.

---

## Corruption Handling

| Scenario | Current behavior | Classification |
|----------|-----------------|----------------|
| Missing manifest | `findById` returns empty; no recovery | Acceptable |
| Invalid JSON (malformed) | `CodecException` → `findById` returns empty; `findRecoverable` skips and logs | Accepted limitation |
| Unsupported `envelopeVersion` | `CodecException` → skipped | Accepted limitation |
| `schemaVersion` newer than current | `CodecException` → skipped and logged | Accepted limitation |
| `schemaVersion` older, migration available | Migrated transparently | Implemented |
| `schemaVersion` older, no migration | `CodecException` → skipped | Accepted limitation |
| Missing required field | JSON-B deserialization failure → skipped | Accepted limitation |
| Invalid enum value | JSON-B deserialization failure → skipped | Accepted limitation |
| Corrupt revision (file unreadable at save time) | `save` throws rather than resetting to 0 | Implemented (STOR-013) |
| Orphaned `.tmp` file | Cleaned at store construction | Implemented (STOR-007) |
| Partially written `.tmp` (crash mid-write) | Cleaned at store construction; target unchanged | Implemented |
| Quarantine/alert for corrupt manifests | Not implemented | Deferred (STOR-010) |

**Policy:** corrupt documents are skipped on recovery, not silently accepted. The application
must not resume from an offset that cannot be validated. When a corrupt manifest is detected,
the download is treated as not recoverable — the user will need to restart it from the beginning.

**Future work (STOR-010):** a quarantine directory for corrupt manifests would allow operators
to inspect and diagnose corruption without silent data loss. Deferred beyond phase 0.2.

---

## Alternatives Considered

### Versioned JSON — Accepted initial strategy

**Advantages:**
- Human-readable and inspectable with any text editor.
- Portable across JVM platforms (modulo `PathAdapter` cross-OS, STOR-011).
- No embedded database dependency.
- Already fully implemented and tested.
- Per-download manifest isolation: a single corrupt file does not affect others.
- Simple backup, debugging, and manual recovery.

**Limitations:**
- No `fsync` without additional platform-specific work (STOR-006).
- In-process locking only; no multi-process coordination.
- No transactional query engine.
- Schema migration is explicit and manual.
- Filesystem rename atomicity is POSIX-only; Windows has caveats (STOR-009).

### SQLite — Deferred alternative

**Potential advantages:**
- ACID transactions with automatic crash recovery.
- Indexed queries across all downloads.
- Better fit for large registries (hundreds of concurrent downloads).
- Well-understood write-ahead log durability model.
- Multi-process safe with WAL mode.

**Trade-offs:**
- Adds a runtime dependency (e.g., `sqlite-jdbc` or native library).
- Schema migrations require SQL DDL management.
- Requires a new `DownloadStateStore` adapter.
- Less transparent to operators (binary file, requires sqlite3 tooling).

**Trigger conditions for reconsideration:**
- Multi-process writers required (e.g., distributed download daemon with multiple workers).
- Registry queries across downloads needed at runtime (e.g., resumption of all interrupted
  downloads by URI pattern).
- JSON durability guarantees are demonstrably insufficient for a confirmed failure mode.
- Registry scale exceeds practical per-file limits (e.g., > 10,000 concurrent downloads).

SQLite must remain compatible with the existing `DownloadStateStore` port if adopted.
No migration to SQLite during phase 0.2.

---

## T10 Handoff — Storage Wiring (K-0.2-T10)

### What exists

- `DownloadStateStore` port: `save`, `findById`, `findRecoverable`, `delete`.
- `FileDownloadStateStore`: thread-safe JSON adapter with atomic write.
- `CheckpointCoordinator`: event-driven coordinator with periodic flush.
- `DownloadStateStores.fileStore(directory)` factory.
- `DownloadSnapshot` and `SegmentSnapshot` records with all fields defined.

### What is missing

The current `CheckpointCoordinator` builds snapshots from public event data only. The following
fields are always null/empty in current production snapshots:

| Field | Required for recovery | Why not currently populated |
|-------|----------------------|----------------------------|
| `partialPath` | Yes (if different from targetPath) | Internal to `DownloadTask`; not in events |
| `etag` | Yes (for segmented resume validation) | Internal to `HttpDownloadOrchestrator`; not in events |
| `segments` | Yes (for segmented resume) | Internal to `SegmentedHttpDownloadStrategy`; not in events |

### Wiring work required

1. **Connect `DownloadManager` to `CheckpointCoordinator`**: `DownloadManagers.createDefault`
   must accept (or construct) a `DownloadStateStore` and create a `CheckpointCoordinator`
   that observes it. Currently `DownloadManagers.createDefault()` takes a `DownloadConfig`
   only and has no persistence wiring.

2. **Expose internal state to the coordinator**: two approaches were evaluated.

   **Option A — Enriched public events**: add `partialPath`, `etag`, and `List<SegmentSnapshot>`
   to `DownloadStartedEvent` and `DownloadProgressEvent`.
   
   _Problem:_ segmented downloads run multiple virtual threads concurrently, each emitting its
   own `DownloadProgressEvent` after each buffer write. There is no barrier between VTs.
   Assembling a `List<SegmentSnapshot>` from within an event handler therefore captures an
   inconsistent mid-flight state across segments. Additionally, emitting segment lists on every
   buffer write (potentially thousands per second) creates GC pressure.

   **Option B — Internal checkpoint port (recommended)**: define a `CheckpointSource` interface
   in `katabasis-core`'s internal packages. The executing strategy (or its owning task) implements
   this interface and exposes `DownloadSnapshot buildSnapshot()`. The coordinator calls
   `source.buildSnapshot()` at flush time rather than assembling a snapshot from events.
   
   _Advantages:_ single consistent snapshot assembled under the strategy's own synchronization;
   no event API change; no GC pressure from per-event list allocation; naturally supports future
   FTP/S3 strategies (each provides its own `CheckpointSource` appropriate to its protocol).
   
   _Coupling:_ `CheckpointCoordinator` holds a map of `CheckpointSource` per download ID;
   sources are registered at download submission and deregistered at terminal event. The core
   module exposes `CheckpointSource` as an internal API; `katabasis-storage-json` reads it
   but does not depend on HTTP-specific classes.

   **Recommendation for T10:** implement Option B.

3. **Partial file path**: the partial file must be tracked and included in the snapshot so
   recovery can locate the file. `HttpDownloadStrategy` and `SegmentedHttpDownloadStrategy`
   both write to `targetPath` directly — if the convention is kept, `partialPath` can remain
   null and recovery uses `targetPath`. If a `.partial` rename is introduced, the path must
   be recorded from the moment the file is opened.

4. **Terminal checkpoint ordering**: the coordinator must save a final `FAILED` or `PAUSED`
   snapshot before the executor releases the semaphore permit. Currently the event listener
   is asynchronous; a `close()` call or an explicit barrier may be needed to guarantee the
   terminal checkpoint is durable before the manager returns from `download()`.

### Not required for T10

- Recovery logic (T11).
- Resumption of paused downloads across restarts.
- Schema migration (no schema change needed for initial wiring).

---

## T11 Handoff — Persistent Recovery (K-0.2-T11)

### Recovery preconditions

T10 must be complete and producing snapshots with populated `etag` and `segments` (for
segmented downloads) before T11 is meaningful.

### Minimum recoverable snapshot

A snapshot is recoverable if:
- `status` is `IN_PROGRESS` or `PAUSED`.
- `sourceUri` and `targetPath` are non-null and structurally valid.
- The partial file exists and its size is ≥ `confirmedBytes`.

A snapshot with `confirmedBytes == 0` is technically recoverable (recovery from byte 0
re-downloads the whole file) but is equivalent to a fresh download and may be discarded.

### Recovery validation sequence

1. **Discover:** call `findRecoverable()` on `DownloadStateStore` at startup.
2. **File existence:** check that `effectivePartialPath()` exists. If absent, treat as
   unrecoverable; optionally prompt the user.
3. **File size consistency:** check that the partial file size ≥ `confirmedBytes`. A
   truncated file (size < `confirmedBytes`) must not be resumed — it means data the snapshot
   claims was written was actually lost.
4. **Remote resource identity:** if `etag` is non-null, issue a `HEAD` request to `sourceUri`
   and verify the ETag matches. If the ETag changed (or the server now returns no ETag), the
   resource has changed; partial data is unusable. The download must restart from byte 0
   (or fail with an informative error).
5. **Resume offset determination:**
   - Single-stream: resume from `confirmedBytes`.
   - Segmented: for each `SegmentSnapshot`, resume from `startByte + confirmedBytes`. Segments
     where `confirmedBytes == endByte - startByte + 1` are complete and may be skipped.
6. **Re-submit:** submit a `DownloadRequest` annotated with the resume offset (requires
   extending the request model or the strategy contract to accept a starting byte range).
7. **Lifecycle re-entry:** the recovered download re-enters the normal `DownloadManager`
   lifecycle; existing event types (`Started`, `Progress`, `Completed`, etc.) apply as usual.

### Not in scope for T11

- Automatic recovery on startup without user confirmation.
- Recovery of downloads that failed (non-interrupted failure mode).
- Cross-host recovery (ETag may not be meaningful across different servers).

---

## Existing Storage Findings — Disposition

| ID | Description | Disposition |
|----|-------------|-------------|
| STOR-001 | Lock-map race: `delete()` removed lock while holding it | **Resolved** (K-STOR-HARDEN-001) |
| STOR-002 | Path traversal in `findById`/`delete` — no ID validation | **Resolved** (K-STOR-HARDEN-001) |
| STOR-003 | Checkpoint resurrection after terminal deletion | **Resolved** (K-STOR-HARDEN-001) |
| STOR-004 | ADR-009 amendment re: atomic-write tradeoff | **Resolved** (superseded by this ADR-002) |
| STOR-005 | `StorageDocument.revision` Javadoc misdescribed "optimistic concurrency" | **Resolved** (K-STOR-HARDEN-001) |
| STOR-006 | `fsync` not performed after rename | **Accepted limitation** — documented in class Javadoc and this ADR; deferred beyond phase 0.2 |
| STOR-007 | Leftover `.tmp` file on mid-write IOException | **Resolved** (K-STOR-HARDEN-001) |
| STOR-008 | `close()` did not await scheduler termination | **Resolved** (K-STOR-HARDEN-001) |
| STOR-009 | Windows reader race during rename | **Accepted limitation** — documented; Windows not a phase 0.2 target |
| STOR-010 | Corrupt manifest quarantine | **Deferred beyond phase 0.2** — required before production hardening |
| STOR-011 | `PathAdapter` cross-OS portability | **Deferred beyond phase 0.2** |
| STOR-012 | `handleFailed` with no prior `Started` produced no snapshot | **Resolved** (K-STOR-HARDEN-001) |
| STOR-013 | `currentRevision` reset to 0 on read failure, breaking monotonicity | **Resolved** (K-STOR-HARDEN-001) |
| STOR-014 | Low-priority design improvement | **Deferred** |
| STOR-015 | Low-priority design improvement | **Deferred** |

**Required before T10:** none of the deferred findings block T10 wiring.

**Required before T11:** STOR-010 (corrupt manifest quarantine) is desirable before T11, since
recovery must handle corruption gracefully. The current skip-and-log behavior is acceptable
for a first T11 implementation but should be addressed before shipping recovery to users.

---

## Consequences

### What becomes easier

- Manifests are human-readable; failed or interrupted downloads can be inspected and manually
  repaired without tooling.
- Schema migration is explicit and independently testable.
- Each download's state is isolated; corruption of one file does not affect others.
- No runtime dependency beyond the JDK and JSON-B (already a katabasis-storage-json dependency).
- The `DownloadStateStore` port is decoupled; a SQLite adapter can be added later without
  changing any caller.

### What becomes harder

- Durable persistence requires careful `fsync` handling not yet implemented.
- Multi-process writers require external file locking or separate directories.
- Querying across all downloads (e.g., "find all interrupted downloads for host X") requires
  reading and decoding every file.
- Windows rename atomicity limitations require additional testing if Windows support is added.
