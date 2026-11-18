# Checkpointing

Who owns checkpoint state, how it is persisted, and what guarantees the current implementation does and does not provide.

**Last verified:** 2026-11-18  
**Baseline:** `f999777`  
**Authorities:** ADR-002 (persistence strategy and guarantees), core/storage source at the baseline, K-0.2-T10 and K-0.2-T10-HARDEN-001 reports.

## Ownership

| Layer | Owns | Does not own |
| --- | --- | --- |
| `katabasis-core` | `DownloadSnapshot` / `SegmentSnapshot` domain records; `CheckpointSource`, `CheckpointSourceProvider`, `DownloadStateStore` ports; `CheckpointSource` registration on `DownloadTask` | JSON encoding, file layout, flush scheduling |
| Protocol strategies (internal) | Building internally consistent snapshots from their own accounting state | Persisting them |
| `katabasis-storage-json` | JSON envelope/codec, migrations, `FileDownloadStateStore`, `CheckpointCoordinator`, flush scheduling | Download lifecycle, recovery decisions |
| CLI / UI | Composition: create store + coordinator, wire the shutdown snapshot consumer, close in the right order | Checkpoint content |

Protocol adapters must not own persistent lifecycle state. A future FTP or S3 strategy participates by registering its own `CheckpointSource`.

## Component flow

```mermaid
flowchart LR
    Strategy["HTTP strategy"] -->|registers| Source["CheckpointSource<br/>(on DownloadTask)"]
    Manager["DownloadManager<br/>(CheckpointSourceProvider)"] -->|findSource(id)| Coord["CheckpointCoordinator"]
    Source -->|buildSnapshot(at)| Coord
    Coord -->|save / delete| Port["DownloadStateStore<br/>(core port)"]
    Port --> Adapter["FileDownloadStateStore<br/>(storage-json)"]
    Adapter --> Manifest["Versioned JSON manifest<br/>one file per download"]
```

## CheckpointSource

`CheckpointSource.buildSnapshot(Instant)` is a functional interface registered by the executing strategy after the response is established:

- Single-stream `HttpDownloadStrategy` registers a source with the strong ETag, expected content length, and confirmed byte counter.
- `SegmentedHttpDownloadStrategy` registers a source that aggregates per-segment `AtomicLong` confirmed counts and segment boundaries.

The source captures the strategy's mutable accounting state under its own synchronization, so the coordinator never has to assemble segment state from concurrent progress events.

`CheckpointSourceProvider.findSource(id)` is implemented by `DefaultDownloadManager`. The coordinator receives the manager as the provider; the cast is safe because `createDefault(...)` always returns `DefaultDownloadManager`.

## Coordinator behavior

| Trigger | Action |
| --- | --- |
| `DownloadStartedEvent` | Creates pending `IN_PROGRESS` state at 0 bytes |
| `DownloadProgressEvent` | Coalesces the latest event-derived values |
| `DownloadPausedEvent` | Builds from source when available and saves synchronously |
| Periodic flush (default 5 s) | Pulls `buildSnapshot(now)` from the source, or uses the pending event-derived snapshot |
| `DownloadCompletedEvent` | Deletes the manifest and pending entry |
| `DownloadCancelledEvent` (explicit) | Deletes the manifest and pending entry |
| `DownloadFailedEvent` | Saves a `FAILED` snapshot for inspection |
| `notifyShutdownCheckpoint(snapshot)` | Removes the pending entry, then saves the post-quiescence snapshot |
| `close()` | Deregisters the listener, stops the scheduler (30 s await), final flush |

Save/delete failures are reported as `DownloadCheckpointFailedEvent` through the configured failure consumer; they are not silently swallowed.

## Manifest format

One JSON envelope per download, written to `<downloadId>.json`:

```json
{
  "envelopeVersion": "1",
  "type": "download-snapshot",
  "id": "<downloadId>",
  "schemaVersion": 1,
  "revision": 7,
  "payload": { "downloadId": "...", "status": "IN_PROGRESS", "...": "..." }
}
```

- `schemaVersion` drives migrations; unknown newer versions raise `CodecException` and are skipped.
- `revision` is monotonic per file and is never reset after a read failure.
- Writes go to a sibling `.tmp` file, then `ATOMIC_MOVE`; unsupported filesystems fall back to a logged non-atomic replace.
- Per-download `ReentrantLock`s serialize writes for the same ID; reads are lock-free and tolerate a concurrent replace by skipping corrupt documents.
- Orphaned `.tmp` files are removed at store construction.

## Guarantees and non-guarantees

| Property | Guarantee |
| --- | --- |
| Atomic visibility | Conditional on filesystem `ATOMIC_MOVE` support |
| Survives normal process termination (`kill -9`) | Yes |
| Survives kernel crash / power loss | **No** — no `fsync` of file or parent directory |
| `confirmedBytes` = verified bytes | **No** — bytes accepted by the write API, not checksums |
| Segment snapshots | Confirmed contiguous prefixes per segment |
| ETag | Resource validator, not a checksum |
| Corrupt manifest | Skipped; never authorizes recovery |
| Multi-process writers | Not supported |
| Recovery/resume after restart | **Not implemented — K-0.2-T11 pending** |

Recovery preconditions and validation steps are specified in ADR-002 but must not be described as implemented.

## Shutdown checkpointing

Shutdown checkpoints are authoritative post-quiescence snapshots:

1. The manager cancels active tasks and waits (10 s bound) for quiescence.
2. It builds snapshots from the still-reachable `CheckpointSource` references.
3. Each snapshot goes to `notifyShutdownCheckpoint`, which removes the pending entry before saving.
4. The caller closes the coordinator after the manager.

Shutdown-induced cancellation suppresses `DownloadCancelledEvent`, so the coordinator does not delete the manifest. Explicit cancellation does emit the event and deletes it.

If the executor times out, workers may still be running; the snapshot loop is best-effort. Timeout is logged but not surfaced as an event.

## Known discrepancy

K-0.2-T10-HARDEN-001's report describes a `flushPending()` guard that skips saves when a non-terminal pending entry has no source. At baseline `f999777`, `flushPending()` has no such guard; the implemented protection is `notifyShutdownCheckpoint()` removing the pending entry before the authoritative save. See the curation report under `docs/engineering/` for follow-up.

## Related

- [Architecture: current state](katabasis-core.md)
- [Download lifecycle](../flows/download-lifecycle.md)
- [Module map](module-map.md)
- ADR-002 — Versioned JSON persistence strategy
