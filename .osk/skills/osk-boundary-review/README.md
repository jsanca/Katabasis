# Architecture Boundary Review

This canonical OSK package reviews architectural placement: whether an artifact, dependency, or responsibility belongs within its intended boundary.

It complements osk-architecture-review:

- osk-boundary-review asks: Is this artifact in the correct architectural boundary?
- osk-architecture-review asks: Is the overall design correct, proportional, and maintainable?

Use this skill for module placement, dependency-direction, framework-leakage, DTO/entity leakage, adapter/application/domain separation, and documented-boundary compliance. It is not a style, formatting, naming, performance, optimization, or general code review.

The package includes a reusable boundary-matrix template and two concise review examples. It requires osk-execution-timebox for bounded review execution and osk-engineering-reporting for durable evidence and handoff.
