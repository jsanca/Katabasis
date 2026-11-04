# Adversarial Analysis

## Mission

Falsify engineering assumptions through scoped, evidence-backed counterexamples, failure-mode analysis, invariant challenges, and concrete hardening recommendations.

Core principle: do not criticize the design; try to falsify its assumptions.

## Scope

### Covers

- Assumption discovery and explicit statement of the claim being challenged.
- Happy-path challenge, counterexample design, failure-mode analysis, and invariant inspection.
- Boundary-condition exploration, including assumptions about concurrency, state, ordering, resources, retries, and recovery.
- Discovery of validation gaps and recommendations for hardening or adversarial tests.
- Clear classification of findings as verified, plausible but unverified, not reproducible, or out of scope.

### Does not cover

- Stylistic criticism, preference-based rewrites, or unsupported claims of failure.
- Broad structural assessment owned by `osk-architecture-review`, or ordinary contract and placement review owned by `osk-boundary-review`.
- Replacing `osk-verification-engineering` or claiming verification without executed and cited evidence.
- Destructive security testing, live penetration testing, social engineering, production probing, unsafe payloads, or application-specific attack execution.

## Responsibilities

- Define the reviewed claim, target, scope, and authoritative evidence.
- Extract explicit and implicit assumptions that the claim depends on.
- Construct specific counterexamples and explain the plausible mechanism by which each could invalidate an assumption.
- Classify failure modes and inspect relevant invariants, boundary conditions, and current validation.
- Separate verified issues, plausible risks, speculation, deferred concerns, and out-of-scope work.
- Recommend the smallest concrete hardening action, test, documentation update, policy, or escalation.
- Preserve a durable adversarial analysis record through `osk-engineering-reporting`.

## Boundaries / Constraints

- Challenge claims with evidence and mechanisms, not taste, tone, or generalized distrust.
- Do not report absence of tests as proof of a defect; it is a validation gap unless a failure mechanism is established.
- Do not modify the reviewed system, execute destructive inputs, access production resources, or run unsafe security actions unless separately authorized by explicit policy and task scope.
- Treat security-sensitive hypotheses requiring dynamic or live probing as deferred and escalate them rather than attempting them.
- Keep the review bounded to the stated target; refer general architecture or boundary findings to their owning Skills.
- State limitations whenever source, tests, environment, authoritative documents, or allowed commands are unavailable.

## Required Inputs

- The task objective, design claim, implementation claim, or workflow claim being challenged.
- Relevant source tree, diff, configuration, or other reviewed artifact.
- Architecture documents, ADRs, design notes, and known invariants or expected behavior.
- Current tests, static checks, validation results, and existing reports or reviews.
- Constraints including timebox, environment, production/data boundaries, and allowed commands.

When inputs are missing, proceed only with explicit limitations and label conclusions as appropriate to the available evidence.

## Expected Outputs

- A durable adversarial analysis report with a clear target, evidence, limitations, and related records.
- Specific findings that follow: assumptions → counterexamples → failure modes → validation gaps → hardening/tests.
- Actionable recommendations or explicit escalation to the appropriate Skill or human decision.

Recommended report structure:

```markdown
# <TASK-ID> — Adversarial Analysis

## Status
## Reviewed Claim / Target
## Assumptions Identified
| Assumption | Evidence | Why It Matters | Confidence |
| --- | --- | --- | --- |
## Counterexamples
| Counterexample | Target Assumption | Expected Failure | Verification Status |
| --- | --- | --- | --- |
## Failure Modes
| Failure Mode | Trigger | Impact | Existing Coverage | Gap |
| --- | --- | --- | --- | --- |
## Boundary Conditions
## Invariants Challenged
## Validation Gaps
## Recommended Hardening
## Recommended Tests
## Limitations
## Related Records
```

## Workflow

1. **Identify the target claim.** State precisely what is challenged, such as completeness, contract stability, safety, repository sufficiency, workflow determinism, or API-boundary cleanliness.
2. **Extract assumptions.** Record explicit and implicit assumptions about input shape; null, empty, or missing values; ordering; concurrency; retries; timeouts; state transitions; external services; permissions; environment; filesystem; clock/time; locale/encoding; data volume; compatibility; rollback/recovery; and observability.
3. **Challenge with counterexamples.** For each material assumption, ask what input, sequence, state, timing, ordering, dependency behavior, user modification, or prior OSK invariant could make it false.
4. **Classify failure modes.** Describe trigger, mechanism, impact, existing coverage, and gap. Use relevant categories such as correctness, safety, data loss, security, observability, operability, maintainability, compatibility, or reproducibility.
5. **Check existing validation.** Compare each hypothesis with tests, static checks, reports, logs, manual validation, and relevant Skill outputs. Mark it verified, plausible but unverified, not reproducible, or out of scope.
6. **Propose hardening.** Recommend a concrete test, input validation, invariant check, documentation change, error path, fallback, explicit policy, unsupported-scenario rejection, or escalation.
7. **Report honestly.** Distinguish verified issues, plausible risks, speculation, and deferred concerns; preserve evidence, limitations, follow-up ownership, and related records.

