# Verification Engineering

## Mission

Transform product requirements and use cases into reproducible verification evidence. Verification is complete only when expected behavior, executable test, observed result, and supporting evidence remain reproducible without the original agent.

## Scope

### Covers

- Reading use cases, rules, and acceptance criteria; deriving traceable test cases; selecting verification level; creating/executing reproducible automation; preserving evidence; and reporting honest result states.
- Black-box verification, controlled white-box inspection, test-environment prerequisite discovery, and pragmatic timebox use.

### Does not cover

- Performance/load testing, profiling, security assessment, threat modeling, accessibility audits, SEO, architecture review, general-purpose infrastructure creation, production-data inspection, or organization-wide test-strategy governance.

## Responsibilities

- Treat use cases/business rules, acceptance criteria, and documented test cases as the source of truth; automation implements rather than invents expected behavior.
- Design tool-independent test cases and map each automated test to its test-case identifier.
- Choose the least expensive verification level that can produce sufficient evidence.
- Execute reproducibly, preserve proportional evidence, pin mutable evidence when consistent review requires it, classify the actual result, and distinguish automation readiness from verified behavior.
- Report missing prerequisites, environment boundaries, defects, partial scope, and non-convergence honestly.

## Boundaries / Constraints

- Do not claim verification without execution, observation, retained evidence, and documented reproduction.
- Do not access production data, modify databases without authority, invent infrastructure/credentials, or silently repair an application defect to make a test pass.
- Prefer externally observable behavior; use controlled white-box inspection only for material business or consistency conditions that public boundaries cannot prove reliably.
- Automation must run in batch without LLM conversation, hidden agent state, IDE interaction, or undocumented manual order.
- Tool choice follows project conventions and verification need; no tool defines this skill.

## Required Inputs

- Relevant use case, business rules, acceptance criteria, known regressions, and requested verification scope.
- Existing repository verification conventions, runnable environment/prerequisites, and allowed infrastructure/data boundaries.
- Active timebox, observability mode when supplied, and evidence/report destination conventions.

## Expected Outputs

- Documented test cases with traceability to requirements and automated test identifiers.
- Reproducible automation with prerequisites, command, expected result, evidence location, and cleanup where applicable.
- A classified verification result: VERIFIED, VERIFIED WITH OBSERVATIONS, PARTIALLY VERIFIED, AUTOMATION READY — NOT EXECUTED, BLOCKED, or FAILED.
- Proportional evidence and verification-specific findings for osk-engineering-reporting to preserve.

## Workflow

1. Read use cases, business rules, acceptance criteria, and known regressions; report conflicting sources rather than silently selecting one.
2. Derive tool-independent test cases, including preconditions, stimulus, expected observable result/side effects, evidence, cleanup, and priority/risk.
3. Inspect existing infrastructure and choose the narrowest sufficient verification level.
4. Decide whether the evidence is stable or may change during multi-pass or parallel verification; pin the relevant mutable state when consistency materially matters.
5. Implement automation that references test-case IDs and is batch-runnable without AI.
6. Execute the smallest meaningful scope, capture proportional evidence, and classify observed results.
7. Preserve reproduction instructions and report verified, partial, blocked, failed, or unexecuted status honestly.
8. Stop under the timebox before non-convergent environment/debugging work consumes evidence and reporting time.

## Questions to Ask

- Which use case, rule, or criterion defines the expected behavior?
- What is the narrowest verification level capable of proving it?
- What does a reproducible run require: setup, command, variables, expected output, evidence path, and cleanup?
- Which side effects require controlled white-box inspection, if any?
- Is the true state test designed, automation created, test executed, evidence collected, or behavior verified?

## Escalation Rules

- Conflicting requirements/acceptance criteria/current behavior → report inconsistency and request a decision.
- Missing startup command, migrations, fixture, credentials, endpoint, browser dependency, cleanup path, or test environment → report BLOCKED; do not improvise infrastructure.
- Data-loss/production-data risk, destructive action, or a requested write outside scope → stop and escalate.
- Defect found during verification → preserve failure evidence and recommend correction; do not repair unless the task explicitly includes remediation.
- Repeated non-convergent verification work or timebox pressure → make it visible through osk-execution-observability and stop under osk-execution-timebox when necessary.

## Quality Checklist

- [ ] Every case maps to a requirement/rule/criterion/regression and every automated test maps to a case ID.
- [ ] Test cases remain understandable without reading automation.
- [ ] The narrowest sufficient verification level was selected.
- [ ] Mutable evidence used by independent reviewers was pinned once when doing so materially prevents moving-target conclusions.
- [ ] Automation is batch-runnable without AI and has meaningful exit status.
- [ ] Evidence proves the reported classification and avoids sensitive production data.
- [ ] Unexecuted automation is not reported as verified; partial/blocked scope is explicit.
- [ ] Infrastructure, data, repair, and timebox boundaries were respected.

## Anti-Patterns

