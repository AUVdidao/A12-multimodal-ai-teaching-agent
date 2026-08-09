import assert from "node:assert/strict";
import crypto from "node:crypto";
import test from "node:test";
import { HarnessConfig } from "../src/config.js";
import { HarnessError, JobArtifact, JobEvent, JobStatus, PresentationJob, SlideSpec, TemplateSpec } from "../src/domain.js";
import { ControlledArtifactStore } from "../src/artifact-store.js";
import { PgJobRepository } from "../src/repository.js";
import { PptSkillRunnerClient, RunnerGeneration } from "../src/runner-client.js";
import { PresentationWorkflowService } from "../src/workflow.js";
import { TemplateRegistry } from "../src/template-registry.js";

const jobId = "22222222-2222-4222-8222-222222222222";
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

const template: TemplateSpec = {
  templateId: "a12-teaching-generic",
  version: "1.0.0",
  name: "Test template",
  locale: "zh-CN",
  previewRef: "internal",
  stylePreset: "editorial",
  layouts: [],
};

function job(): PresentationJob {
  return {
    id: jobId,
    requestId: "request-preview-resume",
    projectId: 12,
    status: "QUEUED",
    templateId: template.templateId,
    templateVersion: template.version,
    locale: "zh-CN",
    targetSlideCount: 3,
    progressPercent: 0,
    attemptCount: 0,
    jobSnapshot: {
      project: {},
      requirementSummary: {},
      confirmedTeachingIntent: {},
      confirmedGenerationPlan: {},
      materialEvidence: [],
      templateSelection: {},
      generationPreferences: {},
    },
    createdAt: new Date(0).toISOString(),
    updatedAt: new Date(0).toISOString(),
  };
}

function slideSpec(): SlideSpec {
  return {
    schemaVersion: 2,
    deckTitle: "Preview test",
    locale: "zh-CN",
    templateId: template.templateId,
    templateVersion: template.version,
    slides: ["HOOK", "CONCEPT", "SUMMARY"].map((role, index) => ({
      slideId: `slide-${index + 1}`,
      pedagogicalRole: role as "HOOK" | "CONCEPT" | "SUMMARY",
      learningObjectiveIds: [],
      teachingPurpose: "Verify real preview handoff",
      title: `Slide ${index + 1}`,
      content: {},
      evidenceRefs: [],
      sourceNotes: [],
      visualIntent: { type: "TEXT", description: "A deterministic preview test" },
      assetRequests: [],
      layoutIntent: "Use a deterministic test layout",
      interaction: { type: "NONE" },
      teacherNotes: "",
      density: "LOW",
      importance: "CORE",
      outlineSectionIndex: null,
      layoutId: "title_content",
      visualStrategy: "Text-only test layout",
      slots: {},
    })),
  };
}

function runnerResult(): RunnerGeneration {
  return {
    jobId,
    status: "SUCCEEDED",
    fileName: "presentation.pptx",
    sizeBytes: 4,
    sha256: crypto.createHash("sha256").update(Buffer.from([1, 2, 3, 4])).digest("hex"),
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
      totalSizeBytes: 3,
      files: [1, 2, 3].map(slideNumber => ({
        slideNumber,
        fileName: `slide-${String(slideNumber).padStart(2, "0")}.png`,
        sizeBytes: 1,
        sha256: "b".repeat(64),
        width: 1500,
        height: 844,
        downloadRef: `/internal/ppt-skill/v1/jobs/${jobId}/previews/${slideNumber}`,
      })),
    },
  };
}

class MemoryRepository {
  current = job();
  checkpoints = new Map<string, Record<string, unknown>>([
    ["VALIDATED_SLIDE_SPEC", slideSpec() as unknown as Record<string, unknown>],
    ["RUNNER", { runnerResult: runnerResult() as unknown as Record<string, unknown> }],
  ]);
  artifact: JobArtifact | undefined;
  qa: { qaLevel: string; passed: boolean; report: Record<string, unknown> } | undefined;
  events: JobEvent[] = [];

  async get(_id: string): Promise<PresentationJob | undefined> { return this.current; }
  async incrementAttempt(_id: string): Promise<void> { this.current.attemptCount += 1; }
  async latestCheckpoint(_id: string, type: string): Promise<Record<string, unknown> | undefined> { return this.checkpoints.get(type); }
  async saveCheckpoint(_id: string, type: string, payload: Record<string, unknown>): Promise<void> { this.checkpoints.set(type, payload); }
  async updateStatus(_id: string, status: JobStatus, message: string, progressPercent: number, error?: { code: string; message: string }): Promise<void> {
    this.current = { ...this.current, status, currentStep: status, progressPercent, errorCode: error?.code, errorMessage: error?.message };
    this.events.push({ id: this.events.length + 1, status, message, progressPercent, createdAt: new Date().toISOString() });
  }
  async saveQaReport(_id: string, qaLevel: string, passed: boolean, report: Record<string, unknown>): Promise<void> { this.qa = { qaLevel, passed, report }; }
  async setArtifact(_id: string, artifact: JobArtifact): Promise<void> { this.artifact = artifact; this.current = { ...this.current, artifact }; }
}

