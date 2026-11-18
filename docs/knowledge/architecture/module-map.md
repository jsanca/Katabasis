# Module Map

Current Maven modules, JPMS names, and compile-time dependency direction.

**Last verified:** 2026-11-18  
**Baseline:** `f999777`  
**Evidence:** root `pom.xml`, each module's `module-info.java` and `pom.xml`.

```mermaid
flowchart TB
    CLI["katabasis-cli<br/>jsanca.katabasis.cli<br/>picocli commands"]
    UI["katabasis-ui<br/>jsanca.katabasis.ui<br/>JavaFX application"]
    STORAGE["katabasis-storage-json<br/>jsanca.katabasis.storage.json<br/>checkpoint adapter + coordinator"]
    CORE["katabasis-core<br/>jsanca.katabasis.core<br/>download engine"]

    CLI -->|requires| CORE
    CLI -->|requires| STORAGE
    UI -->|requires| CORE
    UI -->|requires| STORAGE
    STORAGE -->|requires| CORE

    classDef core fill:#fff,stroke:#2d3142,color:#2d3142,stroke-width:1.2;
    classDef adapter fill:#ececec,stroke:#4f5d75,color:#2d3142;
    classDef app fill:#f5f5f5,stroke:#4f5d75,color:#2d3142;
    class CORE core;
    class STORAGE adapter;
    class CLI,UI app;
```

## Reading the diagram

- **Core is independent.** `katabasis-core` has no compile-time dependency on storage, CLI, or UI. Storage is an adapter over core ports (`DownloadStateStore`, `CheckpointSource`, `CheckpointSourceProvider`).
- **Both applications compose persistence.** `katabasis-cli` and `katabasis-ui` depend on `katabasis-storage-json` to create a `FileDownloadStateStore` and `CheckpointCoordinator`, and on `katabasis-core` for the engine.
- **Dependency direction is one-way.** `storage-json → core`, `cli → core + storage-json`, `ui → core + storage-json`. There are no cycles and no core-to-storage edge.
- **JPMS boundary.** Core exports only `api.event`, `api.exception`, `api.manager`, `api.model`. Storage exports only `jsanca.katabasis.storage.json.api`. `internal.*` remains module-private in both modules.
- **Java 27.** Core uses preview `StructuredTaskScope`; core-dependent modules enable preview at test runtime.

## Exported packages

| Module | Exports |
| --- | --- |
| `jsanca.katabasis.core` | `api.event`, `api.exception`, `api.manager`, `api.model` |
| `jsanca.katabasis.storage.json` | `api` |
| `jsanca.katabasis.cli` | none (entry point only; `opens` the package to picocli) |
| `jsanca.katabasis.ui` | `jsanca.katabasis.ui.app` |

## Related

- [Architecture: current state](katabasis-core.md)
- [Class model](class-model.md)
- [Checkpointing](checkpointing.md)
- [Editorial version of this diagram](diagrams/module-map.html)
