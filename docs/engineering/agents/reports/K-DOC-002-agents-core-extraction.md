# K-DOC-002 — AGENTS-CORE Extraction — Report

## Status

Complete

## Objective

Resolve finding #11 of [K-DOC-001](K-DOC-001-documentation-reconciliation.md): eliminate the duplicated `AGENTS.md` / `CLAUDE.md` content by extracting shared guidance into a canonical `AGENTS-CORE.md` and leaving both files as thin tool-specific adapters.

## Summary

All shared sections previously present in `CLAUDE.md` and `AGENTS.md` were moved verbatim (including the K-DOC-001 corrections) into `AGENTS-CORE.md`:

Build & Test, Packaging, Architecture (module layout, core key concepts, JPMS boundary, interface + factory, domain types, concurrency, in-memory note, UI architecture, CLI status), Planned Features, Coding conventions, Test conventions, Record + Builder pattern, and Reporting.

`AGENTS.md` now contains only: the Codex adapter note, a reference to `AGENTS-CORE.md`, Codex-specific pointers, and the auto-managed OSK workspace and skills blocks. `CLAUDE.md` now contains only: the Claude Code adapter note, a reference plus `@AGENTS-CORE.md` import, Claude Code-specific pointers, and the auto-managed OSK workspace block.

One additional inconsistency was corrected during extraction: the Record + Builder example used `DownloadRequest` with a `checksum` field, which does not match the current `DownloadRequest(URI sourceUri, Path targetPath)` record. The example was renamed to an explicitly illustrative `TransferRequest` type so it no longer contradicts the real API.

## Files Changed

- `AGENTS-CORE.md` — new canonical shared guidance.
- `AGENTS.md` — reduced to Codex adapter; OSK auto-managed blocks preserved intact.
- `CLAUDE.md` — reduced to Claude Code adapter; OSK auto-managed block preserved intact.
- `docs/PROJECT.md` — repository map updated with the new canonical file and updated adapter descriptions.
- `docs/engineering/agents/tasks/K-DOC-002.md` — task record.
- `docs/engineering/agents/reports/K-DOC-002-agents-core-extraction.md` — this report.
- `docs/engineering/ENGINEERING_LOG.md` — added the K-DOC-002 row.
- `docs/engineering/agents/reports/K-DOC-001-documentation-reconciliation.md` — annotated the resolved follow-up.

No application code, build files, roadmap, ADRs, or knowledge pages were modified.

## Evidence

Section inventory comparison between the previous `CLAUDE.md` (`git show HEAD:CLAUDE.md`) and `AGENTS-CORE.md`:

```
## Build & Test
### Packaging
## Architecture
### Module layout
### `katabasis-core` — key concepts
### JPMS API boundary
### Interface + Factory pattern
### Domain types
### Concurrency model
### In-memory only (by design)
### `katabasis-ui` — UI architecture
### `katabasis-cli` — status
## Planned Features (do not implement without explicit request)
## Coding conventions
## Test conventions
## Record + Builder pattern
## Reporting
```

The only previous heading not present in `AGENTS-CORE.md` is `## OSK Workspace`, which remains in each adapter because OSK manages it through `<!-- OSK:BEGIN -->` markers.

Adapter sizes after extraction:

```
  41 AGENTS.md
  26 CLAUDE.md
 191 AGENTS-CORE.md
```

## Validation

```
python3 relative-link check over README.md, AGENTS.md, AGENTS-CORE.md, CLAUDE.md, docs/**.md
checked 24 markdown files
no broken relative links

grep -c "^## Build & Test" AGENTS.md CLAUDE.md AGENTS-CORE.md
AGENTS.md:0
CLAUDE.md:0
AGENTS-CORE.md:1

grep -c "OSK:BEGIN|OSK:SKILLS:BEGIN" AGENTS.md CLAUDE.md
AGENTS.md:2
CLAUDE.md:1
```

OSK auto-managed blocks are present and unmodified. No build or test run: documentation-only change with zero code modification.

## Limitations

- The adapters rely on the consuming agent following the reference. `CLAUDE.md` additionally uses Claude Code's `@AGENTS-CORE.md` import; `AGENTS.md` is a plain reference that requires the agent to read the file.
- `AGENTS-CORE.md` is canonical for agent operating guidance, not for project knowledge; architecture facts remain in `docs/knowledge/`.
- The content was moved, not re-reviewed beyond the checks recorded in K-DOC-001.

## Open Follow-Up

- If a third agent integration is added, create another adapter that references `AGENTS-CORE.md` rather than copying it.
- Optional: add a CI or scripted check that fails when `AGENTS.md` or `CLAUDE.md` reintroduces duplicated shared sections.

## Related Records

- Task: [K-DOC-002.md](../tasks/K-DOC-002.md)
- Predecessor: [K-DOC-001](K-DOC-001-documentation-reconciliation.md)
- Canonical guidance: [AGENTS-CORE.md](../../../../AGENTS-CORE.md)
- Project context: [docs/PROJECT.md](../../../PROJECT.md)
