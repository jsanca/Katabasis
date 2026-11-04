# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## Build & Test

- **Compile:** `mvn compile`
- **All tests:** `mvn test`
- **Single test:** `mvn test -Dtest=DownloadManagerTest`
- **Full verification:** `mvn compile && mvn test-compile && mvn test`
- **Requires Java 25.** Maven is pinned to `release=25` in `pom.xml`. No other JDK version will work.

### Packaging

```bash
# Full install (skip tests)
mvn clean install -DskipTests

# Generate custom runtime image with jlink
mvn -f katabasis-ui/pom.xml clean org.openjfx:javafx-maven-plugin:0.0.8:jlink -DskipTests

# Package macOS app bundle with jpackage
jpackage \
  --type app-image \
  --name Katabasis \
  --dest "$(pwd)/dist" \
  --runtime-image "$(pwd)/katabasis-ui/target/katabasis" \
  --module jsanca.katabasis.ui/jsanca.katabasis.ui.app.KatabasisLauncher
```

## Architecture

### Module layout

Multi-module Maven project (`pom.xml` at root). Three modules:

| Maven module      | JPMS module              | Responsibility                                       |
|-------------------|--------------------------|------------------------------------------------------|
| `katabasis-core`  | `jsanca.katabasis.core`  | Core engine — download lifecycle, events, strategies |
| `katabasis-cli`   | `jsanca.katabasis.cli`   | Command-line interface                               |
| `katabasis-ui`    | `jsanca.katabasis.ui`    | JavaFX desktop GUI; entry point (`KatabasisLauncher`) |

Module dependency direction: `katabasis-cli` → `katabasis-core` ← `katabasis-ui`.

### `katabasis-core` — key concepts

| Component                  | Responsibility                                                                 |
|----------------------------|--------------------------------------------------------------------------------|
| `DownloadManager`          | Orchestration: accepts requests, manages lifecycle, emits events, delegates    |
| `DownloadExecutor`         | Concurrency layer — virtual threads + semaphore for parallelism control        |
| `DownloadStrategy`         | Encapsulates *how* a download runs (`HttpDownloadStrategy`, `S3DownloadStrategy`) |
| `DownloadStrategyResolver` | Picks the right strategy for a given request (based on URI scheme, etc.)       |
| `DownloadTask`             | Live download representation: status, progress, timestamps, cancellation state |
| `DownloadRegistry`         | In-memory store of active tasks — lookup, cancel, snapshot queries             |
| `DownloadStateStore`       | Persistence interface (not yet implemented — see In-memory note below)         |
| `DownloadEvent`            | Event types: `Started`, `Progress`, `Completed`, `Failed`, `Paused`, `Resumed`, `Cancelled` |
| `CancellationToken`        | Cooperative cancellation mechanism passed into executing strategies            |
| `PauseCoordinator`         | Pause/resume coordination (`PauseToken`, `PauseListener`)                      |
| `RetryPolicy`              | Pluggable retry: `NoRetryPolicy`, `FixedDelayRetryPolicy`, `ExponentialBackoffRetryPolicy` |

### JPMS API boundary

`katabasis-core` only exports `api.*` packages (`api.event`, `api.exception`, `api.manager`, `api.model`). The `internal.*` packages (`internal.execution`, `internal.strategy`, `internal.http`, `internal.s3`, `internal.retry`, `internal.model`, `internal.util`) are module-private and must never be referenced from `katabasis-ui` or `katabasis-cli`.

### Interface + Factory pattern

Every domain concept follows: `Xxx` interface + `Xxxs` (or `XxxManagers`) static factory class.
Implementations are `private static final` inner classes of the factory.
**Concrete types must never leak into public APIs** — callers use the interface type exclusively.

### Domain types

Core data types use Java **records**: `DownloadRequest`, `DownloadTask`, `DownloadEvent` subtypes, `DownloadProgress`, `WebHttpResponse`.

### Concurrency model

- **Virtual Threads (Project Loom)** — blocking I/O is cheap, no complex async pipelines.
- **Structured Concurrency** preferred over raw thread pools.
- **`ScopedValue`** over `ThreadLocal`.
- Cooperative cancellation via `CancellationToken`.
- Parallelism bounded by a `Semaphore` inside `DownloadExecutor`.
- When iterating over a `synchronized` collection in tests, synchronize **explicitly** on the collection object during the loop.

### In-memory only (by design)

`DownloadRegistry` and all task state are in-memory. The `DownloadStateStore` interface is a placeholder for future persistence. Do not implement persistence without explicit request.

### `katabasis-ui` — UI architecture

Single-screen JavaFX application; **no FXML** — UI is constructed programmatically in `DownloadManagerApplication`.

- `KatabasisLauncher` — JPMS-compatible entry point (delegates to `DownloadManagerApplication`)
- `DownloadManagerApplication` — JavaFX `Application` subclass; owns the scene, `ObservableList<DownloadRow>`, and the `DownloadManager` event listener
- `DownloadRow` — inner `record` used as the data model for each list row
- `DownloadRowCell` — inner `ListCell` subclass that renders progress bars, status labels, play/pause and cancel buttons, and a right-click context menu
- Drag-and-drop of files containing URLs, clipboard paste, and directory chooser are handled inline in `DownloadManagerApplication`
- Styling: AtlantaFX dark theme (`atlantafx-base`) + custom `style.css`; icons via Ikonli + Material Design 2 pack

