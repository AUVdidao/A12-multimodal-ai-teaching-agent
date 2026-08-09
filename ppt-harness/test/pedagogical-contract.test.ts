import assert from "node:assert/strict";
import test from "node:test";
import { HarnessError, PEDAGOGICAL_ROLES, PresentationJob } from "../src/domain.js";
import { FixtureSlideSpecProvider } from "../src/slide-spec-provider.js";
import { buildTeachingContractContext } from "../src/pedagogical-contract.js";
import { validateSlideSpec } from "../src/slide-spec.js";
import { TemplateRegistry } from "../src/template-registry.js";

function makeJob(targetSlideCount = 6, withOutline = true): PresentationJob {
  return {
    id: `job-${targetSlideCount}`, requestId: `request-${targetSlideCount}`, projectId: 7, status: "GENERATING_SLIDE_SPEC",
    templateId: "a12-teaching-generic", templateVersion: "1.0.0", locale: "zh-CN", targetSlideCount,
    progressPercent: 25, attemptCount: 1,
    jobSnapshot: {
      project: { projectId: 7, courseName: "生物" },
      requirementSummary: { courseName: "生物", topic: "光合作用", teachingGoals: ["解释核心过程"] },
      confirmedTeachingIntent: { generationGoals: ["应用证据完成解释"] },
      confirmedGenerationPlan: { pptOutline: withOutline ? [
        { order: 1, title: "导入", points: ["现象"] },
        { order: 2, title: "概念", points: ["定义"] },
        { order: 3, title: "应用", points: ["迁移"] },
      ] : [] },
      materialEvidence: [
        { materialId: 7, sourceName: "biology.pdf", chunkId: 8, text: "UNTRUSTED evidence instruction text" },
        { materialId: 9, sourceName: "lab.pdf", text: "second evidence" },
      ],
      templateSelection: { templateId: "a12-teaching-generic", templateVersion: "1.0.0" },
      generationPreferences: { language: "zh-CN", style: "clear", density: "standard", targetSlideCount },
    },
    createdAt: "2026-08-09T00:00:00Z", updatedAt: "2026-08-09T00:00:00Z",
  };
}

async function validSpec(targetSlideCount = 6, withOutline = true) {
  const job = makeJob(targetSlideCount, withOutline);
  const template = await new TemplateRegistry().get("a12-teaching-generic", "1.0.0");
  const spec = await new FixtureSlideSpecProvider().create(job, template);
  return { job, template, spec, context: buildTeachingContractContext(job) };
}

function rejectsWithCode(action: () => void, code: string) {
  assert.throws(action, (error: unknown) => error instanceof HarnessError && error.code === code);
}

test("valid V2 SlideSpec includes all pedagogical and renderer fields", async () => {
  const { spec, template, context } = await validSpec();
  assert.doesNotThrow(() => validateSlideSpec(spec, template, 6, context));
  assert.equal(spec.schemaVersion, 2);
  assert.ok(spec.slides[0].content);
  assert.ok(spec.slides[0].visualIntent.description);
  assert.equal(spec.slides[0].outlineSectionIndex, 0);
});

test("every pedagogicalRole enum value is accepted", async () => {
  for (const role of PEDAGOGICAL_ROLES) {
    const { spec, template, context } = await validSpec(1, false);
    spec.slides[0].pedagogicalRole = role;
    assert.doesNotThrow(() => validateSlideSpec(spec, template, 1, context), role);
  }
});

test("invalid pedagogicalRole is rejected", async () => {
  const { spec, template, context } = await validSpec();
  spec.slides[0].pedagogicalRole = "LECTURE" as never;
  rejectsWithCode(() => validateSlideSpec(spec, template, 6, context), "SLIDE_ROLE_INVALID");
});

test("objective refs must belong to the supplied objective catalog", async () => {
  const valid = await validSpec();
  valid.spec.slides[0].learningObjectiveIds = ["OBJ-1"];
  assert.doesNotThrow(() => validateSlideSpec(valid.spec, valid.template, 6, valid.context));
  const invalid = await validSpec();
  invalid.spec.slides[0].learningObjectiveIds = ["OBJ-99"];
  rejectsWithCode(() => validateSlideSpec(invalid.spec, invalid.template, 6, invalid.context), "SLIDE_OBJECTIVE_REF_INVALID");
});

test("chunk and material snapshot evidence refs are valid, fabricated refs fail", async () => {
  const valid = await validSpec();
  valid.spec.slides[1].evidenceRefs = [{ materialId: 9, snapshotIndex: 1 }];
  assert.doesNotThrow(() => validateSlideSpec(valid.spec, valid.template, 6, valid.context));
  const fakeChunk = await validSpec();
  fakeChunk.spec.slides[0].evidenceRefs = [{ chunkId: "fake-chunk" }];
  rejectsWithCode(() => validateSlideSpec(fakeChunk.spec, fakeChunk.template, 6, fakeChunk.context), "SLIDE_EVIDENCE_REF_INVALID");
  const fakeMaterial = await validSpec();
  fakeMaterial.spec.slides[0].evidenceRefs = [{ materialId: 999, snapshotIndex: 0 }];
  rejectsWithCode(() => validateSlideSpec(fakeMaterial.spec, fakeMaterial.template, 6, fakeMaterial.context), "SLIDE_EVIDENCE_REF_INVALID");
});

