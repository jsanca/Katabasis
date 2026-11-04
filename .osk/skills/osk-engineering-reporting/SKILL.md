# Engineering Reporting

## Mission

Create, review, reconcile, and recover non-trivial engineering work with durable implementation, review, fix, checkpoint, and documentation-audit records grounded in actual repository evidence.

## Scope

### Covers

- Completion reporting for non-trivial implementation, review, recovery, architecture, security, and documentation tasks.
- Implementation, review, fix, recovery checkpoint, and documentation-audit report structures.
- Evidence rules, validation reporting, limitations, unresolved issues, and preservation of historical records.

### Does not cover

- Inventing evidence, completion, ownership, behavior, or decisions.
- Treating a checkpoint as an implementation report or silently rewriting historical delivery evidence.
- Defining the specialized methodology of architecture, security, or other review disciplines.

## Responsibilities

- Locate the task and read any open checkpoint before reporting completion.
- Select the matching record type and write it to the canonical engineering location.
- Record only commands and validation results actually observed.
- Create or update `docs/engineering/ENGINEERING_LOG.md` as a human-navigable materialized index of durable engineering work; link task, report, review, remediation/checkpoint, and knowledge artifacts without recreating their content.
- Link related durable records and update canonical documentation only when repository evidence supports current-state claims.
- State skipped/unavailable validation, unresolved issues, and future behavior explicitly.

## Boundaries / Constraints

- Do not report DONE while an OPEN checkpoint exists; resolve or supersede it first.
- Never claim tests pass, files exist, or work is complete without evidence.
- Keep active canonical documentation about present behavior; put future behavior in planned work or open questions.
- Preserve historical reports through annotation or supersession rather than silent rewriting.
- A recovery checkpoint is temporary operational memory, not an implementation report.

## Required Inputs

- Active task identifier/objective and the work or artifact being reported.
- Actual changed files, validation commands/results, evidence, limitations, and unresolved issues.
- Existing relevant reports and any open recovery checkpoint.

## Expected Outputs

- A correctly typed, structured engineering record with evidence, validation, limitations, follow-ups, and references.
- A concise linked entry in `docs/engineering/ENGINEERING_LOG.md` using the canonical durable-artifact table.
- Explicit checkpoint status when recovery state exists.

## Workflow

1. Locate the active task and inspect the relevant prior reports and OPEN checkpoint.
2. Select the record type and location: task spec → `docs/engineering/agents/tasks/`; completion report → `docs/engineering/agents/reports/`; review → `docs/engineering/agents/reviews/`; recovery checkpoint → `docs/engineering/agents/checkpoints/`.
3. Collect repository evidence, files changed, commands run, validation outcomes, and limitations.
4. Name new records `<task-id>-<lowercase-slug>.md` when a task ID exists; preserve historical names and link supersessions rather than renaming prior records.
5. Populate the required report sections for that type; use [references/report-template.md](references/report-template.md) for a completion report.
6. Create or update the engineering log with the canonical table in [references/engineering-log-template.md](references/engineering-log-template.md). Preserve existing valid rows and historical records; do not reconstruct undocumented work.
7. Link related durable records and update current-state documentation only when the evidence warrants it.
8. Verify that the record and log do not overclaim success, validation, or scope, and that no OPEN checkpoint is contradicted.

## Engineering Log Contract

`ENGINEERING_LOG.md` is a human-navigable materialized index of durable engineering work. It is not the authority for task intent, implementation detail, review findings, project knowledge, conversations, or work that left no durable artifact. Linked task files, reports, reviews, checkpoints, fixes, and knowledge records remain authoritative.

Use the canonical header and table in [references/engineering-log-template.md](references/engineering-log-template.md):

| Task | Description | Status | Depends On | Task File | Report | Review | Fix / Checkpoint | Knowledge |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |

`—` means no artifact exists, no artifact was found, or a relationship is intentionally not inferred.

### Column semantics

- **Task:** stable published engineering task ID; do not rename it after publication.
- **Description:** short navigation title, not a replacement task specification.
- **Status:** one of `PLANNED`, `ACTIVE`, `BLOCKED`, `REVIEW`, `DONE`, `SUPERSEDED`, or `CANCELLED`.
  - `PLANNED`: durable task exists but work has not materially begun.
  - `ACTIVE`: work is in progress and no blocking condition is recorded.
  - `BLOCKED`: work cannot proceed until an identified prerequisite, authority, or external condition changes.
  - `REVIEW`: primary work is complete enough for an expected independent review, which is pending or active.
  - `DONE`: the task's recorded completion criteria were met; a review is optional unless project process required it.
  - `SUPERSEDED`: a later durable task/decision replaces this task's intended direction; preserve the row and link the replacing record where known.
  - `CANCELLED`: intentionally stopped without completion; preserve the reason in an authoritative linked artifact when one exists.
