# K-DOC-004 — Core Javadoc Coverage — Report

## Status

Complete

## Objective

Document `katabasis-core` classes and public methods that had no Javadoc, including the `DownloadManager` control methods. The Knowledge Curator role was used for the audit and classification; the Javadoc edits were applied as explicitly authorized code documentation.

## Audit

Command: static scan of all 76 Java files under `katabasis-core/src/main/java`, checking for a Javadoc block immediately before each type declaration and each public/interface method.

| Finding | Count | Classification |
| --- | --- | --- |
| Type declarations without class-level Javadoc | 37 | Missing |
| Public methods without Javadoc (excluding `@Override`) | 52 | Missing |
| Interface methods without Javadoc (implicitly public) | 4 | Missing |
| **Total gaps** | **93** | — |

Gaps by package:

| Package | Types without docs |
| --- | --- |
| `api.event` | 2 |
| `api.exception` | 4 |
| `api.manager` | 1 |
| `api.model` | 7 (including `DownloadConfig.Builder`) |
| `internal.exception` | 3 |
| `internal.execution` | 6 (including `PauseCoordinator.PauseState`) |
| `internal.http` | 2 |
| `internal.model` | 1 |
| `internal.retry` | 4 |
| `internal.s3` | 5 |
| `internal.util` | 2 |

## Changes

### Class-level documentation

All 37 type declarations now have a contract-level Javadoc: purpose, current status, and — for placeholders — an explicit statement that they are not wired into the engine. Placeholder status was verified by reference count before writing the claim: `DownloadEventPublisher`, `DefaultDownloadEventPublisher`, `DownloadStateStore`, `DownloadCapabilities`, `DownloadCommand`, `DownloadProgress`, `DownloadScheduler`, `ExecutionSlotController`, `ProgressReporter`, `QueuedDownloadTask`, `HttpDownloadClientFactory`, `HttpDownloadOptions`, the four retry types, and the five S3 types occur only in their own declaration.

`DownloadException`, `DownloadExecutionException`, `DownloadRejectedException`, `InvalidDownloadRequestException`, and `StrategyNotFoundException` are likewise declared but never thrown; their Javadoc states so and points to the mechanism actually used instead.

### Method documentation

56 public and interface methods gained Javadoc:

- `DownloadManager.pause` / `resume` — cooperative semantics and `REQUESTED` result.
- `DownloadConfig` factory, builder, and build methods.
- `DownloadStatus.isTerminal` / `isActive`.
- `DownloadCancelledException` constructor and accessors.
- `HttpDownloadStatusException` constructor, accessors, and status-to-error-code mapping.
- `Scheme.from`.
- `MapperExceptionUtil.mapExceptionToErrorCode`.
- `Preconditions` check/reject variants.
- `PauseCoordinator` constructor, `PauseListener.onPaused` / `onResumed`.
- `Main.main`.
- Exception constructors across the five exception types.

`DownloadConfig`'s class Javadoc also had empty `@param` entries; they now describe each component and the defaults.

### Javadoc generation fix

`mvn -pl katabasis-core javadoc:javadoc` failed with 29 `bad HTML entity` errors caused by raw `&` in `@author` tags (`@author jsanca & elo`). The entity was corrected to `&amp;` in 64 core source files, one duplicated author typo (`jsnca`) was fixed, and generation now succeeds. This is a comment-only change.

## Files Changed

- 65 files under `katabasis-core/src/main/java` — Javadoc additions and author-entity corrections.
- `docs/engineering/agents/tasks/K-DOC-004.md` — task record.
- `docs/engineering/agents/reports/K-DOC-004-javadoc-core.md` — this report.
- `docs/engineering/ENGINEERING_LOG.md` — added the K-DOC-004 row.

No signatures, visibility, control flow, or tests were modified.

## Evidence

- Placeholder reference counts: `grep -rn "\b<Type>\b" --include="*.java"` returned one occurrence (the declaration) for every placeholder listed above.
- `DownloadCancelledException` accessors and `HttpDownloadStatusException.mapHttpStatusToErrorCode` were documented from their implementations.
- `DownloadManager.pause` / `resume` were documented from `DownloadManagers.DefaultDownloadManager`, which registers the request through `PauseCoordinator` and returns `DownloadControlStatus.REQUESTED`.

## Validation

```
# Post-change audits
types without javadoc: 0
public methods missing javadoc: 0

mvn -q -pl katabasis-core -am compile
COMPILE_EXIT=0

mvn -q -pl katabasis-core test
TEST_EXIT=0
(logged stack traces are expected failure-path assertions)

mvn -q -pl katabasis-core javadoc:javadoc -DskipTests
JAVADOC_EXIT=0
0 errors
```

Test and Javadoc runs wrote into the tracked `katabasis-core/target` directory; those generated changes were reverted so the working tree contains only source documentation changes.

## Limitations

- Javadoc describes current behavior; it does not change the fact that several documented types are dead placeholders. Removing or implementing them is a separate decision.
- Warnings remain for `module-info.java` and default constructors (for example `DownloadRegistry`), but there are no Javadoc errors.
- The audit is heuristic (Javadoc immediately preceding a declaration). Annotations between Javadoc and declaration are tolerated, but unusual formatting could evade it.
- A concurrent, uncommitted `K-0.1-T02` task and `DownloadManagerTest` appeared in the working tree during this work; they were left untouched.

## Open Follow-Up

- Decide whether to delete the documented dead placeholders or keep them until their roadmap phases.
- Add `mvn javadoc:javadoc` and the audit script to CI so documentation coverage does not regress.
- Consider `doclint` configuration for `module-info.java` if a warning-free build is required.

## Related Records

- Task: [K-DOC-004.md](../tasks/K-DOC-004.md)
- Predecessor: [K-DOC-001](K-DOC-001-documentation-reconciliation.md)
- Canonical architecture: [katabasis-core.md](../../../knowledge/architecture/katabasis-core.md)