- **Automation as specification** — writing code before documenting the behavior it protects.
- **False verification** — claiming success because automation exists but was not run.
- **Brittle browser test** — fixed sleeps, fragile DOM paths, shared mutable state, or hidden order dependence.
- **Unbounded infrastructure improvisation** — creating environments or credentials outside scope.
- **Silent repair** — changing the system to pass a test without preserving initial failure evidence.
- **Unnecessary white-box coupling** — asserting private implementation details when public behavior proves the requirement.
- **Moving-target review** — independent reviewers reconstruct a changing diff, log, report, or configuration state and reach conclusions about different evidence.
- **Recursive reviewer fan-out** — reviewers delegate the same review again without an explicit workflow need, multiplying inconsistent evidence and work.

## Dependencies

| Skill ID | Relationship | Required before | Rationale |
| --- | --- | --- | --- |
| osk-execution-timebox | requires | Bounded verification work | Defines budget, stopping, and recovery behavior. |
| osk-execution-observability | requires | Live verification execution | Makes convergence, blockers, and timebox pressure visible. |
| osk-engineering-reporting | requires | Final/checkpointed handoff | Preserves verification outcomes, evidence, and unresolved scope. |

## Activation Conditions

Apply for QA, acceptance testing, use-case-derived test cases, E2E/API verification, reproducible regression evidence, test execution, or verification reporting. Do not apply to profiling, load testing, security assessment, accessibility auditing, or SEO review without a verification-engineering objective.

## Verification Model and Traceability

Canonical flow:

    Use case and business rules
            ↓
    Test cases
            ↓
    Reproducible automation
            ↓
    Execution
            ↓
    Evidence
            ↓
    Verification report

Expected-behavior hierarchy:

    Use case and business rules
            ↓
    Acceptance criteria
            ↓
    Documented test cases
            ↓
    Automated tests
            ↓
    Execution results and evidence

When artifacts conflict, report the inconsistency. Do not let existing automation silently become the specification.

Each test case includes, when applicable: ID, title, purpose, source requirement, preconditions, data, steps/stimulus, expected observable result, expected side effects, required evidence, cleanup, and priority/risk. Each automated test references that ID, for example TC-AUTH-003 in its test name. Reports map use case → criterion/rule → test case → automated test → result → evidence.

## Verification Level and Evidence

Choose the least expensive sufficient level: pure function/domain, component, API/handler, integration, end-to-end, or controlled white-box inspection. A calculation may need only a domain test; HTTP status/JSON may use an HTTP test helper; a full user workflow may need browser E2E; transaction rollback may need API behavior plus a read-only database query.

Prefer black-box evidence: visible UI, HTTP response, supported emitted event, downloaded file, user-visible error, or final resource state. Controlled white-box inspection is permitted only in test environments, read-only by default, documented, traceable to a case, isolated from production, and limited to material conditions such as no partial persisted row, exactly one created entity, an outbox record, or rollback state.

Evidence is proportional and diagnostic: runner output, structured results, failure screenshots/traces/videos, requests/responses, correlation-aware logs, approved query results, generated files, timestamps, and environment metadata. It answers what ran, under what conditions, what happened, why the result was classified, and how to reproduce it. Do not capture screenshots indiscriminately.

### Pinned Evidence Snapshots

Evidence can be **stable** (safe to read directly for the relevant verification window) or **mutable** (its underlying source may change while reviewers or verification passes run). Examples of mutable evidence include a working-tree diff, live/replaced test or build output, generated reports, configuration state, and benchmark results.

When multiple reviewers, subagents, or separated verification passes evaluate the same mutable evidence, capture one shared snapshot before that work when doing so materially improves consistency. Do not add snapshot ceremony for a trivial single-pass check or evidence already stable by contract.

A snapshot records only the evidence needed for the claim, with:

- evidence source and scope;
- capture timestamp or execution boundary;
- producing command/process when applicable;
- snapshot location and provenance, including a content digest when practical;
- intended consumers/reviewers; and
- an immutability expectation: do not overwrite or silently replace it during that review.

Use a task/report-approved location that is readable by the intended consumers. A snapshot can be a copied diff, captured command output, generated report, or other bounded artifact; it need not be a full repository or environment dump. Do not capture environment dumps blindly. Redact credentials, tokens, secrets, personal data, and unrelated sensitive content before sharing evidence with reviewers or reports.

Give all reviewers the same snapshot path/identity. They should not independently reconstruct mutable evidence unless their assigned role explicitly requires fresh state; if fresh state is required, label it as a separate observation rather than treating it as the shared review basis. Do not recursively delegate verification/review from a reviewer unless the workflow explicitly requests it.

## Reproducibility and Tooling

Document prerequisites, setup, command, environment variables, expected output, evidence location, and cleanup. Preferred commands are repository-native, such as make test-e2e, a project verification script, go test, or a focused browser-runner command. They return zero for success and non-zero for failure or inability to execute; human-readable and structured results are used where supported.

Browser automation is selected only when needed. Prefer accessible role/label selectors or project-approved stable identifiers, condition-based waits, deterministic isolated data, independent tests, and traces/screenshots on failure. Avoid brittle DOM paths and arbitrary sleep-based synchronization.

## Infrastructure and Repair Boundary

