# ADR 0004: Version the three-input composition execution package independently

## Status

Accepted for the deterministic Layout Resolver and Slide Composer slice.

## Context

The five existing v1 schemas and `contractVersion = 1.0.0` were independently
reviewed as the Contract Gate and Component Resolver boundary. Layout,
composition, and plan validation need new response fields, diagnostic sources,
and diagnostic codes. Reinterpreting an existing v1 field or silently widening
an existing v1 response would make strict consumers unable to identify the
change.

## Decision

The locked Specification and confirmed Profile remain input contract `1.0.0`.
`POST /internal/v1/preflight` keeps its existing request, response, status, and
diagnostic behavior. The five reviewed schemas are retained as compatibility
fixtures and are not rewritten to describe composition output.

`POST /internal/v1/compose-plan` does not reuse the frozen two-input preflight
DTO. Compose contract `2.0.0` requires a `GenerationJob`, LOCKED Specification,
CONFIRMED Profile, and APPROVED Asset Manifest. The Job binding checksum fixes
all three input IDs/versions/checksums plus compose/plan, Engine build, Executor
adapter, font environment, requester, request time, and idempotency values. It
excludes only `executionAttemptId`, so a retry may change the attempt while the
Job fingerprint stays fixed. A changed Manifest or execution-version binding
requires a new Job; this service enforces and propagates the contract but does
not persist Job history.

After strict Schema, Job binding, and Manifest validation, downstream stages
receive an immutable `ValidatedExecutionPackage`. Successful or partial
composition output carries independent versions:

- `planContractVersion = 2.0.0` identifies the deterministic Composition Plan.
- `feedbackContractVersion = 2.0.0` identifies four Job statuses and
  impact-classified diagnostics.

The Plan contract has no timestamp. Its `planChecksum` is lowercase UTF-8
SHA-256 over recursively key-sorted canonical JSON with array order retained and
the root `planChecksum` field excluded. It includes the exact Job/Attempt,
binding checksum, execution versions, three input references, component
selection basis, TextFit boundary, and native object references. Input, plan,
and feedback versions are explicit and cannot be inferred from an endpoint
path.

All object-preserve and component-use/clone operations use nested
`StableNativeObjectReference(referenceVersion, templateId, templateVersion,
scope, sourceSlide, objectId, objectType)`. Profile-known component types are
preserved. A base-page object with no type fact is `UNKNOWN`; fields not present
in Profile v1 (for example package hash, part URI, relationship, or object path)
are not invented. Their future addition requires a new Template Source/Profile
and reference contract version.

Specification v1 `assetId` is interpreted only inside compose v2 as
`assetRequirementId`. The approved Manifest supplies the distinct
`approvedAssetId`, type, and content hash. Optional approved omission produces
no fill; required omission is rejected. This interpretation does not mutate the
reviewed Specification v1 Schema.

The Java diagnostic enums are an additive superset used by both APIs. Legacy
preflight code continues to emit only the original v1 sources and codes. The
new composition schemas enumerate the complete superset and composition code
uses these additional sources:

- `ASSET_MANIFEST_GATE`
- `TEMPLATE_PAGE_RESOLVER`
- `LAYOUT_RESOLVER`
- `SLIDE_COMPOSER`
- `PLAN_VALIDATOR`

The composition superset also includes Generation Job/Manifest Gate codes plus:

- `TEMPLATE_PAGE_MISSING`
- `LAYOUT_OUT_OF_BOUNDS`
- `SAFE_AREA_VIOLATION`
- `SEMANTIC_REGION_CAPACITY_EXCEEDED`
- `COMPOSITION_REFERENCE_INVALID`
- `PLAN_CHECKSUM_MISMATCH`
- `ASSET_APPROVED_OMISSION`
- `COMPONENT_SELECTION_COMPATIBILITY_FALLBACK`
- `CONTENT_OVERFLOW`
- `TEXT_FIT_POLICY_UNAVAILABLE`
- `RASTER_FALLBACK_FORBIDDEN`

Compose Feedback uses these statuses and diagnostic impacts:

- `FAILED`: at least one `JOB_BLOCKING` diagnostic; HTTP 422 and no Plan;
- `PARTIAL`: no blocker but at least one `ARTIFACT_INCOMPLETE` diagnostic;
  HTTP 200 is allowed only when every N input page remains in exact
  `slideId`/`pageNumber`/Specification-order correspondence;
- `SUCCEEDED_WITH_FEEDBACK`: only `NON_BLOCKING` diagnostics;
- `SUCCEEDED`: no diagnostics.

Profile v1 has no `resolverPriority`. Its compatibility adapter therefore uses
the frozen total order: exact component semantic role, complete constraint/
slot/transform/type compatibility, minimum sufficient capacity, confidence
descending, `componentId`, then `slotId`. Every selection records
`selectionBasis` and emits a non-blocking warning. A future Profile version must
provide explicit ascending `resolverPriority`.

TextFitPolicy is a versioned Profile-owned constraint, not a typography engine.
The default `NO_ADJUSTMENT_PROFILE_V1` preserves the previous boundary. A
constrained policy may describe bounded font size, line spacing, text-box
growth, paragraph spacing, and deterministic order, but real measurement is
not implemented in this slice. Missing measurement or a mismatched font
environment therefore fails closed as `TEXT_FIT_POLICY_UNAVAILABLE`; no fake
measurement, rewrite, truncation, split, merge, or PowerPoint AutoFit is
allowed. The pure Planner only returns typography parameter changes. Known
Profile capacity overflow returns `CONTENT_OVERFLOW`. The operation vocabulary
has no raster, screenshot, PNG/SVG substitution, or fallback path.

The post-composer Plan Build Validator has one frozen order: TextFitPolicy and
font-environment binding first, then the existing independent composition-plan
validator. A simultaneous failure preserves that diagnostic order. `UNBOUND`
is a legacy-only sentinel for `NO_ADJUSTMENT_PROFILE_V1`; constrained policies
must bind a non-`UNBOUND` font environment matching the Job.

No Layout, Composer, or Validator failure is translated to
`COMPONENT_RESOLVER` or collapsed into `CONTRACT_INVALID`. Old v1 examples and
tests remain active; new examples and tests validate the independent contracts
and prove preflight compatibility. Compose non-2xx responses use a dedicated
v2 error envelope without a Plan; preflight remains on its original v1 error
envelope.

## Consequences

Strict existing consumers can continue using preflight v1 without accepting new
input fields, output fields, status values, or diagnostics. Composition
consumers must opt into the three-input v2 endpoint and inspect status plus
diagnostic impact. A future breaking Plan, Feedback, Manifest, Profile, or
native-reference change requires an explicit version; it cannot be hidden
behind the current endpoint.

This decision only versions a deterministic object-operation plan. It does not
select a PowerPoint library, read a PPTX, or authorize a PowerPoint Executor.
