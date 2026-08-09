# A12 RAG Retrieval Evaluation Baseline

This folder contains the first repeatable black-box retrieval evaluation baseline for A12.

## Scope

- It evaluates deterministic lexical retrieval and the gated dense cosine retrieval endpoint through real HTTP APIs.
- It does not implement Hybrid Retrieval, Reranking, Parser changes, or production-code fixes.
- It stores only questions, expected anchors, expected concepts, expected chapter/title hints, and notes.
- It does not store the source PDF or long book excerpts.

## Current API

The current codebase exposes a workspace search API with explicit `PRECISE` and `BROAD` modes, plus a separate dense cosine endpoint:

```text
POST /api/projects/{projectId}/knowledge/workspace-search

POST /api/projects/{projectId}/knowledge/dense-search
```

Request fields used by the runner:

```json
{
  "query": "question text",
  "materialId": 184,
  "matchMode": "PRECISE",
  "caseSensitive": false,
  "page": 0,
  "size": 5
}
```

The runner logs lexical runs as `LEXICAL`. Use `-Modes DENSE` after the project's dense index is ready to evaluate the dense endpoint with the same 26-question dataset.

## Run

```powershell
.\scripts\run-rag-eval.ps1 `
  -BaseUrl http://127.0.0.1:8081 `
  -Username teacher `
  -Password "<password>" `
  -ProjectId 219 `
  -MaterialId 184 `
  -TopK 5 `
  -OutputFile .\tmp\rag-eval\ai-agent-book-baseline.json
```

Use `-BaselineSourceTruncated` when the indexed material still comes from a known truncated parse. This marks the JSON report with:

```text
BASELINE_SOURCE_TRUNCATED=true
```

## Metrics

- `Strong Hit@K`: at least one returned chunk contains any `expectedAnchors`.
- `Any Hit@K`: at least one returned chunk is either `STRONG_HIT` or `WEAK_HIT`.
- `MRR`: reciprocal rank of the first strong or weak match.
- `Material Precision`: when `-MaterialId` is provided, the ratio of top-k hits belonging to that material.

The script exits non-zero for runner/API/auth/data-format failures. Low retrieval quality is reported as metrics and does not fail the script.

## Tests

```powershell
.\scripts\rag-eval\run-rag-eval-tests.ps1
```

The tests cover deterministic scoring and output-shaping logic without calling the backend.
