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

The system is intentionally structured to evolve into more advanced capabilities such as:

- cancellation and pause/resume
- segmented (parallel) downloads
- pluggable protocols (HTTP, FTP, S3, etc.)
- persistence and recovery

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

Current implementation:
- `HttpDownloadStrategy` (using JDK `HttpClient`)

Future:
- FTP
- S3
- custom protocols

---

### `DownloadStrategyResolver`
Decides *which* strategy to use based on the request (e.g., URI scheme).

---

### `DownloadTask`
Represents a live download in the system.

Tracks:
- status
- progress
- timestamps
- cancellation state

---

### `DownloadRegistry`
In-memory registry of active downloads.

Enables:
- lookup by ID
- control operations (cancel, future pause/resume)
- snapshot queries

---

### `DownloadEvent`
Event-driven communication layer.

Examples:
- `DownloadStartedEvent`
- `DownloadProgressEvent`
- `DownloadCompletedEvent`
- `DownloadFailedEvent`

---

## ⚙️ Concurrency Model

Katabasis leverages **virtual threads** to simplify concurrency:

- blocking I/O becomes cheap
- no need for complex async pipelines
- cooperative cancellation via `CancellationToken`

---

## 🚀 Current Features

- HTTP downloads using JDK `HttpClient`
- progress tracking
- event emission
- configurable concurrency
- modular architecture (strategy + resolver)
- thread-safe execution model

---

## 🔜 Planned Features

- cancel download (in progress)
- pause/resume (with HTTP range support)
- segmented downloads (parallel chunks)
- checksum validation
- CLI interface
- persistent registry (filesystem / database)

---

## 🧪 Example

```java
DownloadManager manager = DownloadManagers.createDefault();

manager.addListener(System.out::println);

manager.submit(new DownloadRequest(
    URI.create("https://example.com/file.pdf"),
    Path.of("./file.pdf")
));