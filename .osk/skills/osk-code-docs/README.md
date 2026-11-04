# Code Documentation Publication

`osk-code-docs` is the canonical OSK Skill for turning selected, curated engineering knowledge and native API documentation into a traceable static publication for human readers.

It complements [osk-knowledge-curator](../osk-knowledge-curator/SKILL.md): the curator owns durable knowledge authority and reconciliation; this Skill owns publication planning, derived-output validation, and honest publication limitations.

The package provides a portable contract plus a Maven-first reference. Maven Site and JavaDoc are the first experiment direction, not the Skill's identity or a universal publisher choice. Native documentation tools remain project-owned.

`diagram-design` may later enrich publication as an optional externally owned capability. It is not required for v0.1; the baseline is canonical Markdown/Mermaid rendered through a standard deterministic path.

## Package Contents

- [skill.yaml](skill.yaml) — machine-readable identity and declared resources.
- [SKILL.md](SKILL.md) — portable operating contract.
- [publication model](references/publication-model.md) — deeper portable authority and lifecycle guidance.
- [Maven site reference](references/maven-site.md) — conservative, project-specific Maven integration guidance.
