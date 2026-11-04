# Java Engineering Reference

Curated primarily from the user-authorized `java-core-engineering` Skill.
Apply only after task, architecture, project instruction, and configured Java
version are known.

## Construction, dependencies, and invariants

Prefer required collaborators as construction dependencies and make them
`final` when practical. This reveals requirements, prevents partially
initialized objects, and makes direct tests simple. A framework's documented
construction/DI convention remains authoritative.

```java
final class UserService {
    private final UserRepository repository;

    UserService(UserRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }
}
```

Avoid mutable field injection or setters for required dependencies unless a
project/framework constraint justifies it.

## Types and value objects

Prefer an enum, existing domain type, or small value object when raw strings,
UUIDs, booleans, or primitives create a real ambiguous/invalid state. Do not
wrap every boundary value mechanically.

Use a `record` for stable immutable data carriers; validate in its compact
constructor when the invariant belongs to the value. Use sealed variants only
when a closed set is a real domain rule. Use switch expressions/pattern matching
only when supported by the configured Java version and clearer than alternatives.

## Absence, collections, and control flow

Use `Optional` chiefly as a return contract where absence is meaningful; avoid
it for fields and parameters unless an existing API requires it. Expose internal
collections with `List.copyOf` or an unmodifiable view when mutation would leak
ownership. Keep stream pipelines short; extract a named method or use a loop
when branching obscures the business rule.

## Exceptions and resources

Catch specific exceptions, preserve causes, and make the recovery/translation
intentional. Do not swallow failures merely to continue. Use try-with-resources
for owned `AutoCloseable` resources; do not invent cleanup wrappers where the
platform contract already suffices.

## Proportional abstractions

Prefer a direct concrete component when it makes ownership and behavior clear.
Introduce an interface, factory, builder, mapping layer, or delegating class
only for a present consumer boundary, external boundary, construction need, or
demonstrable complexity reduction. One implementation is not automatically a
reason for either an interface or its prohibition.

## Version and specialist boundaries

Do not assume Java 21 or 25. Consult a version/migration guide for virtual
threads, preview/incubator APIs, toolchain changes, or version-specific
language/library features. Concurrency, persistence, testing, JVM diagnostics,
security, and Jakarta/framework behavior require their own task-specific
authority or specialized guidance.
