# Architecture Boundary Review

## Mission

Verify that software artifacts remain within their intended architectural boundaries and do not violate documented ownership, dependency-direction, or responsibility rules.

Primary question: Is this artifact located in the correct architectural boundary?

## Scope

### Covers

- Module ownership, dependency direction, abstraction placement, boundary integrity, separation of concerns, cyclic dependencies, framework leakage, and documented architectural rules.
- Domain/application/platform/adapter placement where the project defines those or equivalent boundaries.
- DTO, persistence-entity, framework-type, and business-logic leakage across boundaries.

### Does not cover

- Formatting, naming, style, performance, optimization, test quantity, general code quality, or product prioritization.
- A full assessment of cohesion, coupling, extensibility, SOLID, or overall solution quality; use osk-architecture-review for that broader evaluation.
- Documentation review except where documentation states an architectural boundary that must be checked.

## Responsibilities

- Identify the reviewed artifact, its declared boundary, applicable architecture documents, and allowed/forbidden dependencies.
- Determine whether each dependency and responsibility belongs at the source and target boundary.
- Inspect for boundary crossings, dependency inversion failures, cycles, framework leakage, and ownership displacement.
- Classify concrete findings and recommend the smallest boundary-preserving correction.
- Produce a bounded review record with evidence, risk, and overall assessment.

## Boundaries / Constraints

- Evaluate architectural placement rather than implementation taste or local code style.
- Do not invent a project boundary matrix. Use documented rules; if none exists, identify the missing rule and limit findings to directly observable violations.
- Do not modify production code, tests, configuration, or architecture documents during the review unless the task separately authorizes a correction.
- A boundary rule may be expressed through modules, packages, layers, ports/adapters, dependency diagrams, or equivalent project conventions; do not assume one universal architecture.
- Treat an undocumented but suspicious dependency as a question or observation, not a fabricated policy violation.

## Required Inputs

- Review scope: artifact(s), module(s), pull request, slice, or dependency change.
- Applicable architecture rules, module map, boundary matrix, dependency graph, and source code.
- Existing reports or findings that explain intended ownership, when available.

## Expected Outputs

- A boundary review containing executive summary, boundary compliance, violations, risks, recommendations, overall assessment, and references.
- Findings classified as Boundary Violation, Dependency Violation, Responsibility Leakage, Layer Leakage, Framework Leakage, or Module Ownership Issue.
- A durable report/checkpoint record under osk-engineering-reporting and a recovery checkpoint if required by osk-execution-timebox.

## Workflow

1. Define the review scope and identify the artifact's intended boundary and authoritative architecture evidence.
2. Build or obtain the project-specific boundary matrix; record gaps rather than assuming rules.
3. Map source artifact dependencies, responsibilities, framework types, data types, and outward-facing contracts.
4. Evaluate each crossing against allowed/forbidden dependencies and dependency-inversion expectations.
5. Inspect for cycles, framework/platform leakage, DTO/entity leakage, misplaced business logic, and module ownership displacement.
6. Classify findings, attach concrete evidence, assess risk, and state the smallest reasonable correction.
7. Produce the review record and overall assessment; refer broader design concerns to osk-architecture-review.

## Questions to Ask

- Which module or boundary owns this artifact and its responsibility?
- What architecture document, dependency rule, or project convention authorizes this dependency?
- Is the dependency direction allowed, inverted through a consumer-owned abstraction, or cyclic?
- Does a framework, DTO, persistence entity, or platform concern cross into a boundary where it does not belong?
- Is a suspected violation a documented rule breach, a missing boundary decision, or a broader architecture-quality concern?

## Escalation Rules

- Missing, conflicting, or ambiguous architecture rules → request clarification or record a missing-boundary decision; do not invent a rule.
- Confirmed forbidden dependency, cycle, framework leakage, or business logic outside its owned boundary → report a material finding with evidence and correction.
- Broad cohesion/coupling/extensibility concern outside placement scope → recommend osk-architecture-review.
- Timebox expiration, unclear scope, or repeated inability to establish authoritative boundaries → stop under osk-execution-timebox and preserve a checkpoint.

## Quality Checklist

