# Execution Observability

This canonical OSK process skill makes an agent's live execution understandable and interruptible without turning work into command-by-command narration.

Invoke a mode in a task header:

~~~
execution:
  timebox: 45m
  observability: attentive
~~~

Or state it directly: Apply osk-execution-observability in SUPERVISED mode.

| Mode | Intended use | Routine updates |
| --- | --- | --- |
| QUIET | Trivial, low-risk work | Final result only; escalation always interrupts silence. |
| STANDARD | Normal engineering work | Plan and meaningful milestones. |
| ATTENTIVE | Important work a supervisor wants to follow | Decisions, evidence, risks, and next action. |
| SUPERVISED | High-risk or uncertain execution | Hypothesis-driven checkpoints and loop guards. |

Execution observability makes live state visible. osk-execution-timebox supplies execution budget and stopping rules; osk-engineering-reporting preserves final or checkpointed records; osk-verification-engineering defines reproducible verification rather than live status updates.