### `katabasis-cli` — status

Currently a stub (`codex.Main` prints a loop). Module-info exports nothing and does not yet depend on `katabasis-core`.

## Planned Features (do not implement without explicit request)

- Segmented (parallel-chunk) downloads
- Checksum validation
- Persistent registry (filesystem / database)
- Additional protocols: FTP, custom
- CLI wired to core

## Coding conventions

- **Composition over inheritance.** Build small collaborating parts; avoid deep type trees.
- **Records over raw maps.** Wrap domain data in records; avoid `Map<String, Object>`.
- **Primitives first.** Use `int`, `long`, `boolean` unless a wrapper is required.
- **Public API clarity.** Public methods ≤ 20 lines, documented with JavaDoc, validate nulls with `Objects.requireNonNull`.
- **No hidden magic.** Avoid dynamic proxies, reflection, bytecode manipulation, or annotation-driven behavior unless explicitly requested.
- **Do NOT modify `pom.xml` or introduce new dependencies** unless the task explicitly calls for it. Runtime dependencies: `commons-lang3`, `slf4j-api` + `slf4j-simple`.
- **If a task is documentation-only, make ZERO code changes.**
- **always curly brackets in if/else/for/while.**
  No exceptions, even if the body is a single line
## Test conventions

- JUnit 5 (`junit-jupiter`), no Mockito, no AssertJ.
- No shared base classes or fixtures — each test is self-contained.
- Tests mirror the source package structure.
- Tests named descriptively (e.g., `downloadManagerShouldEmitCompletedEventOnSuccess`).
- Concurrency tests use `CountDownLatch`, `CopyOnWriteArrayList`, `AtomicInteger` manually.

## Record + Builder pattern

When a record needs incremental construction or copy-with-modifications, add an inner `Builder`:

```java
public record DownloadRequest(URI uri, Path destination, Optional<String> checksum) {

    public DownloadRequest {
        Objects.requireNonNull(uri);
        Objects.requireNonNull(destination);
        Objects.requireNonNull(checksum);
    }

    public static DownloadRequest of(URI uri, Path destination) {
        return new DownloadRequest(uri, destination, Optional.empty());
    }

    public static Builder builder() { return new Builder(); }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.uri(uri).destination(destination);
        checksum.ifPresent(b::checksum);
        return b;
    }

    public static final class Builder {
        private URI uri;
        private Path destination;
        private String checksum;

        private Builder() {}

        public Builder uri(URI uri)               { this.uri = Objects.requireNonNull(uri); return this; }
        public Builder destination(Path dest)     { this.destination = Objects.requireNonNull(dest); return this; }
        public Builder checksum(String checksum)  { this.checksum = Objects.requireNonNull(checksum); return this; }

        public DownloadRequest build() {
            return new DownloadRequest(uri, destination, Optional.ofNullable(checksum));
        }
    }
}
```

Key rules:
- Compact constructor validates all fields with `Objects.requireNonNull` and defensively copies `List`/`Map` with `List.copyOf`/`Map.copyOf`.
- Optional fields use `String` (null = absent) in the builder; `build()` wraps with `Optional.ofNullable`.
- `toBuilder()` uses `ifPresent` to copy Optional fields — never passes `null` to a setter.
- Records provide `equals`, `hashCode`, `toString`, and accessors — do not add them.

## Reporting

After each task, report: files changed, tests run, and any architectural questions discovered.
<!-- OSK:BEGIN -->

## OSK Workspace

Read:

- `docs/PROJECT.md`
- `docs/OSK.md`

<!-- OSK:END -->

<!-- OSK:SKILLS:BEGIN -->

## OSK Installed Skills

- [Adversarial Analysis](.osk/skills/osk-adversarial-analysis/SKILL.md)
- [Architecture Review](.osk/skills/osk-architecture-review/SKILL.md)
- [Architecture Boundary Review](.osk/skills/osk-boundary-review/SKILL.md)
- [Code Documentation Publication](.osk/skills/osk-code-docs/SKILL.md)
- [Knowledge Curator](.osk/skills/osk-knowledge-curator/SKILL.md)
- [Knowledge Integrity Review](.osk/skills/osk-knowledge-integrity-review/SKILL.md)
- [diagram-design](.osk/skills/diagram-design/SKILL.md)
- [Agent Harness Guide](.osk/skills/osk-agent-harness-guide/SKILL.md)
- [Java Engineering Guide](.osk/skills/osk-java-guide/SKILL.md)
- [Engineering Reporting](.osk/skills/osk-engineering-reporting/SKILL.md)
- [Execution Observability](.osk/skills/osk-execution-observability/SKILL.md)
- [Execution Timebox](.osk/skills/osk-execution-timebox/SKILL.md)
- [Verification Engineering](.osk/skills/osk-verification-engineering/SKILL.md)
<!-- OSK:SKILLS:END -->
