# OSK Workspace Guide

## Start Here

Read [PROJECT.md](PROJECT.md) for concise project context. Use this guide to decide where new information belongs, then read the relevant folder README or area index for local guidance. Keep facts traceable to their evidence and do not treat tool-specific instruction files as canonical knowledge.

## Classification Rule

**Classify documentation by its purpose, not by the task that produced it.** A single task may produce engineering evidence, durable knowledge, a decision, and future intent; place each artifact where it remains useful.

## Information Model

| Concern | Meaning | Canonical location |
| --- | --- | --- |
| Engineering | What happened | `docs/engineering/` |
| Knowledge | What is true | `docs/knowledge/` |
| Architecture decisions | Why we chose it | `docs/adr/` |
| Roadmap | Where we intend to go | `docs/roadmap/ROADMAP.md` |
| Future | Where we might go | `docs/roadmap/future/` |

## Engineering

Record task evidence, reports, reviews, checkpoints, and reproducible validation under `docs/engineering/`. Keep [ENGINEERING_LOG.md](engineering/ENGINEERING_LOG.md) as the stable compact index: detailed task specifications, reports, reviews, and checkpoints belong under `engineering/agents/`. A typical lifecycle is intent → execution → report → independent review when needed → fix, follow-up, or checkpoint. Future rotation may archive completed entries, but must retain this active path. Do not claim results that evidence does not support.

Research belongs here while it is investigation and evidence. If it establishes a reusable current fact, update the canonical knowledge page and link it from the report. Preserve immutable evidence snapshots when exact inputs, tool output, or source state are material to a conclusion; a report should identify the snapshot rather than silently restating it.

## Knowledge

Place durable current understanding under `docs/knowledge/`: domain concepts, actors, entities, flows, terminology, and current architecture. Put the conclusion here and retain the discovery or validation history in engineering records. Navigate progressively: knowledge root → relevant area → concept → authority or evidence. Create a subdirectory only when it aids navigation; a non-trivial area needs a short README linking its important children.

Give each concept one canonical current explanation. Related pages should link to it rather than duplicate its rules. Link important claims to the source, external contract, ADR, or engineering evidence that supports them. Update the canonical page when current truth changes; retain replaced rationale and work history in ADRs or engineering records.

## Decisions

Place significant Architecture Decision Records in `docs/adr/`. An ADR explains context, decision, and consequences, and remains historical evidence. Describe the system as it works now in `docs/knowledge/`, not by rewriting historical decisions.

## Roadmap and Future

Use [ROADMAP.md](roadmap/ROADMAP.md) for intended, committed direction. Preserve non-committed ideas in `docs/roadmap/future/`; they do not become roadmap work unless explicitly adopted.

## Placement Examples

- **Domain discovery:** “Payment can be RETRYING, SETTLED, or FAILED” is durable project knowledge in `docs/knowledge/`. The task report or validation evidence may remain in `docs/engineering/`.
- **Architectural choice:** “The system uses Kafka” is current knowledge in `docs/knowledge/`. Why Kafka was chosen instead of synchronous HTTP belongs in `docs/adr/`.
- **Future capability:** “Add event replay next quarter” belongs in `docs/roadmap/`. After delivery, how replay works belongs in `docs/knowledge/`, with implementation history in `docs/engineering/`.

## OSK-Managed Artifacts

`.osk/` is reserved for OSK-managed state. Installed canonical Skills belong under `.osk/skills/`; do not duplicate their package contracts in project `docs/`.

Agent integrations should reference this canonical Skill source through small adapters. If a tool requires a generated copy, treat it as derived and disposable, with `.osk/skills/` remaining authoritative. Do not use symlinks as the canonical integration mechanism.

## Contribution Checks

- [ ] Put the artifact in the location matching its information type.
- [ ] Link durable claims to evidence or an authority.
- [ ] Keep current knowledge separate from historical decisions and engineering records.
- [ ] Keep future ideas separate from committed roadmap work.
- [ ] Classify documentation by purpose rather than by the task that produced it.
- [ ] Update `PROJECT.md` when project context materially changes.
- [ ] Update a canonical knowledge page when work establishes or changes a reusable current fact.
- [ ] Add maintained local navigation before a knowledge area becomes difficult to scan.
