# ADR-004 — CLI Framework: picocli

## Status

Accepted

## Context

> **Amendment (K-0.2-ADR-HARDEN-001, 2026-11-17):** `katabasis-cli` is now a fully implemented
> CLI module (K-0.2-T01 through K-0.2-T07). The project migrated to Java 27 (K-JAVA27-001).
> References to Java 25 in this ADR are historical; all JPMS and preview-feature constraints
> continue to apply under Java 27. See current-state notes below.

`katabasis-cli` required a functional command-line interface backed by `katabasis-core`. Two
requirements drove the framework choice:

1. **JPMS compatibility** — Katabasis is a full Java module system project (Java 27 as of
   K-JAVA27-001; originally Java 25). The CLI module uses `module-info.java` with named module
   resolution at runtime; any framework that relies on unnamed-module tricks or classpath
   scanning is disqualifying.
2. **Minimal footprint** — `katabasis-cli` must not pull in a heavyweight DI container or
   reflection engine. The runtime dependency set is `katabasis-core` and picocli only.

Additional requirements identified during K-0.2-PRE-001:
- Subcommand dispatch (`download`, `download-list`; future subcommands in later phases).
- `--help` / `--version` as first-class citizens; exit codes must be testable.
- No annotation processors required at build time (keeps Maven pipeline simple).
- Library must be available in Maven Central.

## Decision

Use **picocli 4.7.6** (`info.picocli:picocli`).

- Ships a named JPMS module (`info.picocli`) with a proper `module-info.class`; compiles and runs
  cleanly under a fully modular build with `--module-path`.
- `@Command` / `@Option` / `@Parameters` annotations are processed at runtime by picocli's own
  reflective engine — the CLI module only needs `opens jsanca.katabasis.cli to info.picocli`.
- `CommandLine.execute(args)` returns an integer exit code, enabling `System.exit()` and
  deterministic unit tests without spawning a subprocess.
- `mixinStandardHelpOptions = true` adds `--help` and `--version` without boilerplate.
- Subcommand registration is declarative (`@Command(subcommands = {...})`); adding future
  subcommands does not require changes to the dispatcher.
- Zero transitive dependencies; single JAR on Maven Central.

The entry point pattern chosen:
- `KatabasisCommand` — `@Command`-annotated class implementing `Callable<Integer>`; root command
  with no-op `call()` returning `0`.
- `KatabasisLauncher` — thin `main(String[] args)` entry point; calls
  `System.exit(new CommandLine(new KatabasisCommand()).execute(args))`.

## Alternatives Considered

**Manual `args[]` parsing**: No dependencies, but subcommand dispatch and `--help` generation must
be hand-written. Rejected — ongoing maintenance cost grows with each subcommand, and the output
quality of hand-written help is lower.

**Apache Commons CLI**: Classpath library, no named JPMS module. Would require `--add-modules` or
unnamed-module workarounds. Rejected on JPMS compatibility grounds.

**JCommander**: JPMS module missing from recent releases; last maintained release predates Java 17.
Rejected.

**Quarkus Picocli / Micronaut**: Full framework; bring in CDI, injection, build-time annotation
processing. Far more than needed for a download manager CLI. Rejected.

## Consequences

**Easier:**
- Help text, version strings, and exit codes work out of the box.
- Adding subcommands requires only `@Command(subcommands = ...)` and a new `Callable<Integer>`
  class — no changes to the root dispatcher.
- `CommandLine.execute()` return value enables exit-code assertions in unit tests without spawning
  a subprocess or installing a `SecurityManager`.

**Harder / limitations:**
- picocli uses reflection to process annotations; `opens jsanca.katabasis.cli to info.picocli`
  must be maintained in `module-info.java` as new command classes are added.
- picocli 4.7.6 is pinned explicitly; upgrades require re-verification against JPMS behavior.

**Current state (as of K-0.2-T07):**

The following subcommands are implemented and tested:

| Subcommand | Task | Status |
|-----------|------|--------|
| `download` | K-0.2-T04 | Done — single URL, progress display, interactive p/r/c/q, SIGINT |
| `download-list` | K-0.2-T05 | Done — URL list file, concurrent batch, SIGINT |
| (config options) | K-0.2-T06 | Done — `--segments`, `--retries`, timeouts, `--max-concurrent` |
| (interactive lifecycle) | K-0.2-T07 | Done — pause/resume/cancel/quit, shutdown hook |

**Java 27 and preview features:** `katabasis-core` uses `StructuredTaskScope` (still a preview
API in Java 27). The `katabasis-cli` module requires `--enable-preview` at test runtime
(Surefire argLine) to load core classes compiled with preview enabled. No preview APIs are used
directly in `katabasis-cli` source code, so compilation does not require `--enable-preview`.
picocli 4.7.6 has been verified to compile and run correctly under Java 27 with these settings.
