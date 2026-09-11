# ADR 0001: Keep the new Engine independent

## Decision

The first PPT Engine implementation is a top-level `ppt-engine-service` using
Java 17 and Spring Boot 3.3.x. It exposes only internal health and preflight
endpoints and does not depend on the Spring Boot backend, frontend, database,
credential service, AI provider, old Harness, old Runner, or old `pptskill`
code.

## Rationale

The Engine must be testable without business-state side effects and must not
inherit the old PPT production model. The upstream system will later provide
teacher-confirmed, versioned contracts and own persistence. This service remains
stateless until the later integration phase defines Engine Job persistence.

## Consequences

- The engine may produce a native-object PPTX only through the bounded,
  same-package executor after all frozen inputs and the plan have been
  validated. This does not imply PowerPoint application compatibility.
- No real Provider or Credential is required to test deterministic behavior.
- Root Compose and the main system remain unchanged.
- Future integration must pass immutable contract objects across an explicit boundary.