- **Depends On:** recorded stable task IDs only; list multiple IDs when necessary. Do not infer dependency from chronology.
- **Task File:** authoritative task specification, if one exists.
- **Report:** primary execution/implementation report; link multiple reports only when they belong to one logical task.
- **Review:** independent review evidence when present; do not fabricate a review for a `DONE` task.
- **Fix / Checkpoint:** durable remediation or recovery artifacts associated with the task.
- **Knowledge:** durable current project knowledge materially produced or changed by the task, such as an ADR, architecture/domain page, glossary, protocol, or roadmap decision. Do not put ordinary implementation history here.

One row represents one logical engineering task. Preserve completed, superseded, and cancelled rows. When the log exists, update a recorded task/report relationship with the durable artifact rather than silently leaving the index stale. Do not turn the log into a general project-management system.

### Artifact relationships and authority

The log records, but does not own, these conceptual relationships:

```text
TASK -> depends_on -> TASK
TASK -> specified_by -> TASK FILE
TASK -> produces -> REPORT
TASK -> reviewed_by -> REVIEW
TASK -> remediated_by -> FIX
TASK -> checkpointed_by -> CHECKPOINT
TASK -> contributes_to -> KNOWLEDGE
```

Authority flows from individual durable artifacts to the log and then to any future generated projection. A task file owns task intent; a report owns execution findings; a review owns review findings; a knowledge artifact owns current project knowledge; the log is the navigable index. A future generator may project this recorded structure into Markdown, Mermaid, Graphviz, or JSON, but this Skill does not create a graph, database, scheduler, or orchestration engine.

### Compatibility and future metadata

Adopt the table gradually. Preserve existing valid rows and historical narrative entries rather than rewriting history. Use `—` for unavailable historical fields. Keep the active log at `ENGINEERING_LOG.md`; future rolled files may retain the same schema and be linked from the active index. This Skill sets no rolling threshold.

Minimal frontmatter such as `id`, `type`, `status`, and `depends_on` could later make validation/projection more reliable, but it is not mandatory now. Do not migrate artifacts, add a schema, or infer metadata merely for a hypothetical graph.

## Questions to Ask

- What task and report type is being recorded?
- What commands, tests, or inspections actually ran, and what were their results?
- Is there an OPEN checkpoint that prevents completion reporting?
- Which claims are current-state facts, historical evidence, future plans, or unresolved questions?

## Escalation Rules

- Missing task identity, unavailable evidence, or an unresolved OPEN checkpoint → do not claim completion; request the missing record or resolve the checkpoint.
- Conflicting current-state evidence → report the conflict and escalate to the authority; do not select a winner.
- Hard/early stop before completion → create the recovery checkpoint under osk-execution-timebox.

## Quality Checklist

- [ ] The report type matches the work performed.
- [ ] All mandatory sections for that report type are present.
- [ ] Every validation claim is supported by an actual command or inspection result.
- [ ] Limitations, skipped validation, and unresolved issues are explicit.
- [ ] Historical records are preserved and current-state claims are evidence-backed.
- [ ] The engineering-log row uses a stable task ID, short description, canonical status, and only recorded artifact/dependency relationships.
- [ ] No OPEN checkpoint is contradicted by a completion claim.

## Anti-Patterns

- **Claiming tests pass without running them** — record only actual results or state validation was skipped.
- **Using an implementation report as a checkpoint** — use the recovery checkpoint structure and status.
- **Marking work done with an OPEN checkpoint** — resolve or supersede it first.
- **Writing planned behavior as present fact** — place it in follow-ups or open questions.
- **Reconstructing undocumented history** — do not infer task dependencies, reviews, reports, or knowledge relationships from chronology or conversation.
- **Using the log as the authority** — linked durable artifacts retain authority for their own details and findings.

## Dependencies

| Skill ID | Relationship | Required before | Rationale |
| --- | --- | --- | --- |
| osk-execution-timebox | requires | Reporting interrupted or bounded execution | Defines recovery checkpoint and hard-stop behavior. |

## Activation Conditions

Apply when non-trivial engineering work needs an implementation, review, fix, checkpoint, or documentation-audit record. Do not use a progress update as a substitute for this durable report, and do not apply to a trivial status message with no engineering record to preserve.

## Evidence and Validation

Use observable artifacts, command results, references, and honest limitations as evidence. Validate that each report type satisfies its required sections and that no completion claim exceeds the recorded evidence.

## Supporting Resources

- [references/report-template.md](references/report-template.md) — use for non-trivial completion reports under `docs/engineering/agents/reports/`.
- [references/engineering-log-template.md](references/engineering-log-template.md) — use to create or maintain the durable engineering index.
