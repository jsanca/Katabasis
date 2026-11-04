# Deterministic QA Evidence — <TASK-ID>

> Execution evidence, not a review verdict or authorization decision. Include only categories actually selected and state omitted categories explicitly.

## Task association

- Task:
- Objective or durable task reference:
- Execution scope: `FULL / PARTIAL / TARGETED / CHANGED-CODE`
- Targets/modules:
- Executor:
- Workspace/project identity:

## Execution provenance

- Executed (UTC):
- Revision:
- Working-tree snapshot / digest:
- Staged tracked diff / digest:
- Unstaged tracked diff / digest:
- Untracked-file handling (captured content, manifest only, or unavailable):
- Runtime/tool versions:

## Results

Overall execution result: `PASS / FAIL / PARTIAL / INCONCLUSIVE / BLOCKED`.

| Check | Classification | Why applicable | Command / tool | Scope/input | Result | Bounded raw evidence |
| --- | --- | --- | --- | --- | --- | --- |
| <check> | `DETERMINISTIC GATE / POLICY THRESHOLD / EVIDENCE SIGNAL` | <reason> | `<command>` | <scope> | <result> | <path + digest when practical> |

## Not executed

- <category/check and reason>

## Findings and limits

- What this evidence supports:
- What it does not establish:
- Freshness/working-tree limitation:
- Redaction or raw-evidence limitation:
- Exact task report/review link if this package is promoted as accepted durable evidence:

## Reproduction

- Prerequisites:
- Commands:
- Expected result/evidence location:
