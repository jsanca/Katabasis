# K-STOR-HARDEN-001 — Storage Hardening Report

## Status

Complete.

## Summary

Eight confirmed defects identified in K-STORAGE-REVIEW-001 were fixed across two source files (`FileDownloadStateStore.java` and `CheckpointCoordinator.java`) and one documentation-only record (`StorageDocument.java`). Seven new regression tests were added (three in `FileDownloadStateStoreTest`, two in `CheckpointCoordinatorTest`, plus one helper method). All 70 tests in `katabasis-storage-json` pass; full project build (katabasis-parent, katabasis-core, katabasis-storage-json, katabasis-cli, katabasis-ui) has no regressions.

**Test counts:** 70 tests in `katabasis-storage-json` (26 in `FileDownloadStateStoreTest`, 11 in `CheckpointCoordinatorTest`, 20 in `JsonEnvelopeCodecTest`, 13 in `StorageKeyTest`).

## Finding Disposition

| Finding | Description | Disposition |
| --- | --- | --- |
| STOR-001 | Lock-map race in `delete()` — `locks.remove` while holding lock | Fixed |
| STOR-002 | Path traversal in `findById`/`delete` — no ID validation on read/delete paths | Fixed |
| STOR-003 | `CheckpointCoordinator` resurrection race — flush iterator can re-save after terminal remove | Fixed |
| STOR-004 | ADR-009 amendment — document the atomic-write tradeoff | Deferred (documentation only; separate artifact) |
| STOR-005 | `StorageDocument.revision` Javadoc incorrectly says "optimistic concurrency" | Fixed (documentation) |
| STOR-006 | `fsync` not performed after rename | Deferred; tradeoff documented in class Javadoc |
| STOR-007 | Leftover `.tmp` file on mid-write `IOException` | Fixed |
| STOR-008 | `close()` does not await scheduler termination | Fixed |
| STOR-009 | Windows reader race (reader observes partial rename) | Deferred; documented limitation |
| STOR-010 | Corrupt manifest quarantine | Deferred |
| STOR-011 | `PathAdapter` cross-OS portability | Deferred |
| STOR-012 | `handleFailed` with no prior `Started` produces no snapshot | Fixed (side effect of STOR-003 fix) |
| STOR-013 | `currentRevision` resets to 0 on read failure, breaking monotonicity | Fixed |
| STOR-014 | Low-priority design improvement | Deferred |
| STOR-015 | Low-priority design improvement | Deferred |

## Files Changed

| File | Change |
| --- | --- |
| `katabasis-storage-json/src/main/java/jsanca/katabasis/storage/json/internal/store/FileDownloadStateStore.java` | STOR-001: removed `locks.remove`; STOR-002: added `SAFE_ID` and validate in `resolve()`; STOR-007: `atomicWrite` deletes `.tmp` on error + `cleanupOrphanedTmpFiles()` at startup; STOR-013: `currentRevision` throws instead of resetting; Javadoc updates |
| `katabasis-storage-json/src/main/java/jsanca/katabasis/storage/json/api/CheckpointCoordinator.java` | STOR-003: `handleCompleted`/`handleCancelled`/`handleFailed` use `compute`; `flushPending` uses `computeIfPresent`; STOR-008: `close()` deregisters listener first, awaits termination; STOR-012: `handleFailed` builds snapshot even without prior `Started` |
| `katabasis-storage-json/src/main/java/jsanca/katabasis/storage/json/api/StorageDocument.java` | STOR-005: Javadoc revised — "internal monotonic write counter", not "optimistic concurrency" |
| `katabasis-storage-json/src/test/java/jsanca/katabasis/storage/json/internal/store/FileDownloadStateStoreTest.java` | Added 3 regression tests: path traversal (3 assertions), concurrent save+delete race (STOR-001), orphaned `.tmp` cleanup at startup |
| `katabasis-storage-json/src/test/java/jsanca/katabasis/storage/json/api/CheckpointCoordinatorTest.java` | Added 2 regression tests: `failedEventWithoutPriorStartedStillPersistsSnapshot` (STOR-012), `terminalEventAfterScheduledFlushDoesNotResurrectManifest` (STOR-003); helper `lastIndexOf` |

## Evidence

```
[INFO] Tests run: 70, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  8.127 s
[INFO] Finished at: 2026-11-04T09:37:17-07:00
```

Full reactor build also succeeded:

```
[INFO] katabasis-parent ................................... SUCCESS
[INFO] katabasis-core ..................................... SUCCESS
[INFO] katabasis-storage-json ............................. SUCCESS
[INFO] katabasis-cli ...................................... SUCCESS
[INFO] katabasis-ui ....................................... SUCCESS
[INFO] BUILD SUCCESS
```

## What Was Not Addressed

- **STOR-004**: Requires an ADR amendment artifact. No code change needed; deferred to documentation work.
- **STOR-006 (fsync)**: The tradeoff (journaling FS rename atomicity vs. non-journaling power-fail risk) is already noted in the class Javadoc. Full `fsync` requires platform-specific API and was out of scope.
- **STOR-009 (Windows reader race)**: Readers are explicitly documented as lock-free and tolerant of decode failure. Windows rename semantics are a known limitation documented in the class header.
- **STOR-010 (corrupt manifest quarantine)**: Quarantine/recovery logic requires a broader design decision (quarantine directory, alert mechanism). Deferred.
- **STOR-011 (PathAdapter cross-OS)**: Low-impact portability issue; deferred.
- **STOR-014, STOR-015**: Low-priority design improvements without confirmed defect; deferred.
