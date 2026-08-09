import assert from "node:assert/strict";
import test from "node:test";
import { HarnessError } from "../src/domain.js";
import { HarnessConfig } from "../src/config.js";
import { parseRunnerGeneration, PptSkillRunnerClient } from "../src/runner-client.js";
import { runnerResultFromCheckpoint } from "../src/workflow.js";

const jobId = "11111111-1111-4111-8111-111111111111";
const config: HarnessConfig = {
  port: 8091,
  host: "127.0.0.1",
  databaseUrl: "postgres://unused",
  runnerBaseUrl: "http://runner.test",
  runnerTimeoutMs: 1_000,
  generationSource: "FIXTURE",
  visualReviewEnabled: false,
  eventPollIntervalMs: 10,
  artifactRetentionDays: 7,
  maxRepairAttempts: 1,
  kimiBaseUrl: "https://api.moonshot.ai/v1",
  kimiModel: "kimi-k2.6",
  kimiTimeoutMs: 1_000,
};

function generation(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    jobId,
    status: "SUCCEEDED",
    fileName: "presentation.pptx",
    sizeBytes: 100,
    sha256: "a".repeat(64),
    qa: { passed: true, qaLevel: "AUTOMATED_GEOMETRY_ONLY", report: {} },
    buildDurationMs: 1,
    qaDurationMs: 2,
    totalDurationMs: 3,
    files: {
      presentation: `/internal/ppt-skill/v1/jobs/${jobId}/presentation.pptx`,
      outline: `/internal/ppt-skill/v1/jobs/${jobId}/outline.json`,
      qaReport: `/internal/ppt-skill/v1/jobs/${jobId}/qa-report.json`,
    },
    preview: {
      rendered: true,
      format: "png",
      dpi: 150,
      slideCount: 3,
      totalSizeBytes: 30,
      files: [1, 2, 3].map(slideNumber => ({
        slideNumber,
        fileName: `slide-${String(slideNumber).padStart(2, "0")}.png`,
        sizeBytes: 10,
        sha256: "b".repeat(64),
        width: 1920,
        height: 1080,
        downloadRef: `/internal/ppt-skill/v1/jobs/${jobId}/previews/${slideNumber}`,
      })),
    },
    ...overrides,
  };
}

test("Runner generation response accepts a complete preview manifest", () => {
  const result = parseRunnerGeneration(generation());
  assert.equal(result.preview.slideCount, 3);
  assert.equal(result.preview.files[2].downloadRef, `/internal/ppt-skill/v1/jobs/${jobId}/previews/3`);
});

test("Runner generation response rejects bad preview hash, ordering, and totals", () => {
  const badHash = generation();
  ((badHash.preview as Record<string, unknown>).files as Array<Record<string, unknown>>)[0].sha256 = "bad";
  assert.throws(() => parseRunnerGeneration(badHash), (error: unknown) => error instanceof HarnessError && error.code === "RUNNER_RESPONSE_INVALID");

  const badOrder = generation();
  ((badOrder.preview as Record<string, unknown>).files as Array<Record<string, unknown>>)[1].slideNumber = 3;
  assert.throws(() => parseRunnerGeneration(badOrder), (error: unknown) => error instanceof HarnessError && error.code === "RUNNER_RESPONSE_INVALID");

  const badTotal = generation();
  (badTotal.preview as Record<string, unknown>).totalSizeBytes = 31;
  assert.throws(() => parseRunnerGeneration(badTotal), (error: unknown) => error instanceof HarnessError && error.code === "RUNNER_RESPONSE_INVALID");
});

test("Runner generation response rejects a missing preview manifest", () => {
  const missing = generation();
  delete missing.preview;
  assert.throws(() => parseRunnerGeneration(missing), (error: unknown) => error instanceof HarnessError && error.code === "RUNNER_RESPONSE_INVALID");
});

test("Harness can download preview PNGs on demand through the controlled reference", async () => {
  const originalFetch = globalThis.fetch;
  const requested: string[] = [];
  globalThis.fetch = (async (input: string | URL | Request) => {
    requested.push(String(input));
    return new Response(new Uint8Array([137, 80, 78, 71]), { status: 200 });
  }) as typeof fetch;
  try {
    const client = new PptSkillRunnerClient(config);
    const bytes = await client.downloadPreview(`/internal/ppt-skill/v1/jobs/${jobId}/previews/2`);
    assert.deepEqual([...bytes], [137, 80, 78, 71]);
    assert.deepEqual(requested, [`http://runner.test/internal/ppt-skill/v1/jobs/${jobId}/previews/2`]);
    await assert.rejects(() => client.downloadPreview("/internal/ppt-skill/v1/jobs/not-a-job/previews/2"), (error: unknown) => error instanceof HarnessError && error.code === "RUNNER_RESPONSE_INVALID");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("complete Runner checkpoint reuses the preview manifest, incomplete checkpoint does not", () => {
  const reused = runnerResultFromCheckpoint({ runnerResult: generation() });
  assert.equal(reused?.preview.slideCount, 3);
  assert.equal(runnerResultFromCheckpoint({ runnerJobId: jobId }), undefined);
  const incomplete = generation();
  delete incomplete.preview;
  assert.equal(runnerResultFromCheckpoint({ runnerResult: incomplete }), undefined);
});
