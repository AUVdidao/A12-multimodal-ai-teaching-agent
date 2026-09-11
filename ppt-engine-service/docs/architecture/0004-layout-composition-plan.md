# Layout and Composition Plan architecture

Status: implemented deterministic planning slice and bounded same-package
PPTX execution; PowerPoint application validation remains unverified.

## 1. Scope

This slice turns a `GenerationJob` binding a `LOCKED` Specification, `CONFIRMED`
Template Profile, and `APPROVED` Asset Manifest into a deterministic, immutable
Composition Plan. The plan is consumed by the bounded same-package Executor.
The Executor is an OPC/XML package adapter: it materializes output slides from
confirmed template slide parts, preserves native XML objects, rebinds
same-package relationships, and never claims PowerPoint application
compatibility.

The pipeline is:

```text
Schema / Job / Manifest Contract Gate
  -> immutable Validated Execution Package
  -> Component Resolver
  -> Template Page Resolver
  -> Layout Resolver
  -> Slide Composer
  -> Plan Build Validator (TextFit policy gate + compatibility validator)
  -> Same-Package Executor (slide materialization + native OPC/XML edits)
  -> File Build Validator
```

Every stage is fail-closed. Contract or final-plan defects are `JOB_BLOCKING`
and return no Plan. Local required failures are `ARTIFACT_INCOMPLETE`: the
affected region remains empty while safe pages/regions continue in a `PARTIAL`
Plan. No stage rewrites teacher content, invents coordinates, calls a model, or
repairs another stage's output. Executor edits occur only in an isolated
working copy; the bound template source is never modified.

## 2. Versioned model boundary

The existing input and preflight contracts stay at `1.0.0`. Approved Asset
Manifest is `1.0.0`; compose request, Composition Plan, and Composition Feedback
are independent `2.0.0` contracts. Expanding the old preflight DTO or Generation
Feedback enum would be a silent breaking change for strict consumers.

The principal immutable models are:

| Model | Purpose |
|---|---|
| `GenerationJob` | Separates Job identity from execution attempt and freezes three input plus execution-version bindings. |
| `ValidatedExecutionPackage` | Immutable post-Gate package consumed by every composition stage. |
| `TemplatePageSelection` | Exact confirmed template page and stable selection basis. |
| `ResolvedComponentPlacement` | Confirmed component, stable native object references, allowed transform. |
| `ComponentSelection` | Binding, selected component/slot, and deterministic Profile-v1 selection basis. |
| `SlotPlacement` | Stable binding identity, exact Profile slot bounds, region, and transforms. |
| `ResolvedLayoutPlan` | Page size/safe area plus all component and slot placements for one locked slide. |
| `CompositionOperation` | One preserve, use/clone, text-fill, or asset-fill instruction. |
| `ComposedSlidePlan` | One locked slide's layout and ordered operations. |
| `TextFitPolicy` | Profile-owned versioned typography constraints and deterministic adjustment order. |
| `TextFitBoundary` | Plan-side no-adjustment, no-fake-measurement boundary and prohibited transformations. |
| `ComposedPresentationPlan` | Job/Attempt, three frozen input references, original slide count, ordered safe slides, checksum. |

The Profile lacks its own checksum field, so the plan records a canonical
`profileContentSha256` alongside Profile/template IDs and versions.

### 2.1 Component selection compatibility adapter

Preflight v1 retains its reviewed behavior. Compose v2 collects every legal
candidate and applies a total order: exact component semantic role; complete
slot/content-type/transform/constraint compatibility; minimum sufficient
character/item capacity; Profile priority; then `componentId`. Profile v1 has
no priority field, so the adapter substitutes confirmed confidence descending,
then `componentId` and `slotId` ascending. The output records
`PROFILE_V1_CONFIDENCE_FALLBACK_TOTAL_ORDER` and emits a stable non-blocking
warning. No ambiguity error or profile traversal accident decides the result.

## 3. Template Page Resolver

For each locked slide, candidates must exactly equal
`semanticLayout.primaryRole`. Candidates are ordered by `sourceSlide` ascending,
then `pageReferenceId` ascending; the first candidate is selected. There is no
fuzzy fallback, random choice, model choice, or mutable cache. No exact candidate
produces `TEMPLATE_PAGE_MISSING`; this is a whole-Job structural blocker, so no
page is omitted and no partial Plan is returned. Local component/layout failures
may continue only when every N input page remains in exact
`slideId`/`pageNumber`/Specification-order correspondence; the final validator
independently enforces this invariant.

## 4. Layout Resolver

The resolver does not calculate a new layout. `ComponentSlot.bounds` from the
confirmed Profile is the target geometry and is copied unchanged as four integer
EMU values.

For every binding it verifies:

- the component and slot are selected and come from the Profile;
- `left/top >= 0`, `width/height > 0`, and the rectangle stays inside page size;
- ordinary placement remains inside the Profile safe rectangle;
- a matching `FULL_BLEED` region may touch the page edge but not cross it;
- semantic regions with the same role are consumed in Specification array order;
- each region's `maxItems` is enforced independently;
- requested transform is copied from the locked slide and allowed transform from
  the confirmed component.

