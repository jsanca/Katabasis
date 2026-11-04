# Project Knowledge

## Purpose

Store durable, current understanding of what the project is, how its domain works, and the concepts needed to change it safely. This is an entry point, not an exhaustive index: navigate from this root to a relevant area, then to the concept and its authority or evidence.

## Navigation and ownership

- Start with this file, then read the relevant area README when one exists.
- A non-trivial area should provide a short `README.md` stating its scope and linking its important children. A generated file list may help discovery, but does not replace maintained navigation or canonical ownership.
- Keep one canonical current explanation for a concept. Link to that explanation from related concepts instead of copying rules or definitions.
- Link a durable claim to its authority: a source location, external contract, ADR, or engineering evidence. Engineering records preserve how the claim was discovered or verified; this directory preserves the current conclusion.

## What belongs here

Domain definitions, actors, entities, workflows, business rules, terminology, conceptual architecture, external-system relationships, and other enduring project concepts.

**Example:** Put “an Order has three terminal states” in `domain/order-lifecycle.md`, even when that fact was discovered during an implementation task.

## Optional vocabulary

These are navigation aids, not a required taxonomy. Create an area only when it has enough durable material to make discovery easier.

| Area | Use it for | Do not create it merely for |
| --- | --- | --- |
| `architecture/` | Current system structure, boundaries, and runtime relationships | A single implementation note |
| `domain/` | Business concepts, rules, and lifecycles | Generic project overview |
| `api/` | Stable interface contracts and integration behavior | Endpoint dumps generated from code |
| `entity-relation/` | Relationships, data ownership, and models | A diagram with no maintained explanation |
| `dsl/` | Project-specific languages, schemas, or syntax | Ordinary configuration snippets |
| `actors/` | Users, services, and external participants | Repeated descriptions owned by a domain page |
| `entities/` | Important durable nouns and their definitions | Every database table by default |
| `flows/` | Cross-cutting sequences and state transitions | A one-off task procedure |
| `glossary/` | Shared terms, acronyms, and disambiguation | Terms already clear on their canonical page |

Project-specific areas such as `rendering/`, `compiler/`, or `operations/` are equally valid when they better match the project.

## Updating knowledge

When a change reveals a reusable current fact, add or update the canonical concept page and link the relevant engineering report, review, source, or ADR. When it only records what happened during work, keep it in `../engineering/`. Put an unresolved hypothesis in an engineering investigation or roadmap/future context until it is verified or adopted.

Prefer updating an existing canonical page over adding a parallel page. If a concept gains several children, add an area README before the directory becomes hard to navigate.

## Lifecycle and integrity

Current knowledge may supersede an older explanation; update the current page and retain historical rationale in an ADR or engineering record. Revisit pages when their authority changes, their links break, or their claims conflict. The workspace does not require frontmatter or automated freshness checks; use explicit links and periodic curation to make provenance and stale content visible.

## What does not belong here

Task reports, implementation logs, review results, temporary investigation notes, Architecture Decision Records, future plans, or unverified conclusions. Keep execution history in `../engineering/`.
