# K-DOC-003 — Module, Class, and Sequence Diagrams — Report

## Status

Complete

## Objective

Add the missing diagrams for the core download engine: module structure, class model, and runtime sequences. Deliver Mermaid sources inside `docs/knowledge/` plus editorial HTML/SVG versions built with the `diagram-design` skill.

## Summary

Three knowledge pages were created or extended:

- `docs/knowledge/architecture/module-map.md` — Mermaid flowchart of the three Maven/JPMS modules, the exported `api.*` boundary, and the undeclared CLI dependency.
- `docs/knowledge/architecture/class-model.md` — three Mermaid class diagrams: public management surface, public event hierarchy, and internal collaborators.
- `docs/knowledge/flows/download-lifecycle.md` — three Mermaid sequence diagrams: happy path (including the single vs segmented decision), pause/resume, and cancellation. Failure paths are described in text because they reuse the same terminal flow.

Navigation and cross-links were updated in `docs/knowledge/README.md`, `docs/knowledge/architecture/README.md`, and `docs/knowledge/architecture/katabasis-core.md`.

Editorial diagrams were produced with the installed `diagram-design` skill:

- `docs/knowledge/architecture/diagrams/module-map.html`
- `docs/knowledge/architecture/diagrams/core-collaborators.html`
- `docs/knowledge/flows/diagrams/download-lifecycle.html`

All three are single-file HTML with inline SVG, the accessible-SVG contract, and the skill's editorial design system.

## Diagram gap classification

| Diagram | Prior state | Classification |
| --- | --- | --- |
| Module map | Absent | Missing |
| Public API class model | Absent | Missing |
| Internal collaborator model | Absent | Missing |
| Happy-path download sequence | Absent | Missing |
| Pause/resume sequence | Absent | Missing |
| Cancel/failure sequence | Absent | Missing |

## Additional correction found during verification

`api.model.DownloadProgress` is an empty placeholder class (`public class DownloadProgress { }`) with a TODO comment and no references outside its own declaration. The canonical architecture page listed it in the domain-model table as a record carrying downloaded and total bytes. The table was corrected and a note added that progress values live in `DownloadProgressEvent`. This is the only change to the canonical page.

## Files Changed

- `docs/knowledge/architecture/module-map.md` — new Mermaid module map and explanation.
- `docs/knowledge/architecture/class-model.md` — new Mermaid class model (public API + internal collaborators).
- `docs/knowledge/architecture/diagrams/module-map.html` — new editorial module map.
- `docs/knowledge/architecture/diagrams/core-collaborators.html` — new editorial collaborator diagram.
- `docs/knowledge/flows/README.md` — new flows area index.
- `docs/knowledge/flows/download-lifecycle.md` — new sequence documentation.
- `docs/knowledge/flows/diagrams/download-lifecycle.html` — new editorial sequence diagram.
- `docs/knowledge/README.md` — added area navigation.
- `docs/knowledge/architecture/README.md` — added diagram pages and editorial assets.
- `docs/knowledge/architecture/katabasis-core.md` — linked visual models; corrected `DownloadProgress`.
- `docs/engineering/agents/tasks/K-DOC-003.md` — task record.
- `docs/engineering/agents/reports/K-DOC-003-diagrams.md` — this report.
- `docs/engineering/ENGINEERING_LOG.md` — added the K-DOC-003 row.

No application code, build files, roadmap, or ADRs were modified.

## Evidence

Behavior verified directly in code before drawing:

- `DownloadManagers.DefaultDownloadManager.download()` registers the task, delegates to the executor, and returns `DownloadSubmissionResult` immediately.
- `DefaultDownloadStrategyResolver` maps only `HTTP` and `HTTPS`; other schemes throw.
- `HttpDownloadOrchestrator` probes `Accept-Ranges` and `segmentMinSizeBytes` (default 5 MB) before choosing a strategy.
- `HttpDownloadStrategy` and `SegmentedHttpDownloadStrategy` emit progress per buffer/chunk and catch cancellation through `HttpDownloadSupport`.
- `PauseCoordinator.awaitIfPaused` polls cancellation every 250 ms; `SegmentedHttpDownloadStrategy` guards pause/resume events with shared `AtomicBoolean` flags (K-0.1-T01).
- `DefaultDownloadManager.runDownload` removes the registry entry and clears pause state in `finally`.

## Validation

```
python3 .osk/skills/diagram-design/scripts/self_check.py \
  docs/knowledge/architecture/diagrams/module-map.html \
  docs/knowledge/architecture/diagrams/core-collaborators.html \
  docs/knowledge/flows/diagrams/download-lifecycle.html
OK docs/knowledge/architecture/diagrams/module-map.html
OK docs/knowledge/architecture/diagrams/core-collaborators.html
OK docs/knowledge/flows/diagrams/download-lifecycle.html
```

Mermaid grammar validation:

```
mermaid_extract.py: module-map.md → 1 flowchart, 5 nodes, 3 edges
mermaid_extract.py: download-lifecycle.md → 3 sequence diagrams, no dangling edges
mermaid.parse (Mermaid 11.17.2): class-model.md blocks 0, 1, 2 → all ok
```

Rendering validation: the three editorial diagrams and the three class diagrams were rendered in Chromium; no console errors. The editorial files were checked against the skill's pre-output checklist (4px grid, orthogonal connectors, label masks, bottom legend, accessible SVG).

Documentation links: relative-link check across `README.md`, `AGENTS.md`, `AGENTS-CORE.md`, `CLAUDE.md`, and `docs/**.md` reported no broken links.

No build or test run: documentation-only change with zero code modification.

## Limitations

- The editorial diagrams use the `diagram-design` default skin. The repository has no brand token source, so the default neutral palette was kept; this was not customized with the skill's onboarding flow.
- The Mermaid happy-path sequence uses seven participants and fifteen messages, above the skill's editorial complexity budget. That budget applies to the hand-laid editorial diagram, which stays at five lifelines and ten messages; the Mermaid version is comprehensive rather than editorial.
- Diagrams are static documentation. They will drift if the flow changes; the canonical architecture page now links them, but there is no automated check that they match the code.
- No PNG/SVG exports were produced. The skill treats export as a manual, on-request step.

## Open Follow-Up

- Regenerate the diagrams when the strategy resolver, pause coordinator, or event contract changes.
- Consider a lightweight CI check that validates Mermaid blocks with `mermaid.parse` and re-runs `self_check.py` on the editorial files.
- If a visual identity is adopted, run `diagram-design` onboarding and regenerate the editorial variants.

## Related Records

- Task: [K-DOC-003.md](../tasks/K-DOC-003.md)
- Predecessor: [K-DOC-001](K-DOC-001-documentation-reconciliation.md)
- Canonical architecture: [katabasis-core.md](../../../knowledge/architecture/katabasis-core.md)
- Module map: [module-map.md](../../../knowledge/architecture/module-map.md)
- Class model: [class-model.md](../../../knowledge/architecture/class-model.md)
- Flow: [download-lifecycle.md](../../../knowledge/flows/download-lifecycle.md)
