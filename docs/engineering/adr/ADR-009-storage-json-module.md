# ADR-009 — Introduce katabasis-storage-json as a Standalone Module

## Status

Accepted — 2026-11-03

## Context

K-0.1-T03B requires a version-aware JSON codec independent from HTTP download logic, JavaFX, and CLI concerns. The codec needs Jakarta JSON-B/JSON-P dependencies that the core HTTP download engine has no use for. Two structural options exist:

1. Add codec classes inside `katabasis-core` under a new `internal.json` package.
2. Create a new Maven/JPMS module `katabasis-storage-json` with its own dependency set.

## Decision

Create `katabasis-storage-json` as a standalone module.

**Module name:** `jsanca.katabasis.storage.json`  
**Exported API:** `jsanca.katabasis.storage.json.api` (`StorageKey`, `VersionedCodec`, `StorageDocument`, `CodecException`)  
**Internal packages:** `jsanca.katabasis.storage.json.internal.{codec,migration,adapter}` — not exported

The module depends on `jakarta.json.bind`, `jakarta.json` (compile), and `org.eclipse.yasson`, `org.eclipse.parsson` (runtime only). It does **not** depend on `katabasis-core`.

## Rationale

- **Dependency boundary.** `katabasis-core` has three runtime deps (`commons-lang3`, `slf4j-api/simple`). JSON-B/JSON-P do not belong there. A separate module keeps the HTTP download engine free of serialization dependencies.
- **Independent testability.** The codec can be tested without starting a download session. 33 tests cover it in isolation.
- **Future consumer independence.** `katabasis-cli`, a future server module, or an embedded library can depend on `katabasis-storage-json` without pulling in download internals.
- **JPMS boundary enforcement.** The module system enforces that `internal.*` packages are not accessible to callers. No runtime guard needed.

The module is **not** purely organizational: it has a different dependency set, independent lifecycle, and independently testable behavior. All three criteria (different deps, independent testability, real value to downstream consumers) are satisfied.

## Rejected Alternative: JSON codec inside katabasis-core

Adding a `jsanca.katabasis.core.internal.json` package to `katabasis-core` was considered. This was rejected because:
- It couples the HTTP download engine's dependency set to JSON-B/JSON-P.
- The JSON-B compatibility spike (K-0.1-T03A) already placed a test-only codec in `katabasis-core`; production code is heavier.
- Future modules wanting only serialization would have to depend on the entire core.

## Rejected Alternative: Split API module (katabasis-storage-api + katabasis-storage-json)

A minimal `katabasis-storage-api` module exporting only `StorageKey`, `VersionedCodec`, `StorageDocument`, and `CodecException` was considered. This was rejected because:
- There is currently no second implementation of `VersionedCodec` to justify the split.
- The API types are few, and the module-info already enforces the boundary.
- YAGNI — a future implementation (e.g. Protocol Buffers codec) can motivate the API split at that time.

## JSON Schema Validation — Deferral Decision

The task requires validating documents against their declared schema. The envelope structure is validated programmatically in `JsonEnvelopeCodec.validateEnvelopeStructure` using JSON-P's object model (required fields, types, ranges). A formal JSON Schema validator (e.g. `networknt/json-schema-validator`) was evaluated but not adopted because:

- The library uses an automatic JPMS module name (`json.schema.validator`), which is fragile in strict module-path setups.
- The envelope schema is simple and stable; programmatic validation is proportionate.
- Payload schema validation (per entity type and version) is deferred to a future task when real entity types are defined.

The JSON Schema resource `META-INF/schemas/envelope-v1.schema.json` documents the envelope contract (Draft 2020-12). It is authoritative for future integrations but is not evaluated at runtime in this implementation.

## Consequences

- `katabasis-storage-json` is now a sibling module under the parent POM.
- `katabasis-core` gains no new runtime dependencies.
- Callers of the codec use only `api.*` types; no `jakarta.json.*` types leak into caller code.
- `DownloadStateStore` (in `katabasis-core`) will depend on `katabasis-storage-json` when persistence is implemented.
