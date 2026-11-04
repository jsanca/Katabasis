# Maven Site Reference

Read this reference only when Maven is the selected project-native ecosystem. It is guidance for evidence-based, project-specific integration; it does not prescribe a universal Maven plugin stack, version, output path, or configuration.

## Detect

Before proposing a change, inspect:

- Maven project markers and wrapper/command conventions;
- parent, aggregator, and module relationships;
- existing site, reporting, documentation, JavaDoc, and resource/plugin configuration;
- current documentation locations, generated-output policy, CI expectations, and existing project commands; and
- project constraints, approved runtime/tool availability, and authority to modify build configuration.

Maven presence alone is not evidence that Maven Site is configured, suitable, or the only native publication path.

## Preserve Project Ownership

Treat existing `pom.xml` files, parent inheritance, plugin management, reporting configuration, and project documentation structure as project-owned evidence. Integrate conservatively when explicitly authorized; do not replace configuration, select versions by convenience, or install dependencies/runtimes silently.

Under ADR-008, OSK installation does not authorize arbitrary `pom.xml` mutation. If semantic integration is required, create or use an auditable task that records detected evidence, expected outcome, constraints, changed files, native validation, and readiness state.

## Plan the Native Mapping

Determine from project evidence how the selected publication should expose:

- canonical Markdown knowledge and standard Mermaid rendering;
- JavaDoc/API reference;
- useful examples or reference material;
- reader-oriented navigation; and
- project reports only where they materially help readers and remain visibly historical.

Do not force every available source into the site. Preserve the canonical/generated distinction from the publication model.

## Integrate Only When Authorized

When configuration changes are authorized:

1. inspect current parent/module/plugin interactions and existing lifecycle behavior;
2. identify compatible native Maven mechanisms from the project's actual ecosystem evidence;
3. make the smallest conservative merge that preserves existing behavior;
4. document assumptions, limitations, and any unresolved conflicts; and
5. run the project's native verification before reporting readiness.

If the appropriate mechanism is uncertain, stop with an explicit pending-integration outcome rather than guessing a universal configuration.

## Verify

Identify and execute project-native checks appropriate to the integration, such as:

- site/publication generation;
- JavaDoc generation where applicable;
- Mermaid rendering for selected canonical diagrams;
- generated output and entry-page presence;
- expected navigation targets and internal links where practical; and
- preservation of canonical source files outside authorized changes.

Record actual commands, output locations, results, skipped checks, and limitations. Structural success does not prove semantic documentation quality.

## Handoff Boundary

Use this conceptual flow when a package is present but the project is not ready:

```text
OSK package delivery
        ↓
detected Maven/project integration requirement
        ↓
auditable authorized agent task
        ↓
conservative project-specific integration
        ↓
native Maven validation
        ↓
READY or explicit non-ready outcome
```

Do not implement `diagram-design`, rich SVG, a universal publisher command, or OSK-managed Maven dependencies as part of this Maven-first reference.

## Related Records

- [ADR-008 — Deterministic Installation and Agent-Performed Project Integration](../../../../../docs/adr/ADR-008-deterministic-installation-and-agent-performed-project-integration.md)
- [ADR-011 — Canonical Documentation Sources and Generated Publication Artifacts](../../../../../docs/adr/ADR-011-canonical-documentation-sources-and-generated-publication-artifacts.md)
- [Publication model](publication-model.md)
