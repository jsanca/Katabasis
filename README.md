# Katabasis

> *"A descent into the depths — bringing remote artifacts into the local world."*

## 🧠 What is Katabasis?

**Katabasis** is a modular, event-driven download manager written in Java, designed with clarity, extensibility, and modern concurrency in mind.

At its core, Katabasis is about **bringing resources from remote systems into the local environment** — reliably, observably, and in a way that can evolve over time.

The name comes from the Greek concept *katábasis*, meaning a descent — often used in literature to describe a journey from a higher realm into a deeper or hidden one. In this context:

> Katabasis is the act of descending into remote systems and retrieving their artifacts.

---

## 🎯 Design Philosophy

Katabasis is not just a downloader — it is an exploration of **clean architecture and modern Java design**:

- **Separation of concerns**
- **Event-driven lifecycle**
- **Strategy-based execution**
- **Cooperative concurrency**
- **Extensibility by design**

The system was intentionally structured to evolve into more advanced capabilities. Some are already delivered; others remain on the roadmap:

- cancellation and pause/resume — delivered
- segmented (parallel) downloads — delivered
- pluggable protocols — HTTP today; FTP and S3 on the roadmap
- persistence and recovery — on the roadmap

---

## 🧩 Core Components

### `DownloadManager`
The orchestration layer.

Responsible for:
- accepting requests
- managing lifecycle
- emitting events
- delegating execution

---

### `DownloadExecutor`
Handles concurrency.

- Uses **virtual threads (Project Loom)**
- Controls parallelism via semaphore
- Decouples execution policy from business logic

---

### `DownloadStrategy`
Encapsulates *how* a download is performed.

Current implementations:
- `HttpDownloadStrategy` — single-stream HTTP/HTTPS via JDK `HttpClient`
- `SegmentedHttpDownloadStrategy` — parallel byte-range chunks via `StructuredTaskScope`
- `HttpDownloadOrchestrator` — probes `Accept-Ranges` and picks single-stream or segmented

Future:
- FTP/FTPS
- S3 (only an empty stub class exists today)
- custom protocols

---

### `DownloadStrategyResolver`
Decides *which* strategy to use based on the request (e.g., URI scheme).

---

### `DownloadTask`
Internal mutable representation of a live download.

Tracks:
- status
- progress
- timestamps
- cancellation and pause state

Never exposed publicly — callers receive an immutable `DownloadTaskSnapshot`.

---

### `DownloadRegistry`
In-memory registry of active downloads.

Enables:
- lookup by ID
- control operations (cancel, pause, resume)
- snapshot queries

---

### `DownloadEvent`
Event-driven communication layer.

Examples:
- `DownloadStartedEvent`
- `DownloadProgressEvent`
- `DownloadPausedEvent`
- `DownloadResumedEvent`
- `DownloadCompletedEvent`
- `DownloadFailedEvent`
- `DownloadCancelledEvent`

---

## ⚙️ Concurrency Model

Katabasis leverages **virtual threads** to simplify concurrency:

- blocking I/O becomes cheap
- no need for complex async pipelines
- cooperative cancellation via `CancellationToken`

---

## 🚀 Current Features

- HTTP/HTTPS downloads using JDK `HttpClient`
- single-stream and segmented (parallel byte-range) downloads, auto-selected per server support
- progress tracking and event emission
- cooperative cancel, pause, and resume
- configurable concurrency and segment count
- modular architecture (strategy + resolver)
- thread-safe execution model

---

## 🔜 Planned Features

- functional retry policies (`RetryPolicy` is currently an empty interface)
- checksum validation
- CLI interface (`katabasis-cli` exists only as a stub)
- persistent registry and recovery (`DownloadStateStore` is a placeholder)
- FTP/FTPS and S3 protocol support
- BitTorrent support, pending a feasibility gate

---

## 🧪 Example

```java
DownloadManager manager = DownloadManagers.createDefault();

manager.addListener(System.out::println);

DownloadSubmissionResult result = manager.download(new DownloadRequest(
    URI.create("https://example.com/file.pdf"),
    Path.of("./file.pdf")
));

System.out.println("Download id: " + result.downloadId());
```


## Build and package the JavaFX application

The project now supports building a custom runtime image with `jlink` and packaging a macOS app bundle with `jpackage`.

### Requirements

- JDK 25 installed and available on `PATH`
- Maven installed and available on `PATH`
- The JavaFX UI module already builds successfully

### 1. Build the project

From the repository root:

```bash
mvn clean install -DskipTests
```

### 2. Generate the custom runtime image with jlink

From the repository root:

```bash
mvn -f katabasis-ui/pom.xml clean org.openjfx:javafx-maven-plugin:0.0.8:jlink -DskipTests
```

This creates the runtime image under:

```text
katabasis-ui/target/katabasis
```

You can test the generated launcher with:

```bash
./katabasis-ui/target/katabasis/bin/katabasis
```

### 3. Package the macOS app image with jpackage

From the repository root:

```bash
jpackage \
  --type app-image \
  --name Katabasis \
  --dest "$(pwd)/dist" \
  --runtime-image "$(pwd)/katabasis-ui/target/katabasis" \
  --module jsanca.katabasis.ui/jsanca.katabasis.ui.app.KatabasisLauncher
```

This generates the application bundle at:

```text
dist/Katabasis.app
```

You can open it with:

```bash
open dist/Katabasis.app
```

### Notes

- If `dist/Katabasis.app` already exists, remove it before packaging again:

```bash
rm -rf dist/Katabasis.app
```

- The `jlink` image is intended for local testing and as the runtime input for `jpackage`.
- A custom application icon can be added later to improve the macOS bundle appearance.

