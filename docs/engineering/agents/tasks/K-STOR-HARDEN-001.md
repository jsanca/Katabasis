# K-STOR-HARDEN-001 — Storage Hardening: Fix Review Defects

## Metadata

| Field | Value |
| --- | --- |
| Task ID | K-STOR-HARDEN-001 |
| Type | Engineering / Reliability |
| Status | DONE |
| Date | 2026-11-04 |
| Depends On | K-STORAGE-REVIEW-001 |

## Objective

Fix confirmed defects and selected missing guarantees from K-STORAGE-REVIEW-001.

## Authorized Scope

### High — Confirmed defects

- STOR-001: Remove lock-map cleanup from `delete()`; locks retained for store lifetime
- STOR-002: Validate `downloadId` against safe pattern in `resolve()`; closes path traversal on `findById`/`delete`
- STOR-003: Use `ConcurrentHashMap.compute` in terminal handlers and `computeIfPresent` in flush; prevents resurrection race

### Medium — Confirmed defects and missing guarantees

- STOR-007: Cleanup `.tmp` on mid-write `IOException`; scan and remove orphaned `.tmp` files at startup
- STOR-008: `CheckpointCoordinator.close()` deregisters listener first, then awaits scheduler termination before final flush
- STOR-012: `handleFailed` builds and saves a FAILED snapshot even when no prior `Started`/`Progress` event exists (side effect of STOR-003 fix)

### Low — Documentation/correctness

- STOR-005: Downgrade `StorageDocument.revision` Javadoc from "optimistic concurrency" to "internal write counter"
- STOR-013: Distinguish "file missing" from "file unreadable" in `currentRevision`; throw rather than silently reset to 0

### Not in scope

- STOR-004 (ADR-009 amendment) — documentation only; separate artifact
- STOR-006 (fsync) — deferred; documented tradeoff
- STOR-009 (Windows reader race) — documented limitation
- STOR-010 (corrupt manifest quarantine) — deferred
- STOR-011 (PathAdapter cross-OS) — deferred
- STOR-014, STOR-015 — low-priority design improvements

## Related Records

- Review: [K-STORAGE-REVIEW-001-storage-review.md](../reports/K-STORAGE-REVIEW-001-storage-review.md)
- Report: [K-STOR-HARDEN-001-storage-hardening.md](../reports/K-STOR-HARDEN-001-storage-hardening.md)
