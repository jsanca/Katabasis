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

**Lifecycle:** Active development, pre-release.

- Core HTTP/HTTPS engine (single-stream and segmented parallel) is production-ready.
- JavaFX UI is functional with pause/resume/cancel, clipboard paste, drag-and-drop, directory selection.
- Retry, S3, FTP, CLI, and persistence are not implemented.
- Roadmap covering 0.1–0.4 has been defined and is awaiting approval.

See [ROADMAP.md](roadmap/ROADMAP.md) for planned releases and [docs/engineering/ENGINEERING_LOG.md](engineering/ENGINEERING_LOG.md) for work history.

## Architecture

Current architecture: [docs/knowledge/architecture/katabasis-core.md](knowledge/architecture/katabasis-core.md)

Multi-module Maven project. Three JPMS modules: `katabasis-core`, `katabasis-ui`, `katabasis-cli`. Core exports only `api.*` packages; `internal.*` is module-private. Interface + Factory pattern throughout. Virtual threads (Project Loom) for concurrency.

## Technology

| Concern | Detail |
| --- | --- |
| Language | Java 25 (required) |
| Build | Maven 3.14+ |
| Test | JUnit 5.12; no Mockito; no AssertJ |
| Core runtime deps | `commons-lang3`, `slf4j-api`, `slf4j-simple` |
| UI deps | JavaFX 21, AtlantaFX 2.0.1, Ikonli 12.3.1 |
| Compile | `mvn compile` |
| Test | `mvn test` |
| Full verify | `mvn compile && mvn test-compile && mvn test` |
| Package (macOS) | `jlink` + `jpackage` (see CLAUDE.md) |

## Repository Map

```
katabasis-core/     Core engine — download lifecycle, events, strategies
katabasis-ui/       JavaFX desktop GUI
katabasis-cli/      CLI stub (not yet wired to core)
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

Prerequisites: Java 25 SDK, Maven 3.14+.

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