test("Harness persists the real preview manifest and reuses it on resume", async () => {
  const repository = new MemoryRepository();
  let generateCalls = 0;
  let downloadCalls = 0;
  const pptx = new Uint8Array([1, 2, 3, 4]);
  const runner = {
    async generate(_outline: Record<string, unknown>, _stylePreset: string): Promise<RunnerGeneration> {
      generateCalls += 1;
      throw new Error("Runner must not be called when a complete checkpoint exists");
    },
    async download(_relativePath: string): Promise<Uint8Array> {
      downloadCalls += 1;
      return pptx;
    },
  };
  const artifacts = {
    async save(_id: string, _fileName: string, content: Uint8Array) {
      return { sizeBytes: content.byteLength, sha256: crypto.createHash("sha256").update(content).digest("hex") };
    },
  };
  const templates = { async get(_templateId: string, _version: string): Promise<TemplateSpec> { return template; } };
  const service = new PresentationWorkflowService(
    config,
    repository as unknown as PgJobRepository,
    templates as unknown as TemplateRegistry,
    runner as unknown as PptSkillRunnerClient,
    artifacts as unknown as ControlledArtifactStore,
  );

  await service.process(jobId);

  assert.equal(generateCalls, 0);
  assert.equal(downloadCalls, 1);
  assert.equal(repository.current.status, "SUCCEEDED");
  assert.equal(repository.artifact?.preview?.rendered, true);
  assert.equal(repository.artifact?.preview?.slideCount, 3);
  assert.equal(repository.qa?.report.visualReviewImplemented, false);
  assert.equal(repository.qa?.qaLevel, "AUTOMATED_GEOMETRY_ONLY");
  assert.equal(repository.qa?.report.previewRenderingImplemented, true);
  assert.equal(repository.qa?.report.previewSlideCount, 3);
});

test("visual review provider unavailability fails explicitly even when previews exist", async () => {
  const repository = new MemoryRepository();
  const runner = {
    async generate(_outline: Record<string, unknown>, _stylePreset: string): Promise<RunnerGeneration> { throw new Error("not expected"); },
    async download(_relativePath: string): Promise<Uint8Array> { return new Uint8Array([1, 2, 3, 4]); },
  };
  const templates = { async get(_templateId: string, _version: string): Promise<TemplateSpec> { return template; } };
  const service = new PresentationWorkflowService(
    { ...config, visualReviewEnabled: true },
    repository as unknown as PgJobRepository,
    templates as unknown as TemplateRegistry,
    runner as unknown as PptSkillRunnerClient,
    { async save() { return { sizeBytes: 4, sha256: "" }; } } as unknown as ControlledArtifactStore,
    { async review() { throw new HarnessError("VISUAL_REVIEW_PROVIDER_UNAVAILABLE", "Vision provider unavailable", 503); } } as any,
  );

  await service.process(jobId);

  assert.equal(repository.current.status, "FAILED");
  assert.equal(repository.current.errorCode, "VISUAL_REVIEW_PROVIDER_UNAVAILABLE");
});

test("geometry pass plus visual pass produces the combined QA level", async () => {
  const repository = new MemoryRepository();
  const templates = { async get(_templateId: string, _version: string): Promise<TemplateSpec> { return template; } };
  const service = new PresentationWorkflowService(
    { ...config, visualReviewEnabled: true }, repository as unknown as PgJobRepository, templates as unknown as TemplateRegistry,
    { async generate() { throw new Error("not expected"); }, async download() { return new Uint8Array([1, 2, 3, 4]); } } as unknown as PptSkillRunnerClient,
    { async save(_id: string, _fileName: string, content: Uint8Array) { return { sizeBytes: content.byteLength, sha256: crypto.createHash("sha256").update(content).digest("hex") }; } } as unknown as ControlledArtifactStore,
    { async review() { return { jobId, passed: true, reviewedSlideCount: 3, issues: [] }; } } as any,
  );
  await service.process(jobId);
  assert.equal(repository.current.status, "SUCCEEDED");
  assert.equal(repository.artifact?.qaLevel, "AUTOMATED_GEOMETRY_AND_VISUAL");
  assert.equal(repository.qa?.report.visualReviewImplemented, true);
  assert.equal(repository.qa?.qaLevel, "AUTOMATED_GEOMETRY_AND_VISUAL");
});

test("geometry pass plus visual ERROR fails the overall job and leaves no artifact", async () => {
  const repository = new MemoryRepository();
  const templates = { async get(_templateId: string, _version: string): Promise<TemplateSpec> { return template; } };
  const service = new PresentationWorkflowService(
    { ...config, visualReviewEnabled: true }, repository as unknown as PgJobRepository, templates as unknown as TemplateRegistry,
    { async generate() { throw new Error("not expected"); }, async download() { return new Uint8Array([1, 2, 3, 4]); } } as unknown as PptSkillRunnerClient,
    { async save(_id: string, _fileName: string, content: Uint8Array) { return { sizeBytes: content.byteLength, sha256: crypto.createHash("sha256").update(content).digest("hex") }; } } as unknown as ControlledArtifactStore,
    { async review() { return { jobId, passed: false, reviewedSlideCount: 3, issues: [{ slideNumber: 2, code: "ELEMENT_OVERLAP", severity: "ERROR", confidence: 0.9, description: "Overlap", repairHint: "Separate elements" }] }; } } as any,
  );
  await service.process(jobId);
  assert.equal(repository.current.status, "FAILED");
  assert.equal(repository.current.errorCode, "VISUAL_QA_FAILED");
  assert.equal(repository.qa?.passed, false);
  assert.equal(repository.qa?.report.visualReviewImplemented, true);
  assert.equal(repository.artifact, undefined);
});
