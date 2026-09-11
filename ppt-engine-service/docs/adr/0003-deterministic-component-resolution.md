# ADR 0003: Resolve components deterministically

## Decision

The resolver is a pure function of one locked slide and one confirmed profile.
It can select only `teacherConfirmed=true` and `reusable=true` components. Slot
content type and semantic role must match. Transform requirements must be
supported by the component. Ties are resolved by exact slide role, confidence
descending, and component ID ascending; slots within a component retain their
confirmed contract order.

Each slide has one shared occupancy map keyed by stable `slotId`. Content blocks
are visited in locked Specification array order, followed by assets in array
order. Both kinds of binding consume the same counter. `maxItems=N` caps their
combined count, while `maxItems=null` has no item-count limit. `maxCharacters`
is evaluated independently for each text block and never causes truncation,
summarization, rewriting, splitting, or merging.

After allocation, every `required=true` slot on a selected component must be
filled. An empty required slot produces `REQUIRED_SLOT_UNFILLED` with safe
`componentId` and `slotId` and makes preflight non-successful. Exhausted capacity
produces `SLOT_CAPACITY_EXCEEDED`; the current input remains unresolved.

The frozen v1 asset mapping is deliberately narrow: `IMAGE -> IMAGE`,
`CHART -> CHART`, and `TABLE -> TABLE`. `ICON`, `VIDEO`, and `OTHER` have no
frozen same-type ContentType semantics, so they produce
`ASSET_TYPE_UNSUPPORTED` instead of being guessed as TEXT or IMAGE.

When no candidate or remaining capacity exists, the plan records an unresolved
block or asset and a stable diagnostic. The resolver never invents components,
changes content, crosses slide boundaries, or moves, deletes, splits, or combines
input items.

## Rationale

Determinism makes the preflight plan reproducible and reviewable before a future
PowerPoint executor is introduced. Shared capacity prevents an apparently valid
plan from overbooking a confirmed template slot. Explicit unresolved state is
safer than a silent layout or asset-type substitution.

## Consequences

The current output is only a preflight plan. Layout resolution, slide composition
and native PowerPoint execution remain future boundaries and cannot silently be
implemented as successful no-op stages here.
