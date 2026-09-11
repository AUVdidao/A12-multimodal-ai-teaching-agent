# PPT Engine Service

`ppt-engine-service` is the independent, deterministic execution boundary for
the new PPT Engine. The current slice validates frozen inputs, resolves
components and confirmed layout slots, and produces a versioned Composition
Plan. It also exposes a bounded same-package Executor that copies one
teacher-selected PPTX into an isolated Working Copy, materializes the exact
locked slide sequence from template slide parts (including repeated template
pages), applies the plan inside that package, and runs a file-level OOXML gate
before returning an artifact.

```text
LOCKED PPT Specification + Confirmed Template Profile
                 + Approved Asset Manifest
                         |
                         v
Generation Job binding + strict Contract/Manifest Gate
                         |
                         v
immutable Validated Execution Package
                         |
                         v
Component Resolver -> Template Page Resolver -> Layout Resolver
                         |
                         v
Slide Composer -> Plan Build Validator -> Composition Plan Validator (compatibility checks)
                         |
                         v
Same-Package PowerPoint Executor -> File Build Validator -> PPTX + Feedback
```

The service does not parse arbitrary natural-language requirements, call a
model, read credentials, or connect to the host Spring Boot application. It is
stateless and has no database, JPA, Apache POI, PowerPoint COM automation, AI
SDK, or outbound network client. The Executor is a deliberately narrow
ZIP/XML package adapter, not a claim of PowerPoint 16.0 compatibility.

## Endpoints

- `GET /internal/health`
- `POST /internal/v1/preflight`
- `POST /internal/v1/compose-plan`
- `POST /internal/v1/execute`

Malformed JSON, unknown fields, and Schema failures return HTTP 400 with safe
feedback. Compose errors use the v2 error envelope with no Plan; legacy
preflight keeps its v1 feedback envelope. Contract/checksum/input-binding
failures, a missing Template Page, an unusable Layout, and an invalid final
Plan are `JOB_BLOCKING`: HTTP 422, `FAILED`, and no Plan. Safe local
resolver/layout/composer failures may return HTTP 200 with `PARTIAL` only when
all N input pages remain present with the same `slideId`, `pageNumber`, and
Specification order. Pure warnings return `SUCCEEDED_WITH_FEEDBACK`; a
warning-free result returns `SUCCEEDED`.

All POST endpoints share the same exact 2,000,000-byte request boundary.
Known-length and real chunked bodies of 2,000,001 bytes return HTTP 413. An
exactly 2,000,000-byte body passes the size filter and proceeds to normal JSON
and Schema handling.

## Deterministic composition rules

- A template page must exactly match the locked slide's `primaryRole`; ties use
  `sourceSlide`, then `pageReferenceId`.
- A missing exact Template Page or a null Layout blocks the whole Job before
  composition. The final Validator independently requires exact N/N page count,
  slide IDs, page numbers, and order; it never accepts an omitted-page Plan.
- Compose v2 binds one `generationJobId` to immutable Specification, Profile,
  Manifest, Engine, Executor-adapter, and font-environment versions. A retry may
  change only `executionAttemptId`; a changed Manifest requires a new Job.
- Profile v1 has no `resolverPriority`, so compose candidates use the explicit
  compatibility adapter order: exact component role, compatible slot/type/
  transform, minimum sufficient capacity, confidence descending,
  `componentId`, then `slotId`. The Plan records `selectionBasis` and the
  selected component and emits a stable non-blocking warning.
- Slot bounds are copied from the confirmed Profile as integer EMU. They are not
  resized, inferred, or repaired.
- Ordinary placements must remain inside the confirmed safe area. A matching
  `FULL_BLEED` region may reach the page edge but may never cross it.
- Matching semantic regions are consumed in Specification array order and obey
  each region's `maxItems`.
- Operations are ordered as base-page objects, component objects, text blocks,
  and assets. Their internal orders are also frozen by the confirmed arrays or
  stable IDs.
- Same-source component objects are marked `USE_EXISTING_OBJECT`; cross-source
  objects are marked `CLONE_FROM_SOURCE`. This is only a plan instruction.
- Native-object operations carry only a versioned nested
  `StableNativeObjectReference`. Base-page object type is `UNKNOWN` when the
  confirmed Profile does not provide it; no type or OOXML locator is guessed.
