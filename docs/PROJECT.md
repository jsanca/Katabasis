# Project Context

## Mission

Katabasis is a multiprotocol file download manager for Java developers and power users. It provides a reliable, extensible engine for downloading files over HTTP/HTTPS, FTP/FTPS, S3, and (planned) BitTorrent — with parallel segmented transfers, pause/resume, cancellation, retry, and progress events — exposed as a reusable Java API, a JavaFX desktop application, and a command-line interface.

## Scope

### In scope

- Multiprotocol download engine (`katabasis-core`): HTTP/HTTPS, FTP/FTPS, S3, BitTorrent (feasibility gate)
- Parallel segmented downloads where protocol and server allow
- Pause, resume, and cancellation of active transfers
- Retry policies for transient failures
- Event-driven progress and lifecycle notification
- JavaFX desktop GUI (`katabasis-ui`)
- CLI (`katabasis-cli`)
- Reusable Java API (JPMS module with public API boundary)

### Out of scope

- Anabasis (file upload counterpart) — explicitly excluded
- Upload functionality of any kind
- Authentication or credential management beyond what individual protocol strategies require

## Current State

**Lifecycle:** Active development, pre-release. Baseline `f999777` (K-0.2-T10-HARDEN-001).

- Core HTTP/HTTPS engine (single-stream and segmented parallel) includes functional retry, HTTP timeouts, ETag/range integrity checks, and cooperative pause/resume/cancel.
- Checkpointing is wired: versioned JSON manifests, 5-second coordinator flush, graceful-shutdown checkpoints.
- JavaFX UI is functional with pause/resume/cancel, clipboard paste, drag-and-drop, directory selection, and checkpoint wiring.
- CLI is functional: `download`, `download-list`, structured errors, exit codes, interactive lifecycle, SIGINT.
- Persistent recovery/resume after restart is **not implemented** (K-0.2-T11 pending). S3, FTP/FTPS, and checksum validation are not implemented.
- Full reactor at the baseline: 364 passing tests, zero failures.

See [ROADMAP.md](roadmap/ROADMAP.md) for planned releases and [docs/engineering/ENGINEERING_LOG.md](engineering/ENGINEERING_LOG.md) for work history.

## Architecture

Current architecture: [docs/knowledge/architecture/katabasis-core.md](knowledge/architecture/katabasis-core.md)

Multi-module Maven project. Four JPMS modules: `katabasis-core`, `katabasis-storage-json`, `katabasis-cli`, `katabasis-ui`. Core exports only `api.*` packages; `internal.*` is module-private. `katabasis-storage-json` implements the core persistence ports; CLI and UI compose core plus storage. Virtual threads (Project Loom) for concurrency.

## Technology

| Concern | Detail |
| --- | --- |
| Language | Java 27 (required) |
| Build | Maven 3.14+ |
| Test | JUnit 5.12; no Mockito; no AssertJ |
| Core runtime deps | `commons-lang3`, `slf4j-api`, `slf4j-simple` |
| Storage deps | Jakarta JSON-B / JSON API |
| CLI deps | picocli 4.7.6 |
| UI deps | JavaFX 21, AtlantaFX 2.0.1, Ikonli 12.3.1 |
| Compile | `mvn compile` |
| Test | `mvn test` |
| Full verify | `mvn compile && mvn test-compile && mvn test` |
| Package (macOS) | `jlink` + `jpackage` (see CLAUDE.md) |

## Repository Map

```
katabasis-core/     Core engine — download lifecycle, events, strategies, retry
katabasis-storage-json/ JSON checkpoint adapter and coordinator
katabasis-cli/      picocli CLI (download, download-list)
katabasis-ui/       JavaFX desktop GUI
docs/
  knowledge/        Durable current understanding (architecture, domain)
  engineering/      Task and work evidence (ENGINEERING_LOG.md, reports, reviews)
  roadmap/          Committed direction and future ideas
  adr/              Architecture Decision Records
.osk/               OSK roles and skills installation
AGENTS-CORE.md      Canonical shared agent guidance (build, architecture, conventions)
AGENTS.md           Codex adapter — references AGENTS-CORE.md
CLAUDE.md           Claude Code adapter — imports AGENTS-CORE.md
```

## Getting Started

Prerequisites: Java 27 SDK, Maven 3.14+.

```bash
# Compile all modules
mvn compile

# Run all tests
mvn test

# Single test class
mvn test -Dtest=HttpRangeSupportTest

# Full verification
mvn compile && mvn test-compile && mvn test
```

## Important References

- [Workspace operating guide](OSK.md)
- [Architecture knowledge](knowledge/architecture/katabasis-core.md)
- [Roadmap](roadmap/ROADMAP.md)
- [Engineering log](engineering/ENGINEERING_LOG.md)
- [ADR candidates](adr/README.md)
- [Build and coding conventions](../CLAUDE.md)
