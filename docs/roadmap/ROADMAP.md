# Katabasis Roadmap

This document records committed project direction for Katabasis 0.1 through 0.4. Uncommitted ideas remain in `future/`. Evidence backing this roadmap is in [docs/engineering/agents/reports/KATABASIS-ROADMAP-001-roadmap-planning.md](../engineering/agents/reports/KATABASIS-ROADMAP-001-roadmap-planning.md).

**Status: Proposed — awaiting human review and approval before Phase 0.1 implementation begins.**

---

## Current Commitments

| Item | Outcome | Status | Target | References |
| --- | --- | --- | --- | --- |
| Katabasis 0.1 — HTTP Reliability | Stable, retry-capable HTTP engine with verified pause/resume and test coverage | Planned | — | [Phase 0.1](#katabasis-01--http-reliability) |
| Katabasis 0.2 — CLI & Persistence | Working CLI and persistent state recovery | Planned | — | [Phase 0.2](#katabasis-02--cli--persistence) |
| Katabasis 0.3 — FTP/FTPS & S3 | Multiprotocol downloads | Planned | — | [Phase 0.3](#katabasis-03--ftpftps--s3) |
| Katabasis 0.4 — BitTorrent | BitTorrent support (Go/No-Go gate required) | Planned | — | [Phase 0.4](#katabasis-04--bittorrent) |

---

## Katabasis 0.1 — HTTP Reliability

**Goal:** Consolidate the existing HTTP engine. All HTTP download paths must be verified, retry must be functional, and the test suite must cover end-to-end orchestration.

### Task Backlog

| Task ID | Description | Depends On | ADR Required |
| --- | --- | --- | --- |
| ~~K-0.1-T01~~ | ~~Verify and test pause/resume in segmented downloads; resolve event-storm risk (see R-001)~~ **DONE** | — | — |
| ~~K-0.1-T02~~ | ~~Write end-to-end tests for `DownloadManager` (submit, complete, fail, cancel, pause/resume lifecycle)~~ **DONE** | — | — |
| ~~K-0.1-T03~~ | ~~Design `RetryPolicy` API: add methods defining retry contract (exception, attempt count, delay)~~ **DONE** (implemented as K-0.1-T03A–D; `RetryConfig`, `RetryPolicy`, `RetryExecutor`, `HttpFailureClassifier`) | — | ADR-001 |
| ~~K-0.1-T04~~ | ~~Integrate retry into `HttpDownloadStrategy`~~ **DONE** (K-0.1-T03D + K-RETRY-HARDEN-001) | K-0.1-T03 | — |
| ~~K-0.1-T05~~ | ~~Integrate retry into `SegmentedHttpDownloadStrategy`~~ **DONE** (K-0.1-T03D + K-RETRY-HARDEN-001) | K-0.1-T03 | — |
| K-0.1-T06 | Add timeout configuration and handling to HTTP strategies | K-0.1-T04 | — |
| K-0.1-T07 | Validate `ETag` / `If-Range` headers in segmented strategy for content integrity | — | — |
| K-0.1-T08 | Resource cleanup tests: verify no file handle or partial file leaks on cancellation/failure | K-0.1-T01 | — |
| K-0.1-T09 | Expand HTTP integration tests with controlled in-process server | K-0.1-T04 | — |
| K-0.1-T10 | Verify URL list (concurrent) downloads and concurrency limit enforcement | — | — |

### Definition of Done — 0.1

- [x] All existing tests pass (no regressions) — 90 katabasis-core + 63 katabasis-storage-json (2026-11-04)
- [x] `DownloadManager` end-to-end tests exist and pass for: single download completes, download fails, download is cancelled, download is paused then resumed — K-0.1-T02
- [x] Retry fires for transient HTTP errors (5xx, connection reset) using `FixedDelayRetryPolicy` and `ExponentialBackoffRetryPolicy` — K-0.1-T03D + K-RETRY-HARDEN-001
- [x] Segmented pause/resume emits exactly one `DownloadPausedEvent` and one `DownloadResumedEvent` per pause/resume signal — K-0.1-T01
- [ ] No partial files or open file handles remain after cancellation or failure — K-0.1-T08
- [ ] ETag/If-Range mismatch is detected and raises `DownloadFailedEvent` with appropriate error code — K-0.1-T07
- [ ] `HttpDownloadOrchestratorTest` and `HttpDownloadSupportTest` coverage is known and not empty — K-0.1-T09

---

## Katabasis 0.2 — CLI & Persistence

**Goal:** Provide a working command-line interface as a standalone consumer of `katabasis-core`, and add persistent state so interrupted downloads can be recovered after process restart.

**Prerequisite:** Katabasis 0.1 DONE.

### Key Decisions Required Before Starting

- ADR-001: RetryPolicy contract (must be resolved in 0.1)
- ADR-002: Persistence strategy (file/JSON vs. SQLite vs. other)
- ADR-003: `DownloadManager.close()` / `AutoCloseable` on public API
- ADR-004: CLI framework selection

### Task Backlog

| Task ID | Description | Depends On | ADR Required |
| --- | --- | --- | --- |
| K-0.2-T01 | Decide CLI framework (picocli or other); document in ADR-004 | — | ADR-004 |
| K-0.2-T02 | Add `requires jsanca.katabasis.core` to `katabasis-cli` module-info and pom.xml dependency | — | — |
| K-0.2-T03 | Add `AutoCloseable` to `DownloadManager` interface; implement graceful shutdown | — | ADR-003 |
| K-0.2-T04 | Implement CLI: download single URL with progress display | K-0.2-T01, K-0.2-T02 | — |
| K-0.2-T05 | Implement CLI: download from URL list file | K-0.2-T04 | — |
| K-0.2-T06 | CLI options: destination folder, concurrency, segment count, retry policy | K-0.2-T04 | — |
| K-0.2-T07 | CLI pause/resume/cancel via signal or interactive command | K-0.2-T04 | — |
| K-0.2-T08 | CLI exit codes: 0 = success, non-zero = partial/all failed; structured error output | K-0.2-T04 | — |
| K-0.2-T09 | Decide persistence strategy; document in ADR-002 | — | ADR-002 |
| K-0.2-T10 | Implement `DownloadStateStore` (file-based, one file per download session) | K-0.2-T09 | — |
| K-0.2-T11 | Recovery: resume an interrupted HTTP download after process restart (requires range support and stored byte offset) | K-0.2-T10 | — |
| K-0.2-T12 | Optional checksum validation after download (SHA-256, MD5; flag-driven) | K-0.2-T04 | — |
| K-0.2-T13 | CLI integration tests (spawn subprocess or use programmatic entry) | K-0.2-T04 | — |
| K-0.2-T14 | Executable packaging: fat jar or native image documentation | K-0.2-T04 | — |

### Definition of Done — 0.2

- [ ] `katabasis-cli` compiles with `requires jsanca.katabasis.core`
- [ ] `katabasis download <url>` works end-to-end with progress output
- [ ] `katabasis download --list urls.txt` downloads all URLs with configurable concurrency
- [ ] Exit code is non-zero when any download fails
- [ ] A download interrupted by SIGTERM is recorded; restarting with the same destination resumes from the last checkpoint (HTTP range required)
- [ ] Checksum validation flag works for SHA-256 at minimum
- [ ] No regressions in core or UI

---

## Katabasis 0.3 — FTP/FTPS & S3

**Goal:** Add FTP/FTPS and S3 protocol support, respecting each protocol's distinct capabilities and constraints. Do not force HTTP semantics onto non-HTTP protocols.

**Prerequisite:** Katabasis 0.1 DONE. 0.2 beneficial (persistence for recovery).

### Key Decisions Required Before Starting

- ADR-005: FTP library/approach
- ADR-006: S3 SDK approach
- ADR-008: `DownloadStrategy` capability interface (`supportsRanges()`, `supportsPause()`)

### FTP/FTPS Task Backlog

| Task ID | Description | Depends On | ADR Required |
| --- | --- | --- | --- |
| K-0.3-T01 | Decide FTP implementation approach; document in ADR-005 | — | ADR-005 |
| K-0.3-T02 | Decide `DownloadStrategy` capability interface; document in ADR-008 | — | ADR-008 |
| K-0.3-T03 | Implement `FtpDownloadStrategy`: single-stream, plain FTP | K-0.3-T01 | — |
| K-0.3-T04 | Add FTP REST command support for resumable downloads | K-0.3-T03 | — |
| K-0.3-T05 | Add FTPS (TLS) support with configurable trust store | K-0.3-T03 | — |
| K-0.3-T06 | FTP server capability detection (REST, SIZE commands) | K-0.3-T03 | — |
| K-0.3-T07 | FTP error handling: timeouts, disconnect, re-login | K-0.3-T03 | — |
| K-0.3-T08 | Register FTP/FTPS in `DefaultDownloadStrategyResolver` | K-0.3-T03 | — |

### S3 Task Backlog

| Task ID | Description | Depends On | ADR Required |
| --- | --- | --- | --- |
| K-0.3-T09 | Decide S3 implementation approach; document in ADR-006 | — | ADR-006 |
| K-0.3-T10 | Implement `S3DownloadStrategy`: single-stream from S3 URI | K-0.3-T09 | — |
| K-0.3-T11 | Implement `S3UriParser`: parse `s3://bucket/key` | K-0.3-T10 | — |
| K-0.3-T12 | S3 authentication: AWS credential chain (env, profile, instance metadata) | K-0.3-T10 | — |
| K-0.3-T13 | S3 byte-range (parallel chunk) downloads | K-0.3-T10 | — |
| K-0.3-T14 | S3 integrity: ETag (MD5) or checksum validation | K-0.3-T10 | — |
| K-0.3-T15 | Register S3 in `DefaultDownloadStrategyResolver` | K-0.3-T10 | — |

### Definition of Done — 0.3

- [ ] `ftp://` and `ftps://` URIs download successfully against a test FTP server
- [ ] FTP REST resume works when server supports SIZE and REST commands
- [ ] `s3://bucket/key` URIs download successfully with standard AWS credential chain
- [ ] S3 range downloads work and produce correct files
- [ ] No existing HTTP tests regress
- [ ] `DownloadStrategy` capability interface adopted; no HTTP-specific assumptions in Manager

---

## Katabasis 0.4 — BitTorrent

**Goal:** Evaluate BitTorrent feasibility and, if the Go/No-Go gate passes, implement peer-to-peer download support within the existing event and lifecycle model.

**Prerequisite:** Katabasis 0.1 DONE. BitTorrent persistence requires 0.2.

### Go/No-Go Gate

**K-0.4-T01** is a mandatory feasibility analysis task. It must produce ADR-007 with a clear Go or No-Go recommendation. No implementation tasks begin until ADR-007 is decided and accepted.

### Feasibility Analysis Scope (K-0.4-T01)

- Library evaluation: bt (Atomashevsky), ttorrent, or similar; vs. implementing the piece protocol from scratch
- Torrent file (`.torrent`) metadata parsing and magnet link resolution
- Piece download, SHA-1 hash verification, and piece assembly
- Peer discovery: tracker announce, DHT (BEP-5), and PEX
- Concurrency model: virtual threads with structured task scope vs. dedicated piece scheduler
- Persistence: piece bitmap for resume after restart
- DownloadManager compatibility: how to map BitTorrent lifecycle events to `DownloadEvent` types
- Effort estimate vs. alternatives (wrapper, delegation to external tool)
- Recommendation: Go / No-Go with rationale

### Implementation Task Backlog (conditional on Go)

| Task ID | Description | Depends On |
| --- | --- | --- |
| K-0.4-T02 | Torrent file and magnet link parsing | ADR-007 Go |
| K-0.4-T03 | Piece download with SHA-1 verification | K-0.4-T02 |
| K-0.4-T04 | Tracker announce and peer list acquisition | K-0.4-T02 |
| K-0.4-T05 | DHT peer discovery (BEP-5) | K-0.4-T04 |
| K-0.4-T06 | Piece selection strategy and concurrent download | K-0.4-T03 |
| K-0.4-T07 | Persistence: piece bitmap; resume after restart | K-0.4-T03 |
| K-0.4-T08 | DownloadManager event mapping for BitTorrent lifecycle | K-0.4-T02 |
| K-0.4-T09 | Integration tests: download a known torrent end-to-end | K-0.4-T06 |

### Definition of Done — 0.4 (if Go)

- [ ] ADR-007 documents Go decision with rationale
- [ ] `.torrent` file and magnet link both initiate downloads
- [ ] Piece SHA-1 verification catches corrupt pieces and re-requests them
- [ ] Download survives process restart and resumes from last piece checkpoint
- [ ] `DownloadEvent` types map correctly to BitTorrent lifecycle (started, progress by bytes, completed, failed, cancelled)
- [ ] No regression in HTTP, FTP, or S3 behavior

### Definition of Done — 0.4 (if No-Go)

- [ ] ADR-007 documents No-Go decision with rationale
- [ ] `docs/roadmap/future/bittorrent.md` records the analysis for future reconsideration
- [ ] No code changes made

---

## Recently Completed

| Item | Outcome | Evidence |
| --- | --- | --- |
| Initial HTTP engine | Single-stream and segmented parallel HTTP downloads with pause/resume/cancel and JavaFX UI | [Assessment report](../engineering/agents/reports/KATABASIS-ROADMAP-001-roadmap-planning.md) |
