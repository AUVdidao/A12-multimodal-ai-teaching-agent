import assert from "node:assert/strict";
import test from "node:test";
import { HarnessError, PresentationJob } from "../src/domain.js";
import { FixtureSlideSpecProvider } from "../src/slide-spec-provider.js";
import { validateSlideSpec } from "../src/slide-spec.js";
import { TemplateRegistry } from "../src/template-registry.js";
import { toRunnerOutline } from "../src/runner-outline-adapter.js";
import { toPublicQaReport } from "../src/qa-report.js";

const job: PresentationJob = {
  id: "6a9745b7-1d1d-4c29-83cf-a2d44a059237", requestId: "fixture-test-001", projectId: 113,
  status: "QUEUED", progressPercent: 0, attemptCount: 0, templateId: "a12-teaching-generic", templateVersion: "1.0.0", locale: "zh-CN", targetSlideCount: 9,
  requirementSnapshot: { courseName: "八年级生物", chapterTopic: "光合作用" }, createdAt: new Date().toISOString(), updatedAt: new Date().toISOString()
};

test("fixture SlideSpec validates against the fixed teaching template", async () => {
  const template = await new TemplateRegistry().get("a12-teaching-generic", "1.0.0");
  const spec = await new FixtureSlideSpecProvider().create(job, template);
  validateSlideSpec(spec, template, job.targetSlideCount);
  const outline = toRunnerOutline(spec);
  assert.equal((outline.slides as unknown[]).length, 9);
});

test("template registry exposes the generic and MIT editorial templates", async () => {
  const templates = await new TemplateRegistry().list();
  assert.deepEqual(templates.map(template => template.templateId), ["a12-teaching-generic", "a12-editorial-grid"]);
  assert.equal(templates[0].stylePreset, "forest-research");
  assert.equal(templates[1].stylePreset, "a12-editorial-grid");
});

test("runner outline groups summary and assignment content within card limits", async () => {
  const template = await new TemplateRegistry().get("a12-teaching-generic", "1.0.0");
  const spec = await new FixtureSlideSpecProvider().create(job, template);
  const summaryItems = ["总结一", "总结二", "总结三", "总结四", "总结五"];
  const assignmentItems = ["任务一", "任务二", "任务三", "任务四"];
  const summarySlide = spec.slides.find(slide => slide.layoutId === "summary");
  const assignmentSlide = spec.slides.find(slide => slide.layoutId === "assignment");
  assert.ok(summarySlide);
  assert.ok(assignmentSlide);
  summarySlide.slots.takeaways = summaryItems;
  assignmentSlide.slots.tasks = assignmentItems;

  const slides = toRunnerOutline(spec).slides as Array<{ cards?: Array<{ body: string }> }>;
  const summaryCards = slides[spec.slides.indexOf(summarySlide)].cards;
  const assignmentCards = slides[spec.slides.indexOf(assignmentSlide)].cards;
  assert.equal(summaryCards?.length, 3);
  assert.equal(assignmentCards?.length, 2);
  assert.deepEqual(summaryCards?.flatMap(card => card.body.split("\n")), summaryItems);
  assert.deepEqual(assignmentCards?.flatMap(card => card.body.split("\n")), assignmentItems);
});

test("SlideSpec rejects unsupported layouts", async () => {
  const template = await new TemplateRegistry().get("a12-teaching-generic", "1.0.0");
  const spec = await new FixtureSlideSpecProvider().create(job, template);
  spec.slides[1].layoutId = "unsupported-layout";
  assert.throws(() => validateSlideSpec(spec, template, job.targetSlideCount), HarnessError);
});

test("SlideSpec rejects placeholder content", async () => {
  const template = await new TemplateRegistry().get("a12-teaching-generic", "1.0.0");
  const spec = await new FixtureSlideSpecProvider().create(job, template);
  spec.slides[1].title = "TODO";
  assert.throws(() => validateSlideSpec(spec, template, job.targetSlideCount), HarnessError);
});

test("public QA report excludes runner filesystem paths", () => {
  const report = toPublicQaReport("AUTOMATED_GEOMETRY_ONLY", true, {
    input: "/tmp/a12-ppt-skill-runner/secret/presentation.pptx",
    design_report: "/tmp/a12-ppt-skill-runner/secret/qa/design_rules.json",
    expected_slide_count: 9,
    geometry_error_count: 0,
    geometry_warning_count: 1,
    renderSkipped: true,
    manualReviewSkipped: true,
    geometry_violations: [{ type: "empty_ratio_too_high", severity: "warning", slide_index: 2, suggested_fix: "Add a visual." }],
  });
  const serialized = JSON.stringify(report);
  assert.equal(serialized.includes("/tmp/"), false);
  assert.equal(report.summary.expectedSlideCount, 9);
  assert.equal(report.limitations.visualReviewImplemented, false);
});
