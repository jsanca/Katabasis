# Module Map

Current Maven modules, their JPMS names, and the dependency direction between them.

**Last verified:** 2026-11-03
**Evidence:** `katabasis-core/src/main/java/module-info.java`, `katabasis-ui/src/main/java/module-info.java`, `katabasis-cli/src/main/java/module-info.java`, root and per-module `pom.xml`.

```mermaid
flowchart LR
    subgraph CORE["katabasis-core — jsanca.katabasis.core"]
        direction TB
        API["api.event · api.exception<br/>api.manager · api.model<br/>(exported)"]
        INTERNAL["internal.execution · internal.strategy<br/>internal.http · internal.s3 · internal.retry<br/>internal.model · internal.util<br/>(module-private)"]
        INTERNAL -->|depends on| API
    end

    UI["katabasis-ui<br/>jsanca.katabasis.ui<br/>JavaFX desktop app"] -->|requires| API
    CLI["katabasis-cli<br/>jsanca.katabasis.cli<br/>stub"] -.->|"planned, not declared"| API

    classDef exported fill:#fff,stroke:#2d3142,color:#2d3142;
    classDef private fill:#ececec,stroke:#4f5d75,color:#2d3142;
    classDef adapter fill:#f5f5f5,stroke:#4f5d75,color:#2d3142;
    class API exported;
    class INTERNAL private;
    class UI,CLI adapter;
```

## Reading the diagram

- **Dependency direction.** Arrows point from the consumer to the dependency: `katabasis-ui` requires the exposed API of `katabasis-core`, and `internal.*` packages depend on `api.*`.
- **The API boundary is enforced, not documented.** `katabasis-core` exports only `api.event`, `api.exception`, `api.manager`, and `api.model`. `internal.*` packages are module-private, so `katabasis-ui` and `katabasis-cli` cannot reference them.
- **CLI is not wired yet.** `katabasis-cli` does not declare `requires jsanca.katabasis.core`; its `module-info.java` exports nothing and `codex.Main` is a stub. The dashed arrow marks a planned dependency, not a current one.
- **UI is a real consumer.** `katabasis-ui` declares `requires jsanca.katabasis.core` and builds its screen exclusively against the exported API.

## Related

- [Architecture: current state](katabasis-core.md)
- [Class model](class-model.md)
- [Editorial version of this diagram](diagrams/module-map.html)
