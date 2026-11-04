# Engineering Log

Navigable index of durable engineering work.

This log is a materialized project index, not the authoritative source for task or implementation details. Linked tasks, reports, reviews, checkpoints, and knowledge artifacts remain authoritative.

Only durable artifacts are indexed. Interactions that left no durable artifact are not reconstructed or inferred.

`—` means no artifact exists, no artifact was found, or no relationship is intentionally inferred.

| Task | Description | Status | Depends On | Task File | Report | Review | Fix / Checkpoint | Knowledge |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| KATABASIS-ROADMAP-001 | Consolidación y planificación multiprotocolo | DONE | — | [task](agents/tasks/KATABASIS-ROADMAP-001.md) | [report](agents/reports/KATABASIS-ROADMAP-001-roadmap-planning.md) | — | — | [architecture](../knowledge/architecture/katabasis-core.md) |
| K-0.1-T01 | Fix pause/resume event storm in segmented downloads | DONE | KATABASIS-ROADMAP-001 | [task](agents/tasks/K-0.1-T01.md) | [report](agents/reports/K-0.1-T01-pause-resume-fix.md) | — | — | — |

## Maintenance

- Keep one row per logical engineering task and preserve completed, superseded, and cancelled history.
- Use only recorded stable task IDs in **Depends On**; do not infer dependency from chronology.
- Link authoritative artifacts instead of reproducing their content.
- Add multiple links only when they belong to the same logical task.
- Put durable current project knowledge—not ordinary implementation history—in **Knowledge**.
- Update an existing row when a newly created durable artifact establishes a recorded relationship.
- Preserve existing valid historical rows and use `—` for fields that cannot be established without inference.
