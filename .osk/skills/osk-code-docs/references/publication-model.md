# Publication Model

Use this reference after `SKILL.md` establishes that documentation publication is in scope. It explains the portable model without choosing a site generator or implementation language.

## Authority Classes

| Class | Typical source | Publication treatment |
| --- | --- | --- |
| Current durable knowledge | `docs/knowledge/**/*.md` | Authoritative narrative input. |
| Canonical diagram source | Mermaid embedded in or referenced by canonical Markdown | Authoritative textual diagram input. |
| Source/API authority | Source comments and native API documentation | Reference input; does not replace architecture/domain knowledge. |
| Executable evidence | Examples and tests | Include or link only when useful; evidence is scoped to what it demonstrates. |
| Decision/reference material | ADRs and project/build metadata | Link rationale or use for readiness; do not present as current knowledge. |
| Historical engineering evidence | Reports, reviews, checkpoints, logs | Include only when useful as history; do not elevate to current truth. |
| Generated publication | HTML, rendered diagrams, API pages, navigation, indexes, mirrors | Derived output with no independent authority. |

Do not select a winner when authoritative inputs conflict. Preserve the conflict and escalate to the project authority or `osk-knowledge-curator`.

## Input Selection and Reader Planning

Start with intended readers and their questions. Select only authoritative material that supports a useful journey, such as project orientation, architecture/concepts, getting started where authoritative material exists, examples, and API reference. A site need not contain every available document, report, test, or ADR.

Avoid universal information architecture. The project, reader intent, available canon, and native reference determine a proportionate navigation model. Record excluded, unavailable, or weak inputs as limitations rather than manufacturing sections.

## Canonical and Generated Lifecycle

```text
canonical source
      |
      | generate
      v
derived publication

derived publication
      X
      |
      | must not silently mutate
      v
canonical source
```

Generated HTML, SVG, API pages, indexes, navigation, and future mirrors are disposable/reproducible representations. Do not hand-edit them as maintenance. Correct the authority-owning input and regenerate. A project may explicitly commit generated output for deployment, distribution, or host needs, but it remains derived, traceable, and non-canonical.

When a source disappears or cannot be identified, a generated artifact is stale/untraceable material rather than a surviving authority. Report or remove/rebuild it according to project policy.

## Traceability

Preserve enough provenance for a reader or maintainer to identify the source or authority class behind a publication artifact:

- Markdown → HTML links or identifies its canonical narrative input.
- Mermaid → rendered diagram traces to the canonical textual diagram source.
- Source comments → API reference retains source/API and native-generator context.
- ADR/report links retain their decision/history classification in navigation.

No metadata schema is required by this reference. Use the available publisher/project mechanisms and record limitations where sufficient provenance is not available.

## Validation

Structural success can establish observed command results, output/entry-page presence, expected navigation, link resolution where practical, Mermaid rendering, native API generation, and source-to-output traceability.

Semantic quality is different. A passing publication workflow does not establish that the source is accurate, complete, current, or useful. Those questions belong to evidence-based curation, review, and human evaluation.

## Gap and Contradiction Behavior

```text
detect → record evidence → preserve authority → request or produce appropriate engineering work
```

| Condition | Required behavior |
| --- | --- |
| Knowledge absent or incomplete | Report the gap; do not invent narrative material. |
| Canonical material stale, ambiguous, or contradictory | Preserve evidence and route to `osk-knowledge-curator` or the designated authority. |
| Diagram/native reference cannot generate | Preserve canonical/source input, record failure evidence, and report limitation or non-ready outcome. |
| Native integration missing or ambiguous | Create/request an auditable project-specific integration task under ADR-008. |

## Related Boundaries

- `osk-knowledge-curator` owns durable knowledge placement, reconciliation, and authority decisions.
- ADRs explain why significant decisions were made; reports/reviews/logs preserve engineering history. Neither becomes current knowledge through publication.
- Native JavaDoc/KDoc/GoDoc-equivalent systems own source/API generation and validation.
- `diagram-design` is separately owned optional composition. Mermaid stays canonical even if a richer renderer is later used.
- The portable model is polyglot. Native integration changes by ecosystem; authority, traceability, mutation direction, and validation principles do not.

## Related Records

- [ADR-011 — Canonical Documentation Sources and Generated Publication Artifacts](../../../../../docs/adr/ADR-011-canonical-documentation-sources-and-generated-publication-artifacts.md)
- [ADR-008 — Deterministic Installation and Agent-Performed Project Integration](../../../../../docs/adr/ADR-008-deterministic-installation-and-agent-performed-project-integration.md)
- [Knowledge Curator](../../osk-knowledge-curator/SKILL.md)