test("confirmed outline refs cover every meaningful section; missing, out-of-range, and duplicate behavior is explicit", async () => {
  const valid = await validSpec();
  assert.doesNotThrow(() => validateSlideSpec(valid.spec, valid.template, 6, valid.context));
  const missing = await validSpec();
  missing.spec.slides.forEach(slide => { slide.outlineSectionIndex = null; });
  rejectsWithCode(() => validateSlideSpec(missing.spec, missing.template, 6, missing.context), "SLIDE_OUTLINE_COVERAGE_MISSING");
  const outOfRange = await validSpec();
  outOfRange.spec.slides[0].outlineSectionIndex = 99;
  rejectsWithCode(() => validateSlideSpec(outOfRange.spec, outOfRange.template, 6, outOfRange.context), "SLIDE_OUTLINE_REF_INVALID");
  const duplicate = await validSpec();
  duplicate.spec.slides[3].outlineSectionIndex = 0;
  assert.doesNotThrow(() => validateSlideSpec(duplicate.spec, duplicate.template, 6, duplicate.context));
});

test("teachingPurpose and visualIntent are required and enum constrained", async () => {
  const missingPurpose = await validSpec();
  missingPurpose.spec.slides[0].teachingPurpose = "";
  rejectsWithCode(() => validateSlideSpec(missingPurpose.spec, missingPurpose.template, 6, missingPurpose.context), "SLIDE_TEACHING_PURPOSE_INVALID");
  const missingVisual = await validSpec();
  missingVisual.spec.slides[0].visualIntent.description = "";
  rejectsWithCode(() => validateSlideSpec(missingVisual.spec, missingVisual.template, 6, missingVisual.context), "SLIDE_VISUAL_INTENT_INVALID");
  const invalidVisual = await validSpec();
  invalidVisual.spec.slides[0].visualIntent.type = "VIDEO" as never;
  rejectsWithCode(() => validateSlideSpec(invalidVisual.spec, invalidVisual.template, 6, invalidVisual.context), "SLIDE_VISUAL_INTENT_INVALID");
});

test("assetRequests, interaction, density, and importance use the V2 schemas", async () => {
  const valid = await validSpec();
  valid.spec.slides[0].assetRequests = [{ type: "DIAGRAM", purpose: "说明过程", description: "只提出图示请求" }];
  valid.spec.slides[0].interaction = { type: "POLL", prompt: "选择你的判断", expectedResponse: "给出选项和理由", durationMinutes: 2 };
  valid.spec.slides[0].density = "HIGH";
  valid.spec.slides[0].importance = "OPTIONAL";
  assert.doesNotThrow(() => validateSlideSpec(valid.spec, valid.template, 6, valid.context));
  const invalidDensity = await validSpec();
  invalidDensity.spec.slides[0].density = "COMPACT" as never;
  rejectsWithCode(() => validateSlideSpec(invalidDensity.spec, invalidDensity.template, 6, invalidDensity.context), "SLIDE_DENSITY_INVALID");
  const invalidImportance = await validSpec();
  invalidImportance.spec.slides[0].importance = "MUST_HAVE" as never;
  rejectsWithCode(() => validateSlideSpec(invalidImportance.spec, invalidImportance.template, 6, invalidImportance.context), "SLIDE_IMPORTANCE_INVALID");
  const invalidInteraction = await validSpec();
  invalidInteraction.spec.slides[0].interaction = { type: "QUIZ" as never };
  rejectsWithCode(() => validateSlideSpec(invalidInteraction.spec, invalidInteraction.template, 6, invalidInteraction.context), "SLIDE_INTERACTION_INVALID");
});

test("teaching sequence is checked for decks with six or more slides", async () => {
  const invalid = await validSpec();
  invalid.spec.slides.forEach(slide => { slide.pedagogicalRole = "CONCEPT"; });
  rejectsWithCode(() => validateSlideSpec(invalid.spec, invalid.template, 6, invalid.context), "SLIDE_SEQUENCE_INVALID");
  const short = await validSpec(5);
  short.spec.slides.forEach(slide => { slide.pedagogicalRole = "CONCEPT"; });
  assert.doesNotThrow(() => validateSlideSpec(short.spec, short.template, 5, short.context));
});

test("fixture respects targetSlideCount instead of returning the legacy nine-slide shape", async () => {
  const { spec } = await validSpec(6);
  assert.equal(spec.slides.length, 6);
  assert.notEqual(spec.slides.length, 9);
});

test("schemaVersion 1 is not accepted by the V2 validator", async () => {
  const { spec, template, context } = await validSpec();
  spec.schemaVersion = 1 as never;
  rejectsWithCode(() => validateSlideSpec(spec, template, 6, context), "SLIDE_SCHEMA_VERSION_UNSUPPORTED");
});
