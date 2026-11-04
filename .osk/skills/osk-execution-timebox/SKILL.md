# Execution Timebox

## Mission

Prevent agent loops, context exhaustion, uncontrolled scope expansion, and unreliable recaps during non-trivial execution. Stop cleanly and produce a structured recovery checkpoint when progress, time, or state no longer permits a reliable completion.

## Scope

### Covers

- Agent execution tasks with a concrete outcome: code, migrations, tests, documentation, or other bounded artifacts.
- Target completion, progress review, hard stop, early stop, recovery checkpoint, handoff, and invalid-execution behavior.
- Task splitting for work that combines too many concerns.

### Does not cover

- Roadmap slices intentionally designed for multi-session planning.
- Redesigning the task's methodology, implementation, or engineering report content beyond safe recovery/handoff requirements.

## Responsibilities

- Treat 20–30 minutes as the target completion window.
- At 30 minutes, continue only when remaining work is small, fully understood, and measurable.
- At 45 minutes, stop and create a recovery checkpoint; do not continue coding.
- Stop earlier for repeated failures without new evidence, repeated reasoning/commands, unclear/shifting objective, scope drift, or unexplained repository state.
- Read an existing OPEN checkpoint before continuing and resolve or supersede it after recovery.
- Split overly broad work into smaller execution tasks.

## Boundaries / Constraints

- A hard-stop execution must create the checkpoint before ending.
- Do not claim DONE when repository state, build/test status, changed files, or remaining work cannot be explained confidently.
- A recap naming a different active task, repeating content, claiming unsupported success, or disagreeing with changed files is INCOMPLETE or INVALID.
- Do not use a recovery checkpoint as a final implementation report.

## Required Inputs

- A concrete active task, objective, and target/hard-stop duration.
- Current progress, repository state, validation evidence, and any existing OPEN checkpoint.

## Expected Outputs

- Either a bounded completion/handoff with validated state or a recovery checkpoint containing objective, completed work, files changed, repository state, validation, blocker/evidence, remaining work, continuation tasks, next action, and status.
- A clear stop/continue decision at the 30-minute review and a stop at the 45-minute hard limit.

## Workflow

1. Confirm the work is an agent execution task rather than a roadmap slice; read any OPEN checkpoint.
2. Work toward the 20–30-minute target while keeping repository state explainable.
3. At 30 minutes, assess whether measurable, fully understood remaining work is small enough to finish.
4. Stop immediately on any early stop condition.
5. At 45 minutes, stop regardless of remaining work and write the recovery checkpoint.
6. Validate changed files and actual build/test status; mark incomplete or invalid rather than claiming unsupported completion.
7. Split remaining work into 15–30-minute continuation tasks and recommend the next action.

## Questions to Ask

- Is this a concrete execution task or an intentionally broad roadmap slice?
- What is the target duration and hard stop?
- What OPEN checkpoint exists, if any?
- Can the remaining work, repository state, and validation result be stated exactly?

## Escalation Rules

- Unclear or shifted objective → request clarification and stop rather than expand scope.
- Repeated failure without new diagnostic evidence → stop and create a checkpoint.
- Hard stop reached → create a checkpoint and hand off.
- Repository state cannot be explained → mark incomplete/invalid and request recovery or review.

## Quality Checklist

- [ ] The active task and time limits are explicit.
- [ ] Existing OPEN checkpoint was read before continuation.
- [ ] Continue decisions after 30 minutes have measurable, understood remaining work.
- [ ] Hard/early stop produced the full recovery checkpoint.
- [ ] Changed files, build/test state, and remaining work are evidence-backed.
- [ ] Continuation tasks are bounded to 15–30 minutes.

## Examples

### Valid 30-minute continuation

Persistence mapper and adapter are complete and tested; only understood configuration wiring remains, estimated at ten minutes. Continuing is valid.

### Hard stop

A migration ran but validation repeats the same column-type mismatch. Stop at the hard limit, record the error excerpt and state, then hand off through the checkpoint.

## Anti-Patterns

- **Continuing past the hard stop** — stop and create the recovery checkpoint.
- **Retrying the same failure without evidence** — stop rather than consuming more context.
- **Treating an unexplained state as complete** — mark incomplete or invalid.
- **Combining many concerns in one task** — split into bounded continuation tasks.

## Dependencies

Not applicable: this foundational process skill has no canonical skill dependencies.

## Activation Conditions

Apply to a bounded agent execution task with a concrete outcome, especially when a target/hard stop, recovery checkpoint, scope control, or loop prevention is needed. Do not apply solely to a broad roadmap slice intentionally planned across multiple sessions.

## Evidence and Validation

Record the task target, remaining budget, progress evidence, and stop or handoff decision. Validate that any interruption, timeout, or invalid execution has the required recovery checkpoint.

## Supporting Resources

None.
