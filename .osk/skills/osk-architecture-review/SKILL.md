# Architecture Review

## Mission

Validate a software system's structure, boundaries, and contracts against its intended design so architectural drift is detected before it becomes technical debt.

## Scope

### Covers

- Dependency direction, layering, bounded contexts, module surfaces, and adapter isolation.
- HTTP/domain/error contracts, statelessness, determinism, testability, security implications, and extensibility.
- Findings classified as BLOCKER, MAJOR, MINOR, or OBSERVATION.
- A bounded review verdict of APPROVED or CHANGES REQUIRED.

### Does not cover

- Formatting, naming preferences, linting, minor refactors, coverage percentage, micro-optimizations, library preference, UI aesthetics, or product prioritization.
- Implementing fixes, modifying production code, tests, or configuration.
- General code review or functional testing.

## Responsibilities

- Understand the reviewed slice, its explicit exclusions, prior reports, and intended design.
- Trace the execution path from entry point through application/domain ports and infrastructure adapters.
- Evaluate dependency direction, boundaries, contracts, statelessness, determinism, adapter isolation, error propagation, testability, security, and extensibility.
- Record every finding with an AAR-XXX ID, severity, evidence, impact, recommendation, and disposition.
- Record positive and deferred findings and issue the applicable approval verdict.

## Boundaries / Constraints

- Evaluate architecture rather than coding style, personal taste, or implementation minutiae.
- Do not modify the reviewed codebase, tests, or configuration.
- Use the defined finding taxonomy; observations alone never block approval.
- APPROVED requires zero BLOCKER and zero MAJOR findings, documented follow-up for every MINOR, and alignment with intended design.
- CHANGES REQUIRED applies to any BLOCKER, a MAJOR without a resolution plan, contradictory design, or a violated core property.

## Required Inputs

- The task/slice definition and stated scope.
- Relevant use cases, design documents, source code, tests, and prior reports available for the review.
- The intended architecture or enough repository evidence to identify it.

## Expected Outputs

- A review report containing scope, verdict, architecture assessment, findings, positive/deferred findings, risk assessment, recommendation, and references.
- A clear APPROVED or CHANGES REQUIRED verdict.
- A checkpoint/report record following osk-engineering-reporting; an interruption checkpoint when required by osk-execution-timebox.

## Workflow

1. Read the task, related reports, and intended slice boundary.
2. Map the execution path: entry point → adapter → application service → domain ports → adapter → infrastructure.
3. Evaluate component layering and public surfaces.
4. Validate API, domain, and error contracts; check that secrets and internal state are not exposed.
5. Verify statelessness, deterministic selection/ordering, and adapter boundaries.
6. Inspect whether test design exercises the architectural seams and error paths.
7. Assess each risk's consequence and cost of later correction.
8. Classify findings using the taxonomy and include required evidence and disposition.
9. Record positive/deferred findings and apply the approval rules.
10. Produce the report and required operational record.

## Questions to Ask

- What was this slice intended to add, and what was explicitly excluded?
- Which design, use case, or contract is authoritative for the reviewed boundary?
- What existing architecture does this extend?
- Is a finding required in this slice, a follow-up, or a documented deferral?

## Escalation Rules

- Missing or conflicting intended architecture → escalate to the responsible architecture/design authority; do not invent a rule.
- A BLOCKER, unresolved MAJOR, or core-property violation → issue CHANGES REQUIRED.
- Timebox expiration, unclear objective, or repeated non-progress → stop and create the recovery checkpoint required by osk-execution-timebox.

## Quality Checklist

- [ ] Scope and authoritative references are identified.
- [ ] All relevant architectural properties were assessed or explicitly marked unavailable.
- [ ] Every finding has ID, severity, evidence, impact, recommendation, and disposition.
- [ ] Verdict follows the approval rules.
- [ ] Report distinguishes findings, observations, positives, and deferrals.
- [ ] No reviewed implementation artifact was modified.

## Examples

### Correct dependency direction

An application service imports domain ports such as a repository and session issuer, while HTTP and database concerns stay in adapters. This is a positive finding, not a reason to require changes.

## Anti-Patterns

- **Turning the review into style review** — record only structural concerns unless style masks an architectural problem.
- **Approving with unresolved major findings** — issue CHANGES REQUIRED unless a resolution plan satisfies the approval rule.
- **Treating an observation as a blocker** — observations inform future work but never block approval by themselves.
- **Fixing reviewed code** — the skill evaluates; it does not implement.

## Dependencies

| Skill ID | Relationship | Required before | Rationale |
| --- | --- | --- | --- |
| osk-engineering-reporting | requires | Recording the review | Supplies evidence and checkpoint/report conventions. |
| osk-execution-timebox | requires | Starting the bounded review | Supplies target, hard-stop, and recovery-checkpoint behavior. |

## Activation Conditions

Apply for an architecture review, design-boundary assessment, architectural approval, or a completed slice with structural impact. Do not apply for ordinary style review, lint cleanup, or implementation work without an architecture-evaluation objective.

## Evidence and Validation

Base findings and the verdict on reviewed artifacts, authoritative design evidence, and traceable references. Validate that the report contains required finding evidence and that the approval rules were applied.

## Supporting Resources

None.
