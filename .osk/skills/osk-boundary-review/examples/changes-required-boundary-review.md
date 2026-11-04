# Changes Required Boundary Review Example

## Executive Summary

The domain package imports a database driver type and returns it through a domain-facing method. This crosses a documented domain-to-infrastructure prohibition and leaks persistence behavior into the domain boundary.

## Violations

| Category | Evidence | Impact | Recommendation |
| --- | --- | --- | --- |
| Framework Leakage | Domain type imports database driver transaction type. | Domain tests and callers now require infrastructure knowledge. | Define a domain/application-owned port or move transaction orchestration to the approved boundary. |
| Dependency Violation | Domain package directly imports persistence adapter package. | Direction bypasses the project boundary matrix. | Reverse dependency through the approved abstraction. |

## Overall Assessment

Changes required. Re-run the boundary review after the documented boundary is restored.
