import assert from "node:assert/strict";
import crypto from "node:crypto";
import test from "node:test";
import { HarnessConfig } from "../src/config.js";
import { HarnessError, PresentationJob, SlideSpec } from "../src/domain.js";
import { PptSkillRunnerClient, RunnerGeneration } from "../src/runner-client.js";
import {
  KimiVisualQaProvider,
  VISUAL_ISSUE_CODES,
  VisualQaProvider,
  VisualQaService,
  VisualQaSlideInput,
  parseSlideResult,
  validateVisualQaResult,
} from "../src/visual-qa.js";

const jobId = "33333333-3333-4333-8333-333333333333";
const config: HarnessConfig = {
  port: 8091, host: "127.0.0.1", databaseUrl: "postgres://unused", runnerBaseUrl: "http://runner",
  runnerTimeoutMs: 1_000, generationSource: "FIXTURE", visualReviewEnabled: true, eventPollIntervalMs: 750,
  artifactRetentionDays: 7, maxRepairAttempts: 1, kimiBaseUrl: "https://kimi.example/v1", kimiModel: "kimi-k2.6", kimiTimeoutMs: 1_000,
};

const job = { id: jobId } as PresentationJob;
const spec: SlideSpec = {
  schemaVersion: 2, deckTitle: "Visual QA", locale: "zh-CN", templateId: "template", templateVersion: "1.0.0",
  slides: ["HOOK", "COMPARISON", "SUMMARY"].map((role, index) => ({
    slideId: `slide-${index + 1}`, pedagogicalRole: role as "HOOK" | "COMPARISON" | "SUMMARY", learningObjectiveIds: [],
    teachingPurpose: `Purpose ${index + 1}`, title: `Title ${index + 1}`, content: {}, evidenceRefs: [], sourceNotes: [],
    visualIntent: { type: "TEXT", description: "Visible intent" }, assetRequests: [], layoutIntent: "Keep the layout readable",
    interaction: { type: "NONE" }, teacherNotes: "", density: "LOW", importance: "CORE", outlineSectionIndex: null,
    layoutId: "cover", visualStrategy: "Native shapes", slots: {},
  })),
};

function png(fill: number, width = 160, height = 90): Uint8Array {
  const bytes = Buffer.alloc(32, fill);
  Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]).copy(bytes, 0);
  Buffer.from("IHDR").copy(bytes, 12);
  bytes.writeUInt32BE(width, 16);
  bytes.writeUInt32BE(height, 20);
  return bytes;
}

function runnerGeneration(images: Uint8Array[] = [png(1), png(2), png(3)]): RunnerGeneration {
  return {
    jobId, status: "SUCCEEDED", fileName: "presentation.pptx", sizeBytes: 1, sha256: "a".repeat(64),
    qa: { passed: true, qaLevel: "AUTOMATED_GEOMETRY_ONLY", report: {} }, buildDurationMs: 1, qaDurationMs: 1, totalDurationMs: 2,
    files: { presentation: `/internal/ppt-skill/v1/jobs/${jobId}/presentation.pptx`, outline: `/internal/ppt-skill/v1/jobs/${jobId}/outline.json`, qaReport: `/internal/ppt-skill/v1/jobs/${jobId}/qa-report.json` },
    preview: {
      rendered: true, format: "png", dpi: 150, slideCount: images.length,
      totalSizeBytes: images.reduce((sum, image) => sum + image.byteLength, 0),
      files: images.map((image, index) => ({
        slideNumber: index + 1, fileName: `slide-${String(index + 1).padStart(2, "0")}.png`, sizeBytes: image.byteLength,
        sha256: crypto.createHash("sha256").update(image).digest("hex"), width: 160, height: 90,
        downloadRef: `/internal/ppt-skill/v1/jobs/${jobId}/previews/${index + 1}`,
      })),
    },
  };
}

function service(provider: VisualQaProvider, generation = runnerGeneration()): VisualQaService {
  const images = generation.preview.files.map((file, index) => index === 0 ? png(1) : index === 1 ? png(2) : png(3));
  return new VisualQaService({
    async downloadPreview(ref: string) {
      const slide = Number(ref.split("/").pop());
      return images[slide - 1];
    },
  } as Pick<PptSkillRunnerClient, "downloadPreview">, provider);
}

function issue(slideNumber: number, code: typeof VISUAL_ISSUE_CODES[number], severity: "INFO" | "WARNING" | "ERROR" = "ERROR") {
  return { slideNumber, code, severity, confidence: 0.94, description: `${code} is visibly present`, repairHint: "Reduce or reposition the affected visual element" };
}

