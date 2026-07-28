# A12 PPT Skill Runner - Phase 1

This service is an isolated validation runner for the pinned
`presentation-skill` runtime. Phase 1 does not call Kimi, Dify, Codex, APP-04,
the Spring Boot backend, or any external image-generation service.

## Pinned upstream

- Source: `https://github.com/sirilsengolraj-source/presentation-skill`
- Commit: `3a22eed290fa2205b6a1e2de5549b4429c5fffd0`
- License: MIT
- License SHA-256: `FB8F829F00CBE895F0BFCB244A8A8E3E9C75FCB5F6543CFEE15CAE5C82B0879F`

The Docker build uses the vendored, pinned runtime subset. It never follows an
upstream branch during image construction.

## Supported Phase-1 variants

`standard`, `split`, `cards-2`, `cards-3`, `timeline`, `stats`,
`comparison-2col`, `chart`, `table`, `image-sidebar`, and `flow`.

External URLs, `file://`, generated-image fields, absolute asset paths, path
traversal, symbolic links, and non-whitelisted presets are rejected.

## Runner API

`POST /internal/ppt-skill/v1/generations`

```json
{
  "outline": { "title": "...", "slides": [] },
  "stylePreset": "forest-research"
}
```

A successful response is returned only after the PPTX build and automated
geometry QA both pass. It includes the job ID, SHA-256, byte size, timings,
QA summary, and download paths for `presentation.pptx`, `outline.json`, and
`qa-report.json`.

The QA result is explicitly labeled:

```text
qaLevel=AUTOMATED_GEOMETRY_ONLY
```

This is not a substitute for rendered-slide and human visual review.

## Build and test

```powershell
docker build -t a12-ppt-skill-runner:phase1 .
docker run --rm a12-ppt-skill-runner:phase1 npm test
```

## Generate the fixed fixture

```powershell
docker run --rm `
  -v "${PWD}/data:/app/data" `
  a12-ppt-skill-runner:phase1 npm run generate:fixture
```

## Start the service

```powershell
docker run --rm -p 8090:8090 `
  -v "${PWD}/data:/app/data" `
  a12-ppt-skill-runner:phase1
```

Health check: `GET http://localhost:8090/health`.

Windows PowerShell 5.1 must read the UTF-8 fixture explicitly before calling
the API; otherwise Chinese JSON without a BOM can be decoded with the system
code page:

```powershell
$outline = Get-Content -Raw -Encoding UTF8 `
  .\fixtures\grade-8-biology-photosynthesis-outline.json | ConvertFrom-Json
$body = @{ outline = $outline; stylePreset = "forest-research" } |
  ConvertTo-Json -Depth 100
Invoke-RestMethod -Method Post `
  -Uri http://localhost:8090/internal/ppt-skill/v1/generations `
  -ContentType "application/json; charset=utf-8" `
  -Body ([System.Text.Encoding]::UTF8.GetBytes($body))
```

## Upstream commands executed by the runner

Build:

```text
node <skill-home>/scripts/build_deck_pptxgenjs.js
  --outline <task-dir>/outline.json
  --output <task-dir>/presentation.pptx
  --style-preset forest-research
  --asset-root <task-dir>
```

Automated geometry QA:

```text
python3 <skill-home>/scripts/qa_gate.py
  --input <task-dir>/presentation.pptx
  --outdir <task-dir>/qa
  --style-preset forest-research
  --outline <task-dir>/outline.json
  --strict-geometry
  --skip-render
  --skip-manual-review
  --fail-on-design-warnings
  --report <task-dir>/qa-report.json
```

## Manual preview rendering

After generation, render slides for human inspection with:

```powershell
docker run --rm `
  -v "${PWD}/data:/app/data" `
  a12-ppt-skill-runner:phase1 `
  python3 /app/vendor/presentation-skill/scripts/render_slides.py `
  --input /app/data/results/<job-id>/presentation.pptx `
  --outdir /app/data/results/<job-id>/previews `
  --format png
```

Manual visual review remains required before Phase 2 integration.
