# Execution Observability

## Mission

Keep an agent's execution observable enough for a human supervisor to judge convergence, blockers, scope, risks, and completion without requiring exhaustive narration or disclosure of private reasoning.

Primary principle: execution observability must be proportional to the supervisor's need for attention, intervention, and confidence. Higher observability means more decision-relevant state, not more narration.

## Scope

### Covers

- Live execution progress updates, semantic checkpoints, modes, escalation events, non-convergence, scope visibility, supervisor interruptibility, and timebox awareness.
- Concise operational rationale: current direction, supporting evidence, material decision, next action, and risk when relevant.

### Does not cover

- Final engineering-report structure, software telemetry, product observability, test-evidence standards, architecture-review content, language style, project ceremonies, or exhaustive internal reasoning.

## Responsibilities

- Select or honor an observability mode proportionate to complexity, uncertainty, reversibility, risk, duration, supervisor interest, verification difficulty, loop risk, and scope breadth.
- Communicate meaningful state transitions, evidence, decisions, blockers, scope changes, and timebox pressure.
- Detect and interrupt non-convergent work, starvation, indefinite waiting, and livelock.
- Remain interruptible in ATTENTIVE and SUPERVISED modes.
- Preserve a concise operational rationale without exposing private scratchpad content.

## Boundaries / Constraints

- Activity is not progress. Reading files, running commands, editing, researching, and retrying tests are activity until they produce an artifact, narrowing, evidence, decision, or other meaningful state change.
- Do not narrate every command, repeat the plan without new evidence, or use status noise as a substitute for a checkpoint.
- Do not expose unrestricted chain-of-thought. Reveal decision-relevant execution state, not private scratchpad content.
- An explicitly requested mode may be increased when risk or uncertainty rises; it must not be silently reduced.
- Every mode is overridden by mandatory escalation events.
- Do not invent infrastructure, credentials, or external results to escape a blocked state.

## Required Inputs

- The active task objective, scope, and execution timebox.
- An explicit observability mode when supplied; otherwise enough task context to infer one.
- Current evidence, relevant risks, blockers, and verification state as execution progresses.

## Expected Outputs

- Mode-appropriate live checkpoints containing meaningful progress evidence, decisions, risks, scope changes, or blockers.
- Immediate escalation of material events regardless of mode.
- A final concise outcome with verification and remaining risks, with durable reporting delegated to osk-engineering-reporting.
- A recovery checkpoint when required by osk-execution-timebox.

## Workflow

1. Read the objective, scope, timebox, and any requested observability mode.
2. Select or infer the mode; state an initial plan only when that mode requires it.
3. Execute while emitting semantic progress heartbeats at meaningful state transitions.
4. Escalate mandatory events immediately, including scope change and timebox pressure.
5. Apply the non-convergence, starvation, waiting, and livelock guards before repeated activity consumes the budget.
6. Reserve sufficient time for verification, reporting/handoff, and a timebox checkpoint if completion is at risk.
7. Deliver the final outcome or stop under osk-execution-timebox with the evidence needed to continue safely.

## Questions to Ask

- What observability mode did the supervisor request, if any?
- How costly is a wrong direction or an unreported scope change?
- What would count as progress or convergence for this task?
- What evidence would let the supervisor intervene before the timebox is exhausted?
- Which verification state is actually true: test designed, automation created, test executed, evidence collected, or behavior verified?

## Escalation Rules

Communicate immediately, in every mode, when any of these occurs:

- material blocker, unavailable dependency/tool, missing infrastructure, credentials, or external actor;
- contradictory requirement, inability to reproduce the task, or inability to verify the result;
- data-loss risk, destructive/irreversible action, material security concern, or needed deviation from another OSK rule;
- scope expansion, partially complete result, repeated same-hypothesis failure, or unexpected timebox pressure.

For scope changes, state original scope, discovered issue, impact, and one decision: include because completion requires it; exclude and report; request authorization; or apply the minimal correction and resume.

## Quality Checklist