class FakeProvider implements VisualQaProvider {
  readonly inputs: VisualQaSlideInput[] = [];
  repairCalls = 0;
  constructor(private readonly responses: unknown[], private readonly repairs: unknown[] = []) {}
  async review(input: VisualQaSlideInput): Promise<unknown> { this.inputs.push(input); return this.responses.shift(); }
  async repair(_input: VisualQaSlideInput, _invalid: unknown, _reason: string): Promise<unknown> { this.repairCalls += 1; return this.repairs.shift(); }
}

test("clean rendered slides pass and send controlled PNG plus minimal SlideSpec context", async () => {
  const provider = new FakeProvider(spec.slides.map((_slide, index) => ({ slideNumber: index + 1, issues: [] })));
  const result = await service(provider).review(job, spec, runnerGeneration());
  assert.deepEqual(result, { jobId, passed: true, reviewedSlideCount: 3, issues: [] });
  assert.match(provider.inputs[0].imageDataUrl, /^data:image\/png;base64,/);
  assert.deepEqual(Object.keys(provider.inputs[0].expected).sort(), ["density", "importance", "layoutIntent", "pedagogicalRole", "teachingPurpose", "title", "visualIntent"]);
  assert.equal((provider.inputs[0].expected as Record<string, unknown>).content, undefined);
});

test("visual issue taxonomy classifies overflow, overlap, tiny text, and broken rendering", async () => {
  const cases: Array<[typeof VISUAL_ISSUE_CODES[number], "WARNING" | "ERROR"]> = [["TEXT_OVERFLOW", "ERROR"], ["ELEMENT_OVERLAP", "ERROR"], ["TEXT_TOO_SMALL", "WARNING"], ["BROKEN_RENDERING", "ERROR"]];
  for (const [code, severity] of cases) {
    const provider = new FakeProvider([{ slideNumber: 1, issues: [issue(1, code, severity)] }]);
    const oneSlide = { ...spec, slides: [spec.slides[0]] };
    const result = await service(provider, runnerGeneration([png(1)])).review(job, oneSlide, runnerGeneration([png(1)]));
    assert.equal(result.issues[0].code, code);
    assert.equal(result.issues[0].severity, severity);
    assert.equal(result.passed, severity !== "ERROR");
  }
});

test("multi-slide visual issues are deterministic by slide and issue code", async () => {
  const provider = new FakeProvider([
    { slideNumber: 1, issues: [issue(1, "ELEMENT_OVERLAP")] },
    { slideNumber: 2, issues: [issue(2, "TEXT_OVERFLOW")] },
    { slideNumber: 3, issues: [issue(3, "BROKEN_RENDERING")] },
  ]);
  const result = await service(provider).review(job, spec, runnerGeneration());
  assert.deepEqual(result.issues.map(item => `${item.slideNumber}:${item.code}`), ["1:ELEMENT_OVERLAP", "2:TEXT_OVERFLOW", "3:BROKEN_RENDERING"]);
  assert.equal(result.reviewedSlideCount, 3);
});

test("missing preview and PNG hash mismatch fail closed", async () => {
  const provider = new FakeProvider([{ slideNumber: 1, issues: [] }]);
  const oneSlide = { ...spec, slides: [spec.slides[0]] };
  await assert.rejects(() => service(provider).review(job, oneSlide, runnerGeneration()), (error: unknown) => error instanceof HarnessError && error.code === "VISUAL_REVIEW_COUNT_MISMATCH");
  const bad = runnerGeneration([png(1)]);
  bad.preview.files[0].sha256 = "b".repeat(64);
  await assert.rejects(() => service(provider, bad).review(job, oneSlide, bad), (error: unknown) => error instanceof HarnessError && error.code === "VISUAL_REVIEW_PNG_HASH_MISMATCH");
});

test("invalid visual JSON gets at most one structured repair", async () => {
  const valid = { slideNumber: 1, issues: [] };
  const repaired = new FakeProvider([{}], [valid]);
  const oneSlide = { ...spec, slides: [spec.slides[0]] };
  const result = await service(repaired, runnerGeneration([png(1)])).review(job, oneSlide, runnerGeneration([png(1)]));
  assert.equal(result.passed, true);
  assert.equal(repaired.repairCalls, 1);

  const stillInvalid = new FakeProvider([{}], [{}]);
  await assert.rejects(() => service(stillInvalid, runnerGeneration([png(1)])).review(job, oneSlide, runnerGeneration([png(1)])), (error: unknown) => error instanceof HarnessError && error.code === "VISUAL_REVIEW_INVALID_RESULT");
  assert.equal(stillInvalid.repairCalls, 1);
});

