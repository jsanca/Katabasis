# Agent Harness Guide

Use this guide when the task has a material choice of engineering evidence source or harness capability: an unfamiliar API/framework, possible semantic tooling, debugging, large/context-heavy investigation, repeated low-information activity, a scope-boundary question, or consequential repository action. Do not turn a routine focused edit with one clear repository-native command into a harness inventory.

This guide is portable policy and method. It does not provide permission enforcement, context management, subagents, IDE semantics, MCP, or a particular tool.

## Decide from the question

Before defaulting to shell/filesystem work, state:

1. What engineering question must be answered?
2. What evidence would answer it?
3. Is a known, appropriate authoritative capability currently available?

Use a known available specialized capability when it can safely establish the needed evidence. Otherwise use the narrowest safe portable fallback. Do not exhaustively discover every possible harness feature before acting.

Prefer the strongest **appropriate authoritative** capability for the question. “Strongest” means most capable of establishing relevant evidence safely; it does not mean newest, most automated, IDE-based, MCP-exposed, or semantic by default.

| Question | Prefer when appropriate | Fallback / limit |
| --- | --- | --- |
| Symbol, usage, or project-aware rename | Semantic index/refactor capability | Scoped source/text work when unavailable or inapplicable; verify with relevant build/tests. |
| Exact string or configuration | Targeted text search | Semantic navigation may not add value. |
| Build or test claim | Project-native build/test tooling | IDE diagnostics can narrow a problem but do not replace native evidence for a native claim. |
| Runtime behavior | Debugger or structured diagnostics | Targeted logs/traces/instrumentation; avoid speculative edit/log loops. |
| Dependency/API uncertainty | Project model, local source/artifact metadata, build feedback, or authoritative documentation/registry | Do not keep guessing or crawl unrelated files. |
| Generated artifact | Authoritative input and generator | Do not hand-edit generated regions; regenerate and verify. |

MCP is an integration path to a tool, not the engineering capability or proof by itself.

## Keep execution bounded

Default to the authorized project/workspace. Use an additional local path only when the user, task, project instructions, an applicable Skill, or known project tooling authorizes it.

- Prefer project-relative paths and narrow commands.
- Do not perform broad home-directory, Desktop, Downloads, machine-wide, unrelated-repository, or speculative artifact discovery.
- Inspect an explicitly named local reference before searching for an equivalent elsewhere.
- If an unapproved path or external action is necessary, stop and request authorization or clarification.

These are instructions, not a claim of deterministic enforcement. Use sandbox, permission, hook, or wrapper controls when the current harness actually provides and authorizes them; do not claim they exist otherwise.

## Change strategy when evidence stops improving

After a bounded attempt, ask:

1. What uncertainty did it address?
2. What did it establish, reject, or narrow?
3. What does it change about the next action?

Repeated activity without meaningful information gain is a signal to change strategy, not necessarily to stop. Information gain changes or narrows the hypothesis, capability availability, target/scope, failure cause, authorization need, or next decision. More output, an identical error, or another unsupported guess is not enough.

Do not impose arbitrary call limits: valid debugging can gain information over several iterations. If the same uncertainty remains, change evidence source as appropriate—for example, use semantic/local dependency information after compilation cannot resolve an API signature, structured diagnostics after logs cannot explain runtime behavior, or authorized package metadata when dependency coordinates are unknown. Ask a human when authority, acceptance criteria, or a missing fact is the blocker.

## Verify claims proportionately

Do not claim an engineering outcome solely from reasoning when proportionate authoritative verification is reasonably available.

| Claim | Evidence |
| --- | --- |
| Compiles or builds | Relevant compiler/build result. |
| Test passes | Executed relevant test-runner result. |
| Rename preserved references | Semantic refactor result when applicable, plus relevant build/tests for risk. |
| Dependency resolves | Resolver, project-model, or build result. |
| Runtime condition observed or fixed | Runtime/debugger/diagnostic observation, then suitable regression evidence. |
| UX or release accepted | Explicit human acceptance or agreed acceptance evidence. |

Match evidence to the claim and risk. Do not demand a full verification ceremony for every small action. Use `osk-verification-engineering` for requirements-to-test-case strategy, reproducible automation, result classification, and retained verification evidence.

## Preserve useful context

- Read targeted files/ranges before whole trees or logs when sufficient.
- Keep stable evidence paths, commands, and concise summaries; retain a route to full output.
- Do not reread evidence already in context unless it changed or a precise detail is needed.
- Load implementation detail only when it answers the active question.
- When context pressure is observable, preserve the current hypothesis, decision-relevant evidence, unresolved question, and stable paths before compaction, restart, or handoff.

Do not assume a harness exposes context inspection, compaction, checkpoints, worktrees, or isolation.

## Use optional delegation responsibly

If the harness supports subagents or isolated tasks, use them only for bounded work such as API research, test-failure investigation, independent review, or context-heavy evidence gathering. Give the delegate a scoped question, authorized boundary, and expected evidence. Review returned evidence yourself: the parent remains responsible for the task.

An OSK Role is a durable responsibility and judgment boundary; a harness subagent is an execution-local mechanism. They are not equivalent.

## Degrade honestly and escalate

Use richer capabilities when they are available and appropriate. With only filesystem, shell, and native build/test tools, apply the same boundary discipline, targeted evidence, strategy changes, and honest verification. Never claim semantic, enforcement, context-isolation, or MCP guarantees that the available fallback cannot provide.

Stop and state the smallest useful outcome when:

- a required capability is unavailable and no safe fallback can establish the claim;
- a necessary path/action lacks authorization;
- evidence remains insufficient after credible evidence-source changes;
- current strategies no longer produce useful information; or
- human clarification is needed for scope, authority, acceptance, or contradiction.

State what was attempted, what it established, what remains unproven, and the next credible escalation. Do not fabricate certainty.