- [ ] Mode is explicit or reasonably inferred and remains proportional to risk and supervisor interest.
- [ ] Updates communicate evidence-backed state changes rather than activity.
- [ ] Mandatory escalation events are visible even in QUIET mode.
- [ ] Non-convergence, waits, scope drift, and timebox pressure are detected before silent exhaustion.
- [ ] Checkpoints are concise, decision-relevant, and do not disclose private reasoning.
- [ ] Verification language distinguishes design, automation, execution, evidence, and verified behavior.

## Anti-Patterns

- **Command-by-command narration** — activity that does not improve supervisor understanding.
- **Still-working checkpoint** — a message such as “I am continuing” without new evidence or state.
- **Silent scope expansion** — unrelated or expanded work performed without an explicit decision.
- **Same-hypothesis looping** — repeating an ineffective change without reassessment.
- **False verification** — claiming a test passed because automation was written.
- **Private-reasoning disclosure** — exposing unrestricted scratchpad content rather than concise operational rationale.

## Dependencies

| Skill ID | Relationship | Required before | Rationale |
| --- | --- | --- | --- |
| osk-execution-timebox | requires | Bounded execution | Defines budget, hard-stop, and recovery-checkpoint rules that observability makes visible. |

## Activation Conditions

Apply for supervised execution, progress visibility, agent status updates, an observability mode, convergence/loop monitoring, or work needing easy interruption. Do not apply merely because application logging, metrics, tracing, or OpenTelemetry is mentioned.

## Terminology

| Term | Meaning |
| --- | --- |
| Activity | Work performed by the agent. It is not by itself evidence of progress. |
| Progress | Meaningful movement toward completion: an artifact, narrowed failure, validated/rejected hypothesis, reproducible evidence, blocker resolution, deliberate scope reduction, verification milestone, or consequential decision. |
| Checkpoint | A concise update that exposes a meaningful execution state transition. |
| Operational rationale | Brief what, why, evidence, and next-action explanation; not unrestricted internal reasoning. |
| Convergence | Successive actions reduce uncertainty or move the task toward a verifiable result. |
| Non-convergence | Repeated activity that does not materially reduce uncertainty or produce a usable artifact. |

## Observability Modes

### QUIET

Use for trivial, mechanical, routine, low-risk, easy-to-verify work. Execute without routine narration or an initial plan unless complexity emerges. Report only material blockers, risks, scope changes, or final result/verification. QUIET never hides failure, unverified results, scope expansion, or indefinite blocking.

### STANDARD

Default for normal multi-step engineering work with moderate risk when no mode is specified. Provide a brief plan, the first material finding, material direction changes, immediate blockers, and final verification/risks. Typical sequence: plan → meaningful checkpoint → result.

### ATTENTIVE

Use for important implementation, design, verification, or review work the supervisor wants to follow. State interpreted goal/scope; surface decisions, partial evidence, risks, uncertainty, material deviations, next significant action, convergence, and timebox status. The supervisor must be able to redirect before completion.

### SUPERVISED

Use for high uncertainty, difficult debugging, concurrency/lifecycle work, migrations, destructive or security-sensitive work, repeated failures, or explicitly close supervision. Declare the active hypothesis before expensive/repeated experiments; state what each experiment proved/disproved; expose scope expansion before acting; reassess ineffective attempts; preserve reproducible checkpoints; and expose timebox pressure before budget exhaustion. Typical loop: hypothesis → experiment → evidence → decision → next hypothesis or completion.

## Checkpoints and Heartbeats

Publish a semantic progress heartbeat when planning completes, the first significant finding appears, a milestone completes, a test materially changes understanding, the active hypothesis changes, a blocker appears, task state/scope changes, timebox pressure emerges, or execution completes.

A valid checkpoint contains at least one completed artifact, concrete finding, narrowed failure, verified/rejected hypothesis, reproducible evidence, execution-changing decision, blocker, scope decision, risk, or evidence-based next action. “I am continuing,” “still investigating,” and similar activity-only messages are not checkpoints.

Recommended concise pattern:

~~~
Current state:
<what is now true>

Evidence:
<what demonstrates it>

Decision:
<what changes because of that evidence>

