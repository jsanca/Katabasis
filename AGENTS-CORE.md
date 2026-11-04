# AGENTS-CORE.md

Canonical shared engineering guidance for AI agents working on Katabasis.

`AGENTS.md` (Codex) and `CLAUDE.md` (Claude Code) are thin tool-specific adapters that reference this file. Edit the shared rules here, once. Project knowledge (current architecture, domain, roadmap) remains canonical in `docs/`, starting at [docs/PROJECT.md](docs/PROJECT.md).

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
| `katabasis-cli`   | `jsanca.katabasis.cli`   | CLI stub — not yet wired to core                     |
| `katabasis-ui`    | `jsanca.katabasis.ui`    | JavaFX desktop GUI; entry point (`KatabasisLauncher`) |

Module dependency direction: `katabasis-cli` → `katabasis-core` ← `katabasis-ui`.
`katabasis-cli` does not yet declare `requires jsanca.katabasis.core`.

Detailed current architecture: [docs/knowledge/architecture/katabasis-core.md](docs/knowledge/architecture/katabasis-core.md).

### `katabasis-core` — key concepts

| Component                  | Responsibility                                                                 |
|----------------------------|--------------------------------------------------------------------------------|
| `DownloadManager`          | Orchestration: accepts requests, manages lifecycle, emits events, delegates    |
| `DownloadExecutor`         | Concurrency layer — virtual threads + semaphore for parallelism control        |
| `DownloadStrategy`         | Encapsulates *how* a download runs: `HttpDownloadStrategy`, `SegmentedHttpDownloadStrategy`, `HttpDownloadOrchestrator`; `S3DownloadStrategy` is an empty stub |
| `DownloadStrategyResolver` | Picks the right strategy for a given request (based on URI scheme, etc.)       |
| `DownloadTask` (internal)  | Mutable live-download state — never exposed; callers receive `DownloadTaskSnapshot` |
| `DownloadRegistry`         | In-memory store of active tasks — lookup, cancel, snapshot queries             |
| `DownloadStateStore`       | Persistence interface (not yet implemented — see In-memory note below)         |
| `DownloadEvent`            | Event types: `Started`, `Progress`, `Completed`, `Failed`, `Paused`, `Resumed`, `Cancelled` |
| `CancellationToken`        | Cooperative cancellation mechanism passed into executing strategies            |
| `PauseCoordinator`         | Pause/resume coordination (`PauseToken`, `PauseListener`)                      |
| `RetryPolicy`              | Empty interface plus empty placeholder classes (`NoRetryPolicy`, `FixedDelayRetryPolicy`, `ExponentialBackoffRetryPolicy`) that do not implement it; retry is not functional |

### JPMS API boundary

`katabasis-core` only exports `api.*` packages (`api.event`, `api.exception`, `api.manager`, `api.model`). The `internal.*` packages (`internal.execution`, `internal.strategy`, `internal.http`, `internal.s3`, `internal.retry`, `internal.model`, `internal.util`) are module-private and must never be referenced from `katabasis-ui` or `katabasis-cli`.

### Interface + Factory pattern

Every domain concept follows: `Xxx` interface + `Xxxs` (or `XxxManagers`) static factory class.
Implementations are `private static final` inner classes of the factory.
**Concrete types must never leak into public APIs** — callers use the interface type exclusively.

### Domain types

Core public data types use Java **records**: `DownloadRequest`, `DownloadConfig`, `DownloadInfo`, `DownloadTaskSnapshot`, `DownloadSubmissionResult`, `DownloadControlResult`, `DownloadProgress`, and `DownloadEvent` subtypes. `DownloadTask` is a mutable internal class, not a record and not public API.

### Concurrency model

- **Virtual Threads (Project Loom)** — blocking I/O is cheap, no complex async pipelines.
- **Structured Concurrency** preferred over raw thread pools.
- **`ScopedValue`** over `ThreadLocal`.
- Cooperative cancellation via `CancellationToken`.
- Cooperative pause via `PauseCoordinator` / `PauseToken`.
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

- Functional retry (`RetryPolicy` contract + strategy integration) — roadmap 0.1
- Checksum validation — roadmap 0.2
- Persistent registry and recovery (`DownloadStateStore`) — roadmap 0.2
- CLI wired to core — roadmap 0.2
- FTP/FTPS and S3 protocol support — roadmap 0.3
- BitTorrent, pending a Go/No-Go feasibility gate — roadmap 0.4

Committed direction: [docs/roadmap/ROADMAP.md](docs/roadmap/ROADMAP.md).

## Coding conventions

- **Composition over inheritance.** Build small collaborating parts; avoid deep type trees.
- **Records over raw maps.** Wrap domain data in records; avoid `Map<String, Object>`.
- **Primitives first.** Use `int`, `long`, `boolean` unless a wrapper is required.
- **Public API clarity.** Public methods ≤ 20 lines, documented with JavaDoc, validate nulls with `Objects.requireNonNull`.
- **No hidden magic.** Avoid dynamic proxies, reflection, bytecode manipulation, or annotation-driven behavior unless explicitly requested.
- **Do NOT modify `pom.xml` or introduce new dependencies** unless the task explicitly calls for it. Runtime dependencies: `commons-lang3`, `slf4j-api` + `slf4j-simple`.
- **If a task is documentation-only, make ZERO code changes.**
- **Always curly brackets in if/else/for/while.** No exceptions, even if the body is a single line.

## Test conventions

- JUnit 5 (`junit-jupiter`), no Mockito, no AssertJ.
- No shared base classes or fixtures — each test is self-contained.
- Tests mirror the source package structure.
- Tests named descriptively (e.g., `downloadManagerShouldEmitCompletedEventOnSuccess`).
- Concurrency tests use `CountDownLatch`, `CopyOnWriteArrayList`, `AtomicInteger` manually.

## Record + Builder pattern

When a record needs incremental construction or copy-with-modifications, add an inner `Builder`. The example below is illustrative (not a current API type):

```java
public record TransferRequest(URI uri, Path destination, Optional<String> checksum) {

    public TransferRequest {
        Objects.requireNonNull(uri);
        Objects.requireNonNull(destination);
        Objects.requireNonNull(checksum);
    }

    public static TransferRequest of(URI uri, Path destination) {
        return new TransferRequest(uri, destination, Optional.empty());
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

        public TransferRequest build() {
            return new TransferRequest(uri, destination, Optional.ofNullable(checksum));
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

After each task, report: files changed, tests run, and any architectural questions discovered. Durable engineering records follow [docs/OSK.md](docs/OSK.md) and live under `docs/engineering/`.
