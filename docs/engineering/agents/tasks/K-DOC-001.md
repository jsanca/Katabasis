# K-DOC-001 — Reconcile project documentation with current code state

## Metadata

| Field | Value |
| --- | --- |
| Task ID | K-DOC-001 |
| Phase | Documentation governance |
| Status | DONE |
| Date | 2026-11-03 |
| Depends On | — |

## Objective

Reconcile the developer-facing documentation with the repository-verified current state after discrepancies were found during an initial exploration. Scope is limited to `README.md`, `CLAUDE.md`, and `AGENTS.md`.

## Authority

- Repository code is authoritative for current behavior.
- [docs/knowledge/architecture/katabasis-core.md](../../../knowledge/architecture/katabasis-core.md) is the canonical current-state architecture page (last verified 2026-11-03).
- [docs/roadmap/ROADMAP.md](../../../roadmap/ROADMAP.md) owns committed direction.

## Authorized Scope

- Correct factual statements in `README.md`, `CLAUDE.md`, and `AGENTS.md` that contradict the code or the canonical knowledge page.
- Do not change application code, `pom.xml`, the roadmap, ADRs, the canonical knowledge page, or historical engineering records.
- Do not introduce new claims that cannot be verified in the repository.

## Related Records

- Report: [K-DOC-001-documentation-reconciliation.md](../reports/K-DOC-001-documentation-reconciliation.md)
- Canonical architecture page: [katabasis-core.md](../../../knowledge/architecture/katabasis-core.md)
- Roadmap: [ROADMAP.md](../../../roadmap/ROADMAP.md)
