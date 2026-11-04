# K-DOC-003 — Add module, class, and sequence diagrams for the core download flow

## Metadata

| Field | Value |
| --- | --- |
| Task ID | K-DOC-003 |
| Phase | Documentation governance |
| Status | DONE |
| Date | 2026-11-03 |
| Depends On | K-DOC-001 |

## Objective

Close the diagram gap in project knowledge: document the current module structure, the class model, and the core download lifecycle as maintained diagrams, and provide editorial visual versions built with the `diagram-design` skill.

## Authority

- Repository code for current behavior.
- [docs/knowledge/architecture/katabasis-core.md](../../../knowledge/architecture/katabasis-core.md) is the canonical architecture page; diagrams must agree with it.
- [docs/OSK.md](../../../OSK.md) governs placement: structure under `knowledge/architecture/`, cross-cutting sequences under `knowledge/flows/`.

## Authorized Scope

- Create Mermaid-based knowledge pages under `docs/knowledge/architecture/` and `docs/knowledge/flows/`.
- Create self-contained HTML/SVG editorial diagrams using the installed `diagram-design` skill.
- Update navigation READMEs and link the diagrams from the canonical architecture page.
- Correct any code-contradicted claim discovered during verification.
- Do not change application code, `pom.xml`, roadmap, ADRs, or historical engineering records.

## Related Records

- Follows: [K-DOC-001](K-DOC-001.md)
- Report: [K-DOC-003-diagrams.md](../reports/K-DOC-003-diagrams.md)
