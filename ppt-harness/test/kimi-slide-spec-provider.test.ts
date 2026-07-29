import assert from "node:assert/strict";
import test from "node:test";
import { HarnessConfig } from "../src/config.js";
import { PresentationJob, TemplateSpec } from "../src/domain.js";
import { KimiSlideSpecProvider } from "../src/slide-spec-provider.js";

const template: TemplateSpec = {
  templateId: "a12-teaching-generic",
  version: "1.0.0",
  name: "通用教学模板",
  locale: "zh-CN",
  previewRef: "template://a12-teaching-generic/1.0.0",
  stylePreset: "forest-research",
  layouts: [{ layoutId: "cover", slots: ["subtitle"], capacity: { title: 80, subtitle: 120 } }],
};

const job: PresentationJob = {
  id: "job-1", requestId: "request-1", projectId: 1, status: "GENERATING_SLIDE_SPEC",
  templateId: template.templateId, templateVersion: template.version, locale: "zh-CN", targetSlideCount: 1,
  progressPercent: 25, attemptCount: 1, requirementSnapshot: { courseName: "生物", chapterTopic: "光合作用" },
  createdAt: "2026-07-27T00:00:00Z", updatedAt: "2026-07-27T00:00:00Z",
};

const config: HarnessConfig = {
  port: 8091, host: "127.0.0.1", databaseUrl: "postgres://unused", runnerBaseUrl: "http://runner",
  runnerTimeoutMs: 1000, generationSource: "KIMI", visualReviewEnabled: false, eventPollIntervalMs: 750,
  artifactRetentionDays: 7, maxRepairAttempts: 1, kimiApiKey: "test-key", kimiBaseUrl: "https://kimi.example/v1",
  kimiModel: "kimi-k3", kimiTimeoutMs: 1000,
};

test("Kimi K3 provider sends a compatible JSON-object request and parses SlideSpec", async () => {
  const originalFetch = globalThis.fetch;
  let request: RequestInit | undefined;
  globalThis.fetch = async (_url, init) => {
    request = init;
    return new Response(JSON.stringify({
      choices: [{ message: { content: JSON.stringify({
        deckTitle: "生物：光合作用", locale: "zh-CN", templateId: template.templateId, templateVersion: template.version,
        slides: [{ slideId: "slide-1", layoutId: "cover", title: "光合作用", visualStrategy: "原生图形", slots: { subtitle: "教学课件" } }],
      }) } }],
    }), { status: 200, headers: { "Content-Type": "application/json" } });
  };
  try {
    const spec = await new KimiSlideSpecProvider(config).create(job, template);
    assert.equal(spec.slides[0].title, "光合作用");
    const body = JSON.parse(String(request?.body)) as { thinking?: { type: string }; reasoning_effort?: string; temperature?: number; response_format: { type: string } };
    assert.equal(body.thinking, undefined);
    assert.equal(body.reasoning_effort, "low");
    assert.equal(body.temperature, 1);
    assert.equal(body.response_format.type, "json_object");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("Kimi K2.6 provider omits unsupported thinking and temperature settings", async () => {
  const originalFetch = globalThis.fetch;
  let request: RequestInit | undefined;
  globalThis.fetch = async (_url, init) => {
    request = init;
    return new Response(JSON.stringify({ choices: [{ message: { content: JSON.stringify({
      deckTitle: "test", locale: "zh-CN", templateId: template.templateId, templateVersion: template.version,
      slides: [{ slideId: "slide-1", layoutId: "cover", title: "test", visualStrategy: "shape", slots: { subtitle: "test" } }],
    }) } }] }), { status: 200 });
  };
  try {
    await new KimiSlideSpecProvider({ ...config, kimiModel: "kimi-k2.6" }).create(job, template);
    const body = JSON.parse(String(request?.body)) as { thinking?: { type: string }; reasoning_effort?: string; temperature?: number };
    assert.equal(body.thinking, undefined);
    assert.equal(body.reasoning_effort, undefined);
    assert.equal(body.temperature, undefined);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("Kimi provider normalizes known presentation aliases without accepting an unknown layout", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => new Response(JSON.stringify({ choices: [{ message: { content: JSON.stringify({
    deckTitle: "光合作用", slides: [{
      slideId: 1, layout: "cover", title: "光合作用", subtitle: "八年级生物",
      visualStrategy: { type: "hero-image", description: "叶片和阳光" },
    }],
  }) } }] }), { status: 200 });
  try {
    const spec = await new KimiSlideSpecProvider(config).create(job, template);
    assert.equal(spec.slides[0].slideId, "1");
    assert.equal(spec.slides[0].layoutId, "cover");
    assert.equal(spec.slides[0].slots.subtitle, "八年级生物");
    assert.match(spec.slides[0].visualStrategy, /hero-image/);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("Kimi provider rejects non-JSON response content", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => new Response(JSON.stringify({ choices: [{ message: { content: "not-json" } }] }), { status: 200 });
  try {
    await assert.rejects(() => new KimiSlideSpecProvider(config).create(job, template), { message: "Kimi response was not valid SlideSpec JSON" });
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("Kimi provider fails safely before making a request when server credentials are absent", async () => {
  const originalFetch = globalThis.fetch;
  let called = false;
  globalThis.fetch = async () => {
    called = true;
    throw new Error("network must not be reached");
  };
  try {
    const withoutKey = { ...config, kimiApiKey: undefined };
    await assert.rejects(
      () => new KimiSlideSpecProvider(withoutKey).create(job, template),
      { message: "Kimi SlideSpec generation requires a server-side MOONSHOT_API_KEY" },
    );
    assert.equal(called, false);
  } finally {
    globalThis.fetch = originalFetch;
  }
});
