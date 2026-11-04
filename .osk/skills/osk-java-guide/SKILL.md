# osk-java-guide — Java Engineering Guide

## Mission

Guide implementation toward clear, typed, immutable-where-practical, testable
modern Java without expanding an authorized task into an architecture redesign.

## Scope

### Covers

- Everyday Java implementation and focused code review: construction,
  invariants, value types, collections, exceptions, resources, control flow,
  and proportional abstractions.

### Does not cover

- Framework/Jakarta/Quarkus/Spring policy, architecture, persistence,
  concurrency tuning, migration, security, testing strategy, or delivery.

## Responsibilities

- Prefer explicit required collaborators and immutable state where practical.
- Use domain types, records, sealed types, switch expressions, standard-library
  facilities, and collection factories only when they make the current code
  clearer and safer.
- Keep abstractions direct and proportionate; make a pattern earn its cost.

## Boundaries / Constraints

Authority is: task → architecture/ADRs → project instructions → OSK discipline
→ this guide. Project/framework conventions win. Do not add speculative layers,
refactor unrelated code, or introduce a language feature merely because it is
available. Read the configured Java version before version-sensitive choices.

## Required Inputs

The authorized slice, relevant project conventions, configured Java version,
and affected public/behavioral contracts.

## Expected Outputs

An implementation or review recommendation with explicit invariants, bounded
complexity, and proportionate validation evidence.

## Workflow

1. Ground the slice and version in project evidence.
2. Prefer the simplest explicit construction and types that preserve its
   contracts.
3. Consult [Java engineering reference](references/java-engineering.md) only
   for the decision at hand.
4. Verify the smallest relevant behavior; report any unresolved contract or
   version uncertainty.

## Questions to Ask

- Is this a project/framework convention rather than a core Java decision?
- Does a type or abstraction prevent a present ambiguity, or only imagine one?
- Is the chosen modern feature supported by the configured Java version?

## Escalation Rules

- Missing or conflicting architecture/API/framework convention → escalate; do
  not substitute this guide's preference.
- Version-sensitive choice with no known configured version → use the existing
  idiom or state the uncertainty.

## Quality Checklist

- [ ] Required collaborators and invariants are explicit.
- [ ] Types and collections make ownership/absence/mutation legible.
- [ ] Added abstraction or feature improves this slice's clarity.
- [ ] No framework or architecture policy was inferred from Java guidance.

## Evidence and Validation

Use source, project configuration, and focused build/test evidence appropriate
to the change. A stylistic preference is not a correctness claim.

## Anti-Patterns

- **Ceremonial layer** — an interface, factory, builder, DTO, or delegate exists
  without a current capability/contract boundary.
- **Hidden mutable dependency** — required collaboration is set after
  construction without a project-specific reason.
- **Feature tourism** — a modern construct reduces familiarity rather than
  clarifying the present code.

## Supporting Resources

- [Java engineering reference](references/java-engineering.md) — detailed,
  framework-neutral decision guidance and attribution.
