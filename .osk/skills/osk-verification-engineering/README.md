# Verification Engineering

This canonical OSK process skill turns expected behavior into reproducible verification evidence. AI may create the artifact, but reproduction must not depend on AI availability.

Canonical flow:

    Use case and business rules
            ↓
    Test cases
            ↓
    Reproducible automation
            ↓
    Execution
            ↓
    Evidence
            ↓
    Verification report

Use cases/business rules, acceptance criteria, and documented test cases define expected behavior. Automation implements that truth; execution and evidence show what happened.

| Status | Meaning |
| --- | --- |
| VERIFIED | Executed successfully with reproducible evidence. |
| VERIFIED WITH OBSERVATIONS | Required behavior passed with non-blocking concerns. |
| PARTIALLY VERIFIED | Only part of requested scope was proven. |
| AUTOMATION READY — NOT EXECUTED | Automation exists; no execution claim is made. |
| BLOCKED | Required prerequisites are unavailable. |
| FAILED | Executed behavior did not match expectations. |

The skill is tool-agnostic: browser frameworks, API clients, HTTP test helpers, database inspection, shell scripts, and project-native runners are implementation choices. It does not authorize creation of infrastructure, production-data access, or silent repair of defects.

Example task header:

~~~
role: verification-engineer

skills:
  - osk-verification-engineering
  - osk-execution-timebox
  - osk-execution-observability
  - osk-engineering-reporting

execution:
  timebox: 45m
  observability: attentive
~~~

osk-execution-observability makes live convergence visible; osk-execution-timebox bounds execution; osk-engineering-reporting preserves the final or checkpointed record.
