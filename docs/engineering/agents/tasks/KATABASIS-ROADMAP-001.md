# KATABASIS-ROADMAP-001 — Consolidación y planificación multiprotocolo

## Metadata

| Field | Value |
| --- | --- |
| Task ID | KATABASIS-ROADMAP-001 |
| Type | Architecture / Planning |
| Status | DONE |
| Assigned | Clio |
| Framework | OSK |
| Date | 2026-11-03 |

## Objective

Inspect the actual state of the Katabasis repository and produce a technical, incremental, executable roadmap covering HTTP reliability consolidation, CLI and persistence, multiprotocol expansion (FTP/S3), and BitTorrent feasibility — without implementing any feature or modifying production code.

## Scope

- katabasis-core, katabasis-ui, katabasis-cli
- Phases 0.1 through 0.4
- Explicitly excludes Anabasis

## Authorized Outputs

- Current State Assessment with evidence-based capability classification
- Architecture Gap Analysis
- Task Backlog linked to phases
- Dependency Map
- Risk Register
- ADR Candidates
- Definition of Done per release
- Roadmap maestro Katabasis 0.1–0.4

## Constraints

- No production code changes
- No public API modifications without justification
- No new frameworks introduced
- No assumptions that HTTP strategies apply uniformly to all protocols

## Completion Criteria

- Repository state verified against evidence
- Four releases scoped with clear objectives
- All pending items classified and backlogged
- Risks and architectural decisions documented
- Roadmap ready for human review and approval

## Related Records

- Report: [KATABASIS-ROADMAP-001-roadmap-planning.md](../reports/KATABASIS-ROADMAP-001-roadmap-planning.md)
- Roadmap: [ROADMAP.md](../../../roadmap/ROADMAP.md)
- Architecture: [docs/knowledge/architecture/katabasis-core.md](../../../knowledge/architecture/katabasis-core.md)
- ADR Candidates: [docs/adr/README.md](../../../adr/README.md)