test("review count mismatch and provider unavailability are explicit failures", async () => {
  assert.throws(() => validateVisualQaResult({ jobId, passed: true, reviewedSlideCount: 2, issues: [] }, jobId, 3), (error: unknown) => error instanceof HarnessError && error.code === "VISUAL_REVIEW_COUNT_MISMATCH");
  await assert.rejects(() => new KimiVisualQaProvider(config).review({ slideNumber: 1, imageDataUrl: "data:image/png;base64,AA==", expected: spec.slides[0] } as VisualQaSlideInput), (error: unknown) => error instanceof HarnessError && error.code === "VISUAL_REVIEW_PROVIDER_UNAVAILABLE");
});

test("Kimi Visual QA uses the configured multimodal image input contract", async () => {
  const originalFetch = globalThis.fetch;
  let request: RequestInit | undefined;
  globalThis.fetch = async (_url, init) => {
    request = init;
    return new Response(JSON.stringify({ choices: [{ message: { content: JSON.stringify({ slideNumber: 1, issues: [] }) } }] }), { status: 200 });
  };
  try {
    await new KimiVisualQaProvider({ ...config, kimiApiKey: "test-key" }).review({
      slideNumber: 1,
      imageDataUrl: "data:image/png;base64,UE5H",
      expected: {
        title: spec.slides[0].title,
        pedagogicalRole: spec.slides[0].pedagogicalRole,
        teachingPurpose: spec.slides[0].teachingPurpose,
        visualIntent: spec.slides[0].visualIntent,
        layoutIntent: spec.slides[0].layoutIntent,
        density: spec.slides[0].density,
        importance: spec.slides[0].importance,
      },
    });
    const body = JSON.parse(String(request?.body)) as { model: string; thinking?: { type?: string }; response_format: { type: string; json_schema?: { name?: string; strict?: boolean; schema?: { required?: string[] } } }; messages: Array<{ role: string; content: unknown }> };
    assert.equal(body.model, "kimi-k2.6");
    assert.equal(body.thinking?.type, "disabled");
    assert.equal(body.response_format.type, "json_schema");
    assert.equal(body.response_format.json_schema?.name, "visual_qa_slide_result_v1");
    assert.equal(body.response_format.json_schema?.strict, true);
    assert.deepEqual(body.response_format.json_schema?.schema?.required, ["slideNumber", "issues"]);
    const userContent = body.messages[1].content as Array<{ type: string; text?: string; image_url?: { url: string } }>;
    assert.equal(userContent[1].type, "image_url");
    assert.equal(userContent[1].image_url?.url, "data:image/png;base64,UE5H");
    assert.match(userContent[0].text || "", /slideNumber/);
    assert.equal((userContent[0].text || "").includes("evidenceRefs"), false);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("Kimi invalid JSON enters the single structured repair path", async () => {
  const originalFetch = globalThis.fetch;
  let calls = 0;
  globalThis.fetch = async () => {
    calls += 1;
    const content = calls === 1 ? "not-json" : JSON.stringify({ slideNumber: 1, issues: [] });
    return new Response(JSON.stringify({ choices: [{ message: { content } }] }), { status: 200 });
  };
  try {
    const oneSlide = { ...spec, slides: [spec.slides[0]] };
    const result = await service(new KimiVisualQaProvider({ ...config, kimiApiKey: "test-key" }), runnerGeneration([png(1)])).review(job, oneSlide, runnerGeneration([png(1)]));
    assert.equal(result.passed, true);
    assert.equal(calls, 2);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("Kimi Visual QA aborts a provider request at the configured timeout", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (_url, init) => await new Promise((_resolve, reject) => {
    init?.signal?.addEventListener("abort", () => reject(Object.assign(new Error("aborted"), { name: "AbortError" })));
  });
  try {
    const started = Date.now();
    await assert.rejects(
      () => new KimiVisualQaProvider({ ...config, kimiApiKey: "test-key", kimiTimeoutMs: 20 }).review({
        slideNumber: 1,
        imageDataUrl: "data:image/png;base64,UE5H",
        expected: {
          title: "Photosynthesis",
          pedagogicalRole: "HOOK",
          teachingPurpose: "Inspect the rendered cover slide",
          visualIntent: { type: "MIXED", description: "Review the visible visual hierarchy" },
          layoutIntent: "Keep the slide readable",
          density: "MEDIUM",
          importance: "CORE",
        },
      }),
      (error: unknown) => error instanceof HarnessError && error.code === "VISUAL_REVIEW_PROVIDER_UNAVAILABLE",
    );
    assert.ok(Date.now() - started < 500);
  } finally {
    globalThis.fetch = originalFetch;
  }
});