Next:
<next meaningful action>

Risk:
<optional blocker, uncertainty, or scope concern>
~~~

Use natural language; do not mechanically repeat unchanged labels. Avoid time-based status noise, but treat prolonged silence during complex work as an observability failure. If no milestone occurs for an extended interval, explain what consumed it, why progress evidence is absent, the smallest next experiment, and whether it fits the remaining timebox.

## Non-Convergence and Liveness Guards

After two unsuccessful attempts under the same material hypothesis, stop modifying the system and reassess. Minor syntactic variation is not a materially different hypothesis.

The reassessment states current hypothesis, attempt 1, attempt 2, new evidence, why the work did not converge, and one next decision: use a materially different hypothesis; reduce scope; preserve a partial checkpoint; report blocked; request a missing artifact; stop for the timebox; or escalate.

Starvation is prolonged work without a meaningful artifact, narrowing, decision, or evidence. Stop expanding investigation; state effort consumed and unknowns; identify the smallest informative experiment; assess it against the remaining timebox; then stop or reduce scope if it does not fit.

Do not wait indefinitely for services, commands, infrastructure, tools, credentials, dependencies, or other actors. After a reasonable project-specific threshold, identify the wait, stop it when safe, preserve diagnostics, report blocked, and continue only through a scoped alternative.

Livelock is repeated changes/executions without increasing confidence: repeatedly changing the same selector/timeout, rerunning a failed test without a new hypothesis, oscillating implementations, refactoring around an unresolved defect, or changing unrelated code in hope. Report livelock risk and change strategy or stop.

## Cross-Skill Relationships

osk-execution-timebox defines the execution budget and stopping rules; this skill makes progress, convergence, and risks visible within that budget. A timebox limits resource consumption but does not prove progress. Observability enables intervention before the budget is exhausted, and a task can be within timebox yet non-convergent. Do not silently consume the final portion of a timebox without preserving verification and reporting time.

Execution observability describes the live state of work. osk-engineering-reporting preserves the final or checkpointed engineering record. Live updates must not duplicate a full final report.

This skill shows whether verification is converging. osk-verification-engineering defines reproducible test cases, execution, evidence, and result. Never collapse test designed, automation created, test executed, evidence collected, and behavior verified into one claim.

The mode applies across roles and technology domains, including architecture review, documentation, verification, knowledge curation, debugging, and infrastructure work.

## Examples

### QUIET

Rename a configuration field and references. Give no routine updates; interrupt silence only if compatibility problems emerge, then provide completed change and verification.

### STANDARD

Add a REST endpoint using an established pattern. Give a brief plan, report any relevant inconsistency in that pattern, then report implementation and test result.

### ATTENTIVE

Current state: a worker receives cancellation but no owner waits for termination.

Evidence: shutdown returns while the goroutine is still processing.

Decision: add a done signal owned by the worker and make Close wait for completion.

Next: implement lifecycle change and verify cancellation during active work.

### SUPERVISED

Hypothesis: an E2E failure is a UI assertion that runs before API completion.

Experiment: wait on the relevant response instead of adding a fixed timeout.

Evidence: failure persists; trace shows API response completed before rendering.

Decision: reject timing hypothesis; inspect state initialization and fixture isolation.

## Severity Model

| Severity | Meaning |
| --- | --- |
| BLOCKER | Destructive/high-risk action without escalation, silent data-loss risk, indefinite blocked execution, false completion/verification, or critical unauthorized scope expansion. |
| MAJOR | Prolonged observability loss, repeated non-convergence, late material blocker, ignored explicit mode, significant timebox consumption without evidence, or encouragement of private reasoning disclosure. |
| MINOR | Overly verbose update, missing optional checkpoint detail, slightly late non-material update, or inconsistent formatting. |
| NOTE | Optional clarity/mode improvement or educational observation. |

## Evidence and Validation

Report execution state from observed progress, decisions, risks, and blockers rather than inferred confidence. Validate that the selected observability mode received its required checkpoints and that any non-convergence guard was surfaced.

## Supporting Resources

None.
