# K-DOC-002 — Extract canonical agent guidance into AGENTS-CORE.md

## Metadata

| Field | Value |
| --- | --- |
| Task ID | K-DOC-002 |
| Phase | Documentation governance |
| Status | DONE |
| Date | 2026-11-03 |
| Depends On | K-DOC-001 |

## Objective

Resolve finding #11 of K-DOC-001: `AGENTS.md` and `CLAUDE.md` duplicated the same guidance and had already drifted. Extract the shared content into a single canonical file, `AGENTS-CORE.md`, and reduce both files to thin tool-specific adapters that reference it.

## Authority

- Repository code for current behavior.
- [AGENTS-CORE.md](../../../../AGENTS-CORE.md) becomes the single source for shared agent operating guidance.
- [docs/knowledge/architecture/katabasis-core.md](../../../knowledge/architecture/katabasis-core.md) remains canonical for architecture facts; agent guidance does not replace project knowledge.
- [docs/OSK.md](../../../OSK.md) governs documentation placement.

## Authorized Scope

- Create `AGENTS-CORE.md` with the shared guidance previously duplicated in both adapters.
- Rewrite `AGENTS.md` and `CLAUDE.md` as adapters containing only tool-specific content and their auto-managed OSK blocks.
- Update the repository map in `docs/PROJECT.md`.
- Do not change application code, `pom.xml`, roadmap, ADRs, canonical knowledge pages, or historical records.

## Related Records

- Follows: [K-DOC-001](K-DOC-001.md)
- Report: [K-DOC-002-agents-core-extraction.md](../reports/K-DOC-002-agents-core-extraction.md)
