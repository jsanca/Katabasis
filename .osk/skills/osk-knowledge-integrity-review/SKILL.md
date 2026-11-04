# Knowledge Integrity Review

## Purpose

Inspect whether durable project knowledge remains coherent, current, evidence-supported, discoverable, correctly classified, and appropriately authoritative. Produce evidence-backed findings and a reconciliation plan; apply only explicitly authorized, narrow reconciliation while preserving history and decision boundaries.

## Use when

Use for periodic or post-change integrity review, detected documentation drift/conflict, significant architecture/domain/provider change, migration, major release preparation, or accumulated engineering work whose durable conclusions may not be reflected in current knowledge.

Do not use for ordinary prose cleanup, architecture/code review, documentation publication, automatic migration, or inventing project truth. Use `osk-knowledge-curator` for broader knowledge maintenance or reconciliation not bounded by this review; use `osk-verification-engineering` when a material claim needs reproducible proof.

## Modes and operating basis

**Audit / Plan** is the default and read-only: inspect a bounded evidence basis, classify findings, identify authority/evidence, and produce a reconciliation plan. Invocation or inspection authority does not authorize mutation.

**Authorized Reconciliation** begins with the same review and may apply only a previously identified, evidence-backed change when the executor knows:

- the authority owner or decision source;
- the authorized scope and operations;
- what remains outside that scope; and
- durable authorization evidence when the decision must survive the executor/session.

Pin the smallest basis capable of supporting the review: relevant current knowledge, task/specification, accepted ADRs, engineering records, source/configuration, runtime observations, dependency metadata, authoritative external sources, or an earlier integrity report. Record material omissions and do not treat loaded context, summaries, or recency as authority.

## Findings taxonomy

Use one primary classification per material finding; record secondary concerns in its explanation.

| Classification | Meaning | Normal response |
| --- | --- | --- |
| `STALE` | A current claim appears no longer current under relevant evidence. | Plan current-knowledge update; preserve historical evidence. |
| `CONTRADICTORY` | Sources make incompatible claims in overlapping scope. | Identify claim/authority; escalate rather than choose by recency. |
| `DUPLICATED` | Independently maintained knowledge risks divergence. | Identify canonical explanation and link/consolidate if authorized. |
| `ORPHANED` | Knowledge lacks meaningful ownership, navigation, or connection to current project context. | Restore reference/ownership or classify as intentionally historical. |
| `MISCLASSIFIED` | Information is stored as the wrong semantic purpose. | Plan link/move/reclassification without erasing history. |
| `UNVERIFIABLE` | A material claim lacks sufficient evidence or identifiable authority. | Preserve the uncertainty; request/locate authority. |
| `SUPERSEDED` | A source is replaced in authority/scope but is still presented as current. | Mark/link supersession; retain historical record. |
| `AUTHORITY-AMBIGUOUS` | Multiple plausible owners exist and ownership is unclear. | Escalate for explicit decision or record a plan-only proposal. |

Intentional summaries, indexes, references, and time-scoped records are not duplicates or contradictions merely because they repeat a claim.

## Procedure

1. **Set mode and scope.** State Audit / Plan or Authorized Reconciliation; identify the knowledge area, trigger, boundaries, and evidence basis.
2. **Map claims and ownership.** For each material claim, distinguish current project knowledge, history/evidence, ADR rationale, roadmap commitment, and future possibility. Ask what source owns this type of claim.
3. **Inspect proportionately.** Compare the smallest relevant knowledge surface with the identified authority/evidence. Expand only when a finding requires it.
4. **Classify findings.** Record ID, classification, affected claim/location/scope, evidence and authority basis, impact, uncertainty, and recommended action. Use [the review template](references/review-template.md) when a durable review record is warranted.
5. **Plan before mutation.** Separate safe evidence-backed updates from matters requiring an architectural/product/authority decision. State no-change findings and limitations where material.
6. **Apply only when authorized.** In Authorized Reconciliation mode, make only approved, scope-bounded changes to current knowledge. For broad maintenance, unresolved conflict, or cross-cutting reconciliation, hand off to `osk-knowledge-curator`.
7. **Verify and preserve continuity.** Re-check changed claims and links proportionately; record mutation evidence, unresolved findings, authorization basis when material, and durable next steps.

## Boundaries and authorization

- Permission to inspect, a finding, and a proposed reconciliation are not mutation authority.
- Do not rewrite accepted ADR rationale, delete engineering evidence, convert future work into current fact, or resolve authority ambiguity by convenience.
- Do not expand beyond authorized scope or overwrite host/project-owned changes.
- A portable instruction is policy, not enforcement. Do not claim mutation is impossible without approval unless a known external mechanism actually guarantees it.
- A chat approval or session permission is not automatically durable authorization. For consequential or cross-session reconciliation, retain an appropriate durable task, approved plan, issue, decision, report, or workflow record.
- Code/configuration may show what is present; it does not by itself establish approved intent or decision rationale.

## Stop and escalate

Stop mutation and escalate when authority cannot be determined, evidence materially conflicts, required evidence is unavailable, a change would alter accepted decision rationale, architecture/product judgment is required, authorization/scope is missing, historical evidence would need destructive rewriting, private/security-sensitive material appears, or project ownership is unclear.

Escalate broader knowledge maintenance/reconciliation to `osk-knowledge-curator`; reproducible claim proof to `osk-verification-engineering`; architecture/boundary/adversarial judgments to their respective Skills and responsible authority.

## Output and completion

**Audit / Plan** completes only when the review basis and scope are recorded, material findings have classification/evidence/authority analysis, limitations and conflicts are explicit, and a plan distinguishes proposed reconciliation from unapproved mutation. Report that mutation was not authorized/performed.

**Authorized Reconciliation** additionally requires recorded authorization/scope, applied changes limited to that scope, proportionate re-verification, mutation evidence, and preserved unresolved findings. Use `osk-engineering-reporting` for a durable report, review, checkpoint, or log entry when review significance, mutation, handoff, or project policy warrants it; do not create records merely because a trivial check ran.

## Continuity

Preserve the review basis, findings, plan, authority/authorization information when material, applied mutations, validation, and unresolved questions in the appropriate durable artifact. A session summary is only a secondary aid; it does not replace current knowledge, an ADR, a task/report/review, or the evidence it summarizes.

## Supporting resources

- [references/review-template.md](references/review-template.md) — optional durable review/report starting point; tailor it to scope and do not create empty sections.
