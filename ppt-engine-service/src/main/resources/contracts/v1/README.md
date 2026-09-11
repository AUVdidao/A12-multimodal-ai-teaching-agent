# PPT Engine contracts

This directory contains nine JSON Schemas. They are the cross-service contract
source of truth; unknown fields are rejected and Java records must preserve the
same shape and enum sets.

## Version split

Five original Schemas remain frozen at contract `1.0.0`:

- `locked-ppt-specification.schema.json`
- `confirmed-template-profile.schema.json`
- `generation-feedback.schema.json`
- `engine-preflight-request.schema.json`
- `engine-preflight-response.schema.json`
- `engine-preflight-error-response.schema.json`: strict raw `GenerationFeedback`
  envelope for rejected preflight requests and request-size errors; it is v1
  only and requires `outcome=REJECTED`.

Composition adds four separately versioned Schemas:

- `approved-asset-manifest.schema.json`: approved Manifest `1.0.0`
- `engine-compose-plan-request.schema.json`: three-input request `2.0.0`
- `engine-compose-plan-response.schema.json`: plan and feedback `2.0.0`
- `engine-compose-plan-error-response.schema.json`: compose non-2xx error envelope `2.0.0`, without a Plan
- `composition-feedback.schema.json`: composition feedback `2.0.0`

The old five Schemas and their original diagnostic enums are not expanded.
Composition-only resolver/layout/composer/validator diagnostics live in the new
feedback contract. The compatibility rationale is recorded in
`docs/adr/0004-feedback-and-plan-contract-versioning.md`.

## Resolver and layout rules

- One slide owns one shared occupancy counter per stable `slotId`.
- `contentBlocks` are resolved in Specification order, followed by
  `assetRequirements` in Specification order.
- Preflight v1 keeps its reviewed traversal and allocation behavior. Compose v2
  uses an explicit Profile-v1 compatibility total order: exact component role,
  compatible slot/type/transform, minimum sufficient capacity, confidence
  descending, `componentId`, then `slotId`. Every successful binding records
  `selectionBasis` and emits a stable warning because Profile v1 has no
  `resolverPriority`.
- `maxItems` caps combined bindings and `maxCharacters` is checked per text
  block. Content is never truncated, rewritten, combined, or split.
- Every selected component's `required=true` slot must receive a binding.
- v1 supports only `IMAGE -> IMAGE`, `CHART -> CHART`, and `TABLE -> TABLE`.
- Layout copies confirmed slot bounds exactly as integer EMU. Ordinary regions
  must stay in the safe area; `FULL_BLEED` may reach but not cross the page.
- Matching semantic regions are consumed in Specification array order and obey
  their individual `maxItems`.

## Composition Plan rules

Each slide records the exact selected template page, resolved component and slot
placements, and this operation sequence:

1. `PRESERVE_BASE_OBJECT` in template-page `objectIds` order.
2. `USE_OR_CLONE_COMPONENT_OBJECT` by `componentId`, retaining `shapeRefs` order.
3. `FILL_TEXT_SLOT` in `contentBlocks` order.
4. `FILL_ASSET_SLOT` in `assetRequirements` order.

Native-object operations expose only nested, versioned
`StableNativeObjectReference` values. Base-page objects use `UNKNOWN` when the
Profile does not know their type. Text operations expose only `blockId` and
`contentSha256`; asset operations distinguish `assetRequirementId` from
`approvedAssetId` and include Manifest-owned type/hash. Approved optional
omission emits feedback but no fill operation. They never expose teacher text,
source references, local paths, credentials, or provider output.

The Plan also records `GenerationJobReference`, the exact Manifest reference,
and a `TextFitBoundary`. A Confirmed Template Profile may additionally carry a
versioned `TextFitPolicy`. Current real font measurement is explicitly
`NOT_IMPLEMENTED`; rewrite, truncation, slide split/merge, and PowerPoint AutoFit
are all false. No raster/screenshot/fallback operation exists.

Canonical plan JSON sorts object keys recursively, preserves array order, and
excludes only root `planChecksum` before UTF-8 SHA-256. Generation Job/Attempt,
Job binding checksum, execution versions, three input references, selections,
TextFit policy/boundary, and native references all participate. The same request must
produce byte-identical plan JSON and checksum.

## Stable composition result and diagnostics

Composition Feedback 2.0.0 leaves old Generation Feedback 1.0.0 unchanged and
uses four Job statuses: `FAILED`, `PARTIAL`, `SUCCEEDED_WITH_FEEDBACK`, and
`SUCCEEDED`. Every compose diagnostic includes one impact:

- `JOB_BLOCKING`: stop, HTTP 422, no successful Plan;
- `ARTIFACT_INCOMPLETE`: preserve safe work, HTTP 200 with `PARTIAL` Plan only
  when all input pages remain in exact N/N ID/page/order correspondence;
  missing Template Page or unusable Layout is `JOB_BLOCKING`, not an omission;
- `NON_BLOCKING`: successful Plan with traceable warning.

Composition-only sources include `ASSET_MANIFEST_GATE`,
`TEMPLATE_PAGE_RESOLVER`, `LAYOUT_RESOLVER`, `SLIDE_COMPOSER`, and
`PLAN_VALIDATOR`. New stable codes include the Generation Job and Manifest Gate
families plus `ASSET_APPROVED_OMISSION`,
`COMPONENT_SELECTION_COMPATIBILITY_FALLBACK`, `CONTENT_OVERFLOW`,
`TEXT_FIT_POLICY_UNAVAILABLE`, and `RASTER_FALLBACK_FORBIDDEN`.

The original planning sources/codes remain available without mutating the old
preflight Schema:

- sources: `TEMPLATE_PAGE_RESOLVER`, `LAYOUT_RESOLVER`, `SLIDE_COMPOSER`,
  `PLAN_VALIDATOR`
- codes: `TEMPLATE_PAGE_MISSING`, `LAYOUT_OUT_OF_BOUNDS`,
  `SAFE_AREA_VIOLATION`, `SEMANTIC_REGION_CAPACITY_EXCEEDED`,
  `COMPOSITION_REFERENCE_INVALID`, `PLAN_CHECKSUM_MISMATCH`

Existing resolver codes such as `SLOT_CAPACITY_EXCEEDED`,
`REQUIRED_SLOT_UNFILLED`, and `ASSET_TYPE_UNSUPPORTED` remain available in the
composition feedback union.

## Request boundary and examples

Both `/internal/v1/preflight` and `/internal/v1/compose-plan` share the exact
2,000,000-byte limit. Known-length and chunked overflow return 413; exact-boundary
input continues to normal parsing. Preflight errors retain v1. Compose
malformed/Schema-invalid input returns a v2 error envelope at 400, size overflow
returns the same envelope at 413, Job-blocking rejection returns it at 422, and
partial or successful plans return 200.

- `examples/valid/preflight-request.json`: valid preflight request.
- `examples/invalid/unknown-field.json`: strict unknown-field rejection.
- `examples/invalid/checksum-mismatch.json`: semantic checksum rejection.
- `examples/feedback/resolver-rejected.json`: resolver rejection feedback.
- `examples/compose/success-request.json`: deterministic compose request.
- `examples/compose/success-response.json`: fixed-vector successful plan.
- `examples/compose/error-response.json`: v2 failed response without a Plan.
- `examples/compose/*-feedback.json`: safe FAILED or PARTIAL template, layout,
  and unsupported-asset feedback examples.