When no semantic region matches, the confirmed slot is still used but receives
no region ID and follows the ordinary safe-area rule. Layout never shrinks text,
changes slot dimensions, performs collision avoidance, or derives a replacement
position.

## 5. Slide Composer

Slides retain Specification array order, `slideId`, and `pageNumber`. Every slide
uses this operation order:

1. `PRESERVE_BASE_OBJECT` follows selected page `objectIds` order.
2. `USE_OR_CLONE_COMPONENT_OBJECT` sorts components by stable `componentId` and
   retains each component's `shapeRefs` order.
3. `FILL_TEXT_SLOT` follows `contentBlocks` order.
4. `FILL_ASSET_SLOT` follows `assetRequirements` order.

If a component and selected page share `sourceSlide`, the operation says
`USE_EXISTING_OBJECT`. Otherwise it says `CLONE_FROM_SOURCE`. The Composer does
not perform either action; it only records the deterministic instruction.

Text content is never copied into the plan. A text fill records `blockId` and the
lowercase SHA-256 of exact UTF-8 input bytes. Asset fills record both
Specification-side `assetRequirementId` and Manifest-side `approvedAssetId`,
plus approved type/hash, but never the asset `source`. Approved optional omission
creates no fill operation. IDs are generated from typed, null-delimited stable
tuples rather than array indexes or process state.

Both object operation kinds carry only a nested
`StableNativeObjectReference`. Component type comes from Profile facts; a base
page object without a known type is `UNKNOWN`. Clone decisions use the nested
reference's `sourceSlide`. No package hash, part URI, object path, relationship,
raster substitute, or other unsupported identity is guessed.

The Profile's TextFitPolicy is versioned and includes bounded font size, line
spacing, text-box growth, paragraph spacing, and an explicit adjustment order.
`NO_ADJUSTMENT_PROFILE_V1` preserves the existing safe path. A constrained
policy requires a matching font-environment version and a real measurement
result; because real font measurement is outside this slice, composition fails
closed with `TEXT_FIT_POLICY_UNAVAILABLE`. The pure Planner accepts a supplied
measurement and returns only bounded typography parameter changes. It never
creates text, rewrites, truncates, splits, merges, or enables AutoFit. Bounds
that cannot be satisfied return `CONTENT_OVERFLOW`.

## 6. Plan checksum

Canonical JSON recursively sorts object keys, preserves array order, and removes
only root `planChecksum`. The SHA-256 input is the resulting UTF-8 bytes. The plan
contains no timestamp, UUID, randomness, or runtime path. It does include the
Generation Job/Attempt, binding checksum, Engine/Executor/font versions, exact
Manifest reference, selection bases, TextFit boundary, and native references,
so binding changes are observable while identical valid inputs produce
byte-identical plan JSON and checksum. Feedback time is outside the Plan.

## 7. Plan Build Validator

The post-composer Build Validator is the explicit boundary before a future
Executor. It first rechecks the Profile TextFit policy and Job font-environment
binding, then invokes the existing independent CompositionPlanValidator for
the remaining Plan checks. The compatibility validator does not trust prior
stages and independently checks:

This order is part of the contract: TextFitPolicy diagnostics are emitted first,
followed by compatibility-plan diagnostics. Therefore simultaneous policy and
plan defects have one stable diagnostic order and cannot vary by entry point.

- request, Generation Job/Attempt, Specification, Profile, Manifest, versions,
  and content checksums;
- original slide count, safe slide subset/order, IDs, and page numbers;
- exact stable template-page selection and selection basis;
- sorted component placements and Specification-ordered slot bindings;
- deterministic region assignment, per-region capacity, page and safe bounds;
- every complete block/asset exactly once, each declared local omission absent,
  and every Profile/Manifest reference valid;
- operation category order and operation ID derived from its exact fields;
- component source action, slot, bounds, requested/allowed transform;
- exact UTF-8 content hash and approved/supported asset type;
- TextFit prohibition flags and absence of forbidden content/source/provider/
  credential fields;
- recomputed plan checksum.

Any validator violation is `JOB_BLOCKING` and produces no successful API Plan.
Known local incompleteness is passed as explicit validation context so it cannot
be mistaken for a malformed Plan. The validator never deletes, reorders,
substitutes, or repairs an operation.

## 8. HTTP and safety boundary

`POST /internal/v1/compose-plan` uses the same strict parser and 2,000,000-byte
bounded request path as preflight. Malformed, unknown-field, or Schema-invalid
input is 400; Job-blocking failure is 422; PARTIAL and both success statuses are
200.
Known-length and true chunked overflow are 413, while the exact boundary proceeds
to parsing.

Responses and logs must not expose teacher text, asset source, source reference,
credentials, provider output, exception stacks, or host paths. Diagnostics use
stable IDs, codes, counts, and safe reasons only.

## 9. Explicit non-goals

This slice does not perform visual template parsing, select POI/Aspose/COM,
measure real fonts, rasterize native objects, persist Jobs, or connect to the
Vue/Spring host system. The same-package Executor does generate and validate a
file-level OPC/XML artifact, but it does not open/save/reopen/render it in
PowerPoint. Those application and human-visual checks require fresh
independent review; passing the package gate is not evidence of visual
acceptance.