- [ ] Scope, artifact ownership, and applicable boundary rules are identified.
- [ ] Every reported violation cites source/target, evidence, rule, impact, and recommendation.
- [ ] Allowed dependencies are not reported as violations merely because they cross a package boundary.
- [ ] Framework, DTO, persistence, and business-logic leakage were considered where relevant.
- [ ] Cycles and dependency-inversion expectations were evaluated.
- [ ] Review distinguishes confirmed violations, missing rules, observations, and broader architecture concerns.
- [ ] Report remains about boundaries rather than style or optimization.

## Anti-Patterns

- **Boundary review as code-style review** — report placement and dependency concerns, not naming or formatting preferences.
- **Invented layer rule** — do not label a dependency invalid because it conflicts with an assumed architecture.
- **Package boundary absolutism** — a package crossing is not automatically a violation; evaluate documented ownership and allowed direction.
- **DTO/entity laundering** — do not normalize framework/persistence types crossing into domain boundaries merely because code compiles.
- **Broad design review disguised as boundary finding** — refer cohesion, extensibility, and proportion concerns to osk-architecture-review.

## Dependencies

| Skill ID | Relationship | Required before | Rationale |
| --- | --- | --- | --- |
| osk-engineering-reporting | requires | Recording the review | Supplies evidence, report, and checkpoint conventions. |
| osk-execution-timebox | requires | Bounded review execution | Supplies target, hard-stop, and recovery-checkpoint behavior. |

## Activation Conditions

Apply for an architecture boundary review, module-ownership assessment, dependency-direction check, layer/adapter/domain separation review, or framework/persistence leakage investigation. Do not apply solely for code style, formatting, naming, performance, or general architecture-quality review.

## Review Philosophy

Boundary review validates where an artifact belongs and what it may depend on. It is narrower than Architecture Review, which evaluates whether the overall design is sound and maintainable. It differs from Code Review, which evaluates implementation quality, and Documentation Review, which evaluates documentation integrity. A boundary finding must be grounded in an authoritative rule or a clearly identified missing decision.

## Dependency Review

For each dependency, record source boundary, target boundary, direction, rule, and evidence. Determine whether it is:

- allowed by the matrix or architecture contract;
- forbidden because it crosses an ownership/layer boundary;
- inverted correctly through a consumer-owned abstraction;
- cyclic directly or transitively;
- leaking a framework or platform concern into business boundaries.

Treat framework types crossing domain/application boundaries, infrastructure adapters reaching into domain internals, platform modules depending on business modules, and business logic implemented in transport/persistence layers as review candidates. Confirm each against project-specific rules before classifying it as a violation.

## Boundary Matrix Template

Customize this matrix per project; it is a review aid, not a universal rule set.

| Source | Target | Allowed | Notes |
| --- | --- | --- | --- |
| Domain | Application port | Yes, when the project contract defines the port | Keep framework and infrastructure types out. |
| Application | Domain | Yes | Orchestrate domain capabilities without importing transport/persistence concerns. |
| Adapter | Application service | Usually | Translate external input/output at the boundary. |
| Domain | Framework or persistence adapter | Usually no | Use a port or project-approved boundary instead. |
| Platform | Business module | Project-specific | Document the allowed direction explicitly. |

## Findings Classification

| Category | Meaning |
| --- | --- |
| Boundary Violation | Artifact is located or exposed outside its intended boundary. |
| Dependency Violation | Source depends on a forbidden target or direction. |
| Responsibility Leakage | Responsibility belongs to another boundary. |
| Layer Leakage | A layer's concern crosses into an inappropriate layer. |
| Framework Leakage | Framework/platform type or behavior crosses an intended abstraction boundary. |
| Module Ownership Issue | Module owns behavior or data that another module is responsible for. |

## Output Format

Every review includes:

1. Executive Summary — scope, overall boundary status, and material concerns.
2. Boundary Compliance — matrix/rules evaluated and compliant crossings.
3. Violations — category, source, target, evidence, rule, impact, and recommendation.
4. Risks — maintainability, change isolation, testability, security, or runtime consequences.
5. Recommendations — smallest corrections and ownership/decision follow-ups.
6. Overall Assessment — compliant, changes required, or boundary decision required.
7. References — architecture rules, diagrams, modules, tasks, and related reports.

## Evidence and Validation

Base every confirmed violation on a traceable source/target dependency and an authoritative boundary rule. Validate the report against its required output format and distinguish violations from missing decisions.

## Supporting Resources

- [examples/](examples/) — concise compliant and changes-required review examples; use as illustrations only.