Inspect and use existing startup commands, compose environments, migrations, fixtures, test credentials, variables, endpoints, browser dependencies, and cleanup scripts. When prerequisites are missing, report BLOCKED with missing items, completed design/automation work, and uncompleted execution/evidence/final verification. Create infrastructure only when the user explicitly requests it, scope includes it, and relevant boundaries permit it.

When a defect is found, preserve failing evidence, classify impact, recommend the smallest correction, and continue unaffected cases only as time permits. In an explicitly combined verify-and-repair task, record initial failure, repair, post-repair result, and evidence for both states.

## Result States

| Status | Meaning |
| --- | --- |
| VERIFIED | Cases documented; automation reproducible and executed; result observed; evidence retained; reproduction command documented. |
| VERIFIED WITH OBSERVATIONS | Required behavior passed; non-blocking limitations/observations remain with evidence. |
| PARTIALLY VERIFIED | Only part of requested behavior was proven; proven/unproven scope is explicit. |
| AUTOMATION READY — NOT EXECUTED | Cases and automation exist; execution did not occur; no behavior-success claim. |
| BLOCKED | Required prerequisites are unavailable; blocker and missing artifacts are explicit. |
| FAILED | Verification executed; expected behavior was not observed; evidence demonstrates failure. |

## Cross-Skill Relationships

osk-execution-timebox limits resource consumption and supplies stopping rules, but does not prove progress or permit overstating evidence. Pursue cheapest sufficient evidence first and preserve time for evidence and reporting.

osk-execution-observability shows whether verification is converging. During important work, report case completion, environment startup, first execution, narrowed failure, changed hypothesis, blocker, evidence capture, and result classification; do not narrate each command.

osk-engineering-reporting defines the durable final/checkpointed record. This skill supplies verification-specific cases, evidence, statuses, and findings rather than duplicating full report structure.

When a pinned snapshot is material, preserve its identity, scope, producer, and consumers in the verification evidence handed to osk-engineering-reporting. The report may retain or link the snapshot according to project policy; it must not claim that a later mutable working tree was the evidence actually reviewed.

Verification findings may expose architectural concerns, such as non-isolatable data, unobservable asynchronous effects, nondeterministic startup, or missing boundaries. Report them and recommend osk-architecture-review rather than automatically conducting a full architecture review.

## Examples

### Use Case to Test Case

Use case: a user signs in with valid credentials. Derive cases for successful login, invalid password, unknown account without disclosure, locked account, malformed request, and session-cookie attributes in proportion to risk and scope.

### Lower-Level Verification

Behavior: division by zero returns a business validation error. Use a domain/service test because a browser adds no proof for that rule.

### Browser E2E

Behavior: successful login redirects, preserves session, and loads authenticated content. Use browser E2E with response evidence, secure-session assertion, authenticated state, and trace/screenshot on failure.

### Controlled Database Inspection

Behavior: failed registration does not persist a partial user. Assert the UI failure first; then use a read-only test-environment query for the test identifier to confirm no row.

### Shared Change Review Snapshot

Two reviewers must assess a pending change while implementation work may continue. Capture the scoped diff once at the review boundary, record its source revision/command, timestamp, location, scope, and reviewer identities, then give both reviewers that immutable file. If one reviewer must inspect the later working tree, report that fresh inspection separately; do not merge it into findings claimed about the shared snapshot.

### Test Output Snapshot

Capture the focused test command's output and exit status after the run completes, redact sensitive values, and record the command, execution boundary, output location, and digest when practical. Reviewers use the captured result rather than rerunning a test whose inputs or environment may have changed.

### Blocked Verification

Status: BLOCKED.

Completed: use case reviewed, four cases documented, automation skeleton prepared.

Blocked by: no QA startup command, documented test credentials, or available migrations.

Not claimed: behavior has not been verified.

## Severity Model

| Severity | Meaning |
| --- | --- |
| BLOCKER | False successful-verification claim, destructive production/uncontrolled-data action, corrupting automation, invalidating error handling, unreproducible authoritative result, silent repair that destroys evidence, or critical unverified behavior declared complete. |
| MAJOR | Non-batch automation, absent traceability, brittle timing/order dependence, infrastructure outside scope, omitted material evidence, implementation-detail assertion instead of required behavior, nondeterministic isolation, unjustified white-box coupling, or overstated executed scope. |
| MINOR | Suboptimal selector, unnecessary screenshot, incomplete report metadata, naming inconsistency, missing optional structured output, or local setup duplication. |
| NOTE | Optional coverage, lower-cost level, future-tooling, or educational recommendation. |

## Evidence and Validation

Use reproducible execution output, observed results, and traceability to requirements as verification evidence. Validate that the reported result state matches what was actually executed and that unexecuted automation is not represented as verified behavior.

## Supporting Resources

- [references/deterministic-evidence-package-template.md](references/deterministic-evidence-package-template.md) — use when a task needs a durable, task-addressable record of deterministic-check execution. It preserves scope, provenance, individual results, not-executed checks, bounded raw evidence, and limits without substituting for QA or review judgment.
