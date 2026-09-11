# ADR 0002: Freeze v1 JSON contracts and checksum semantics

## Decision

The five JSON Schemas under `contracts/v1/` are the formal v1 source:

- `locked-ppt-specification.schema.json`
- `confirmed-template-profile.schema.json`
- `generation-feedback.schema.json`
- `engine-preflight-request.schema.json`
- `engine-preflight-response.schema.json`

Every contract carries `contractVersion: 1.0.0`; unknown fields are rejected.
The specification checksum is SHA-256 over canonical UTF-8 JSON after removing
only the root `checksum` field. Object keys are recursively sorted and array
order is preserved. `generatedAt` belongs to feedback and is not part of the
preflight plan or checksum.

## Rationale

The downstream Engine must be able to prove that it received the exact locked
input. A checksum mismatch is a rejection, not an invitation to repair or
normalize the specification.

## Consequences

Any teacher change requires a new specification version and checksum. Feedback
contains safe IDs, codes, and counts only; it never echoes content, credentials,
provider response bodies, or local paths.
