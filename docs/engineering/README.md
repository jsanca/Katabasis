# Engineering History

## Purpose

Store the evidence and execution record of how the project changed.

## What belongs here

`ENGINEERING_LOG.md` is the compact, materialized index of material work. `agents/tasks/` preserves task specifications when useful; `agents/reports/` holds durable completion reports; `agents/reviews/` holds independent review records; and `agents/checkpoints/` holds recovery/continuation state. Investigations, validation records, task outcomes, and execution discoveries also belong here.

Use relationships instead of duplicate narratives: a task may point to its report; a report may point to review, fixes, checkpoints, immutable evidence snapshots, and current knowledge. The log links those durable records with the same relationship columns used by the Engineering Reporting Skill. Not every task needs every record.

Typical flow: intent → execution → report → review when warranted → fix, follow-up, or checkpoint. Research follows the same pattern: preserve its method and evidence here, then update `../knowledge/` when it establishes a durable current conclusion. Do not maintain a second directory-local log; keep `ENGINEERING_LOG.md` as the stable navigation surface.

**Example:** Put an implementation report that records how a payment-state discovery was verified here; put the durable payment-state rule in `../knowledge/`.

## What does not belong here

The durable project/domain explanation itself. Put current reusable understanding in `../knowledge/` and decision rationale in `../adr/`. Do not put a full report body in `ENGINEERING_LOG.md`; link its durable record instead.