- Teacher text is represented by `blockId` plus UTF-8 SHA-256. Asset source,
  teacher text, local paths, credentials, and provider output are never copied
  into the plan.
- Asset fills bind both the Specification `assetRequirementId` and the
  Manifest's `approvedAssetId`, type, and content hash. Approved optional
  omission creates no fill operation.
- The confirmed Profile may carry a versioned `TextFitPolicy`. The default
  `NO_ADJUSTMENT_PROFILE_V1` remains backward-compatible and records that real
  font measurement is not implemented. A constrained policy is accepted only
  with a matching font-environment version and a proven measurement result;
  this slice has no real measurement capability, so it fails closed as
  `TEXT_FIT_POLICY_UNAVAILABLE`. The deterministic Planner can only return
  bounded typography parameter changes; it never rewrites, truncates, splits,
  merges, or delegates to PowerPoint AutoFit. Capacity overflow is
  `CONTENT_OVERFLOW`.
- The Executor accepts one hash-bound, same-package template source. It
  preserves the source and edits a byte-copy in an execution-id directory.
  When the locked output sequence differs from the source sequence, it creates
  new slide parts inside the same OPC package and rewrites only the
  presentation slide list; the selected layout/master/theme relationships
  remain package-local. Shape text and existing picture objects remain native
  XML objects. Cloned native objects retain their confirmed top-level identity;
  nested IDs are made unique and every relationship attribute is rebound to the
  target slide relationship part. Picture media is copied only from an
  Approved Asset file whose requirement, asset ID, type, and SHA-256 match the
  manifest. Unsupported object constructions, cross-package references, and
  Raster Fallback are rejected or returned as explicit feedback; they are not
  converted to images.

The post-composer Plan Build Validator has one frozen order: it first checks
TextFit policy/font-environment binding, then invokes the existing compatibility
validator. It independently rechecks root references, slide count and
order, exact template-page selection, placement and region order, one-to-one
bindings, profile-owned IDs, operation identity/order, transforms, bounds,
approved/supported assets, text hashes, forbidden fields, and plan checksum. It
rejects defects and never repairs a plan.

`UNBOUND` is accepted only by the legacy-compatible
`NO_ADJUSTMENT_PROFILE_V1` default path. `PROFILE_CONSTRAINED` requires a
non-`UNBOUND` font environment that matches the Job; the current service still
fails closed because real measurement is unavailable. Planner output validates
every fontsize, line-spacing, paragraph-spacing, and text-box width/height
change against the same policy bounds.

## Contract versions and checksums

The original five input/preflight Schemas remain frozen at `1.0.0`. Final
composition uses a separate three-input request plus four compose Schemas:

- Approved Asset Manifest contract: `1.0.0`
- request input contract: `2.0.0`
- Composition Plan contract: `2.0.0`
- Composition Feedback contract: `2.0.0`

The version split prevents the expanded composition diagnostics from silently
changing the old `generation-feedback` contract. See
`docs/adr/0004-feedback-and-plan-contract-versioning.md`.

Canonical checksum input recursively sorts object keys and preserves array
order. Specification/Manifest checksums exclude only their root checksum fields;
the Job binding checksum excludes only `executionAttemptId` and its checksum;
the plan checksum excludes only root `planChecksum`. The Plan includes exact
Job/Attempt and execution-version references, so a valid Job binding change is
visible in its checksum. SHA-256 uses UTF-8 JSON and lowercase hexadecimal.

## Run locally

```text
mvn test
mvn package
mvn spring-boot:run
```

```text
docker build -t ppt-engine-service:local .
docker run --rm -p 8080:8080 ppt-engine-service:local
```

The image runs as the non-root `engine` user and exposes a Docker health check.
Contract Schemas and executable examples are under
`src/main/resources/contracts/v1/`.

## Responsibility boundary

The upstream system owns teacher confirmation, authorization, content,
materials, RAG, credentials, and lifecycle persistence. This service accepts
only already locked and confirmed inputs. PowerPoint 16.0
Open/SaveCopy/Reopen/Render, visible repair prompts, real font measurement,
preview generation, native-object visual acceptance, and host application
integration remain unverified by this slice. The Executor's `buildValidation`
response is a file/package gate result, not PowerPoint visual acceptance.
`OpcPackageAdapter` in the separate fidelity lab remains
`PROVISIONAL_CANDIDATE` and is not used here.