## Questions to Ask

- What exact claim is being challenged, and what evidence currently supports it?
- What hidden assumption about input, state, order, time, dependency behavior, user data, or recovery must hold for the claim to remain true?
- What smallest counterexample could falsify that assumption?
- Which invariant is implicit, weak, or untested, and what observable state would show that it was violated?
- Which boundary condition, retry, rollback, compatibility, or observability path did existing validation omit?
- Is this a verified issue, a plausible risk, speculation, or a concern that needs another authorized Skill?

## Escalation Rules

- Dynamic/live security probing, destructive inputs or payloads, production impact, or social engineering risk → stop and escalate to the appropriate future policy-gated security process and human authority.
- Missing required evidence or inability to distinguish speculation from evidence → record the limitation and request the missing evidence; do not overclaim.
- Finding contradicts authoritative documentation → preserve the conflict and request an authoritative decision.
- Counterexample validation exceeds the timebox → preserve the hypothesis and recommended next action through `osk-execution-timebox`.
- General structure or boundary judgment is needed → recommend `osk-architecture-review` or `osk-boundary-review`; reproducible empirical testing → recommend `osk-verification-engineering`.

## Quality Checklist

- [ ] The target claim and review scope are explicit.
- [ ] Assumptions are listed separately from conclusions.
- [ ] Counterexamples are specific and include a plausible mechanism.
- [ ] Findings distinguish verified issues from plausible risks, speculation, and deferrals.
- [ ] Existing validation was inspected before declaring a gap.
- [ ] No stylistic criticism is presented as engineering risk.
- [ ] Recommendations are concrete, scoped, and actionable.
- [ ] Unsafe or security-sensitive actions were not executed.
- [ ] The report links relevant tasks, ADRs, reports, tests, and follow-up Skills.

## Anti-Patterns

- **Preference disguised as risk** — “I do not like this design” is not a finding without a counterexample and mechanism.
- **Invented failure** — do not assert a failure that lacks evidence or a plausible path to reproduction.
- **Test absence as proof** — missing tests establish a coverage gap, not a defect by themselves.
- **Unauthorized experimentation** — do not run destructive, unsafe, live, or production-facing tests.
- **Duplicating another review** — do not replace architecture or boundary review without challenging an assumption.
- **False verification** — do not claim validation when no execution or cited evidence supports it.
- **Hidden uncertainty** — record limitations, unverified hypotheses, and deferred concerns explicitly.

## Dependencies

| Skill ID | Relationship | Required before | Rationale |
| --- | --- | --- | --- |
| osk-boundary-review | complements | Boundary-sensitive adversarial analysis | Boundary review identifies contracts and crossing points that adversarial analysis can challenge. |
| osk-verification-engineering | complements | Counterexamples need empirical validation | Verification engineering turns adversarial hypotheses into reproducible tests when feasible. |
| osk-engineering-reporting | requires | Durable report creation | Adversarial findings need evidence, limitations, and follow-up actions preserved. |
| osk-execution-timebox | requires | Long or interrupted review | Supplies stopping rules and recovery checkpoints for incomplete analysis. |

`osk-architecture-review` remains a related, optional structural review: it owns broad design judgment and is not a hard prerequisite for every adversarial analysis.

## Activation Conditions

Apply when a design, implementation, ADR, or workflow claims to be complete, stable, safe, deterministic, or sufficient; when a human asks for adversarial review; when uncertainty or hidden assumptions are high; or when work touches boundaries, state, concurrency, persistence, retries, install/upgrade, deletion, security, or data migration. Apply after ordinary validation passes when meaningful risk remains unclear.

## Evidence and Validation

Base each finding on a traceable artifact, observed result, or stated plausible mechanism. Cite the assumption, counterexample, current validation, and limitation. A finding is **verified** only when available evidence supports the expected failure; **plausible but unverified** means a mechanism exists but no authorized or feasible validation confirmed it; **not reproducible** means attempted validation did not establish it; and **out of scope** means the required action is not authorized or belongs to another process.

Validate the report itself: every conclusion must identify its target assumption, evidence status, impact, and recommended next step. Do not report a risk as a proven failure merely because it is possible.

## Supporting Resources

- [ADR-006 — Future Direction for OSK Roles, Skills, Capabilities, and Policies](../../../../docs/adr/ADR-006-roles-skills-capabilities-policy-future-direction.md) — records the Phase 1 direction and the policy boundary that defers dynamic security testing.
- `OSK-RESEARCH-004` — task-scoped research identifying this falsification/counterexample gap; the referenced repository-local source records are unavailable in this checkout.
