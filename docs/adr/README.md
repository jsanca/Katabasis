# Architecture Decision Records

## Purpose

Store durable decisions whose alternatives and consequences matter to future engineers.

## What belongs here

ADRs that record context, decision, and consequences for architectural or long-lived technical choices.

**Example:** "Choose PostgreSQL instead of Provider X" with alternatives and consequences is an ADR. "The project uses PostgreSQL" is current knowledge in `../knowledge/`.

## What does not belong here

Task reports, general documentation, or a restatement of the current architecture. Keep current system understanding in `../knowledge/`.

---

## ADR Index

No ADRs have been decided yet.

## Pending ADR Candidates

The following decisions have been identified as requiring an ADR before implementation can proceed. Each is associated with a roadmap phase.

| ID | Question | Phase | Blocking Task |
| --- | --- | --- | --- |
| ADR-001 | How should `RetryPolicy` expose its contract? (decorator vs. callback vs. interface methods) | 0.1 | K-0.1-T03 |
| ADR-002 | How should `DownloadStateStore` persist state? (file/JSON vs. SQLite vs. other) | 0.2 | K-0.2-T09 |
| ADR-003 | Should `DownloadManager` implement `AutoCloseable` for graceful shutdown? | 0.2 | K-0.2-T03 |
| ADR-004 | Which CLI framework? (picocli vs. manual argument parsing vs. other) | 0.2 | K-0.2-T01 |
| ADR-005 | How to implement FTP/FTPS? (Apache Commons Net vs. pure JDK socket vs. other) | 0.3 | K-0.3-T01 |
| ADR-006 | How to implement S3? (AWS SDK v2 full vs. lightweight S3-compatible HTTP approach) | 0.3 | K-0.3-T09 |
| ADR-007 | BitTorrent Go/No-Go: integrate library vs. implement piece protocol vs. defer | 0.4 | K-0.4-T01 |
| ADR-008 | Should `DownloadStrategy` expose a capability interface? (`supportsRanges()`, `supportsPause()`) | 0.3 | K-0.3-T02 |

### Naming Convention

When an ADR is written, name the file `<ID>-<short-slug>.md` (e.g., `ADR-001-retry-policy-contract.md`).

### Suggested Structure

```markdown
# ADR-NNN — Title

## Status
Proposed | Accepted | Superseded | Deprecated

## Context
What problem are we solving? What constraints apply?

## Decision
What did we decide?

## Alternatives Considered
What else was evaluated?

## Consequences
What becomes easier and harder as a result of this decision?
```
