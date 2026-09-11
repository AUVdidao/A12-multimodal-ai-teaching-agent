# Composition contract boundary

`POST /internal/v1/compose-plan` is the final planning boundary. It consumes one
`GenerationJob` plus exactly three immutable logical inputs:

1. LOCKED PPT Specification v1;
2. CONFIRMED Template Profile v1;
3. APPROVED Asset Manifest v1.

The compose request, Plan, Feedback, and non-2xx error envelope are independently
versioned at `2.0.0`.
The existing two-input `/internal/v1/preflight` DTO, endpoint behavior, five v1
Schemas, examples, and diagnostic enums remain compatibility fixtures.

The Contract Gate verifies the Job fingerprint and every input ID, version, and
checksum before producing an immutable `ValidatedExecutionPackage`. A retry of
the same Job may change only `executionAttemptId`; a Specification, Profile,
Manifest, Engine, Executor-adapter, or font-environment version change requires
a new Job. This service has no Job database and does not implement the upstream
teacher-review or artifact-version lifecycle.

Plan v2 records the exact Job/Attempt, all three input references, Profile-v1
component selection basis, nested Stable Native Object References, approved
asset IDs and hashes, and the versioned TextFit boundary. Text is represented
only by block ID and exact UTF-8 hash. It is never rewritten, truncated, split,
merged, auto-fitted, or echoed. Native objects are never rasterized or silently
replaced by screenshots, PNG, or SVG.

Feedback v2 derives one of four statuses from diagnostic impact:

- `FAILED` from `JOB_BLOCKING`: HTTP 422 and no Plan;
- `PARTIAL` from `ARTIFACT_INCOMPLETE`: HTTP 200 with safe work preserved only
  when all N pages remain in exact ID/page/order correspondence;
- `SUCCEEDED_WITH_FEEDBACK` from non-blocking diagnostics only;
- `SUCCEEDED` when diagnostics are empty.

Executable Schemas and fixtures live under
`src/main/resources/contracts/v1/`. The checked-in success response is generated
from the canonical request fixture and validated byte-for-byte by
`SchemaContractTest`. Compose 400/413/422/500 responses use
`engine-compose-plan-error-response.schema.json` and never include a Plan;
preflight keeps its original v1 error envelope.
