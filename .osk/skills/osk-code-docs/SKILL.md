# Code Documentation Publication

## Mission

Transform selected authoritative project knowledge and native source documentation into a useful, traceable static documentation experience for human readers while preserving canonical semantic sources for agents.

## Scope

### Covers

- Discovering and classifying project knowledge, source/API documentation, executable evidence, decisions, and history before publication planning.
- Planning reader-oriented navigation and selecting material that supports the requested documentation experience.
- Using an already-integrated native ecosystem workflow to generate HTML, rendered Mermaid diagrams, navigation, and API-reference links.
- Verifying structural publication evidence and reporting missing, stale, ambiguous, contradictory, or unpublishable inputs.

### Does not cover

- Curating, reconciling, authorizing, or inventing durable project knowledge; `osk-knowledge-curator` and project authorities own those responsibilities.
- Arbitrary build-system management, dependency/runtime installation, universal publisher selection, or replacement of native documentation generators.
- Repository intelligence, dependency resolution, agent orchestration, MCP/AHP, OpenSpec workflows, or documentation-drift intelligence.
- Required `diagram-design` use, rich SVG generation, or ownership/lifecycle of external capabilities.
- Treating generated HTML, diagrams, navigation, indexes, mirrors, or API pages as canonical knowledge or required agent context.

## Responsibilities

- Keep current durable knowledge, source/API authority, executable evidence, decision/reference material, historical engineering evidence, and generated publication distinct.
- Treat canonical Markdown as narrative source and Mermaid embedded in or referenced by it as the canonical textual diagram source.
- Select available material according to reader intent; availability alone does not require publication.
- Preserve source-to-output traceability and use generated artifacts only as derived, reproducible representations.
- Prefer deterministic mechanisms for declared repeatable operations and reserve agent judgment for selection, synthesis, adaptation, ambiguity, reader experience, and gap detection.
- Use project-native API and site-generation mechanisms where appropriate, or create/request an auditable integration handoff when they are not ready.
- Record structural validation, limitations, and follow-up ownership through `osk-engineering-reporting` when the work is non-trivial.

## Boundaries / Constraints

- Publication may transform representation but must not silently transform authority.
- Never hand-edit generated output as a maintenance path. Correct the authority-owning source, then regenerate.
- Do not claim that a successful render, link check, or site build proves semantic correctness, completeness, freshness, or reader usefulness.
- Do not modify project-owned build configuration without repository evidence and explicit authority. Follow ADR-008 when integration remains necessary.
- Use standard Mermaid rendering for v0.1. `diagram-design` remains optional, externally owned future composition.
- Do not guess at missing project facts, choose between conflicting authorities, or make a generated site complete by inventing content.

## Required Inputs

- Publication objective, intended readers, requested scope, and applicable output-retention or commit policy.
- Relevant project knowledge, source/API documentation, examples/tests, ADRs, and historical records when they are intended inputs or navigation targets.
- Evidence of the project's native documentation ecosystem, existing configuration, available commands, expected output location, and validation expectations.
- Constraints including allowed commands, runtime/tool availability, timebox, and authority to change project-owned configuration when integration is requested.

When required inputs are unavailable, produce only the readiness assessment or handoff supported by evidence; do not fabricate publication or readiness.

## Expected Outputs

- A publication plan/input inventory that records selected sources, authority classes, reader intent, representations, and traceability expectations.
- A stated outcome: ready to publish; published with recorded limitations; or `INSTALLED_PENDING_INTEGRATION`.
- When an approved native workflow is ready, derived HTML publication, rendered Mermaid diagrams, navigation, and linked API reference as applicable.
- A durable report or integration handoff containing observed commands/results, generated artifacts, structural validation, source gaps, limitations, and next ownership.

## Workflow

1. **Establish authority and readers.** Identify the publication objective, intended reader journeys, project policy, and candidate sources. Classify their authority before selecting material.
2. **Plan proportionately.** Select useful canonical narrative, diagrams, examples, references, and history without prescribing a universal site structure. Record omissions and gaps explicitly.
3. **Assess native readiness.** Inspect project evidence for a native API/site workflow. Read the applicable ecosystem reference only after the native ecosystem is known.
4. **Handoff missing integration.** When project-specific build configuration or tooling is required, preserve the inspection evidence and create/request an auditable, authorized integration task. Do not claim readiness or change arbitrary configuration as installation work.
5. **Generate through the native workflow.** Use the approved ecosystem commands to render Markdown/Mermaid, generate API reference, and build the site. Keep canonical inputs separate from outputs.
6. **Verify structurally.** Check observed command results, output/entry-page presence, expected navigation and links where practical, Mermaid/API generation, and source traceability.
7. **Report and route gaps.** Record evidence and limitations. Route missing, stale, ambiguous, or contradictory canon to `osk-knowledge-curator` or the designated authority; regenerate only after authoritative correction.

