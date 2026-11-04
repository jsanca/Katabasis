# K-DOC-004 — Document undocumented core classes and public methods with Javadoc

## Metadata

| Field | Value |
| --- | --- |
| Task ID | K-DOC-004 |
| Phase | Documentation governance |
| Status | DONE |
| Date | 2026-11-03 |
| Depends On | — |

## Objective

Add missing Javadoc to `katabasis-core`: every type declaration without a class-level comment, and every public or interface method without documentation, starting with the gaps reported in `DownloadManager`.

## Authority

- Repository code is authoritative for every documented behavior.
- [AGENTS-CORE.md](../../../../AGENTS-CORE.md) coding conventions: public API clarity, documented public methods, `Objects.requireNonNull` validation.
- [docs/OSK.md](../../../OSK.md) for documentation placement.

## Authorized Scope

- Add Javadoc only; no behavior, signature, visibility, or formatting refactors beyond whitespace needed to insert comments.
- Cover `katabasis-core/src/main/java`.
- Do not modify tests, other modules, build files, or the canonical architecture pages.

## Method

1. Static audit of all 76 Java files in `katabasis-core/src/main/java`.
2. Classify each gap as **Missing** with file and line.
3. Verify placeholder types by reference count before describing them as unused.
4. Document and re-run the audit until zero gaps remain.
5. Validate with compile and the core test suite.

## Related Records

- Report: [K-DOC-004-javadoc-core.md](../reports/K-DOC-004-javadoc-core.md)
- Follows: [K-DOC-001](K-DOC-001.md)