## Questions to Ask

- Which artifacts are authoritative for narrative, diagrams, API reference, evidence, decisions, and historical context?
- Who is expected to use the publication, and what reader journey is useful for this scope?
- Which available material is relevant enough to publish, and what must remain an excluded or linked source?
- Is a native documentation workflow already configured, and what evidence proves its commands and outputs?
- Which Mermaid diagrams are canonical inputs, and is standard rendering sufficient for the stated objective?
- Are there source gaps, authority conflicts, or required build changes that need curation or authorized integration work?

## Escalation Rules

- Conflicting or unknown authority, or a request to publish inferred facts → stop and escalate to `osk-knowledge-curator` or the designated project authority.
- Missing/ambiguous native integration, unavailable required runtime, or needed project build changes → create/request an auditable integration task under ADR-008; do not silently install or merge configuration.
- Mermaid or native API generation failure → preserve canonical input/source authority, report observed evidence, and route the native/project-specific failure without inventing an alternative result.
- High-fidelity diagram request beyond standard Mermaid → obtain explicit scope and availability for optional external-capability composition.
- Timebox expiration or material uncertainty → preserve observed evidence, limitation, and next action through `osk-execution-timebox` and `osk-engineering-reporting`.

## Quality Checklist

- [ ] Selected inputs have an identified authority class, owner, and reader purpose.
- [ ] Current knowledge, source/API authority, executable evidence, ADRs, and engineering history remain distinct.
- [ ] The plan has proportionate navigation and does not invent unavailable sections or facts.
- [ ] Generated outputs are derived, traceable, reproducible, and non-canonical.
- [ ] Mermaid remains canonical and standard rendering is sufficient for v0.1 scope.
- [ ] Native readiness is evidenced or an explicit pending-integration handoff exists.
- [ ] Structural validation, skipped checks, failures, limitations, and ownership are recorded honestly.
- [ ] No successful build is represented as semantic documentation approval.

## Anti-Patterns

- **Site as source of truth** — do not promote generated HTML, SVG, indexes, or mirrors to canonical authority.
- **Generator fixes canon** — do not silently rewrite Markdown, source comments, decisions, or history because a publication exposes a discrepancy.
- **Build-manager expansion** — do not make OSK select universal plugins, install runtimes, or hide native commands behind a generic publisher command.
- **Diagram dependency creep** — do not require `diagram-design` or richer SVG before the Mermaid baseline is insufficient by evidence.
- **Flattened authority** — do not present tests, reports, ADRs, and current knowledge as interchangeable facts.
- **False readiness** — do not call the capability ready while required native integration or validation remains incomplete.

## Dependencies

| Skill ID | Relationship | Required before | Rationale |
| --- | --- | --- | --- |
| osk-knowledge-curator | complements | Resolving knowledge authority, placement, or reconciliation gaps | Curator owns durable knowledge curation and authority decisions. |
| osk-engineering-reporting | requires | Preserving non-trivial publication, integration, or limitation outcomes | Reporting records durable evidence and handoffs. |
| osk-execution-timebox | requires | Bounded or interrupted publication/integration work | Timebox defines recovery and honest stopping behavior. |

`diagram-design` is optional external composition, not a dependency. Native publishers and API generators are project-native integration concerns, not package dependencies.

## Activation Conditions

Apply when a project needs a human-facing static engineering documentation experience from curated knowledge and native API reference; needs publication-readiness assessment; needs source-to-publication traceability; or needs a bounded handoff for missing project-specific documentation integration.

## Evidence and Validation

Use identified authoritative inputs, observed native command results, generated-output inspection, and declared project policy. Structural success can establish output presence, rendering, links, navigation, traceability, and native-generation results. Semantic quality remains a separate curation/review question: a passing site build does not prove correctness, completeness, freshness, or usefulness.

## Supporting Resources

- [Publication model](references/publication-model.md) — read for authority classes, input selection, lifecycle, traceability, validation, and gap behavior.
- [Maven site reference](references/maven-site.md) — read only when a Maven project is the selected native ecosystem.
