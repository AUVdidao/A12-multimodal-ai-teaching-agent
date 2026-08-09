import assert from "node:assert/strict";
import test from "node:test";
import { HarnessError } from "../src/domain.js";
import { parseRequest } from "../src/server.js";

function snapshot(evidence: Array<Record<string, unknown>> = []) {
  return {
    project: { projectId: 7, projectName: "Photosynthesis" },
    requirementSummary: { subject: "Biology", topic: "Photosynthesis" },
    confirmedTeachingIntent: { generationGoals: ["Explain"] },
    confirmedGenerationPlan: { pptOutline: [{ order: 1, title: "Confirmed outline", points: ["Point"], materialReference: "biology.pdf" }] },
    materialEvidence: evidence,
    templateSelection: { templateId: "a12-teaching-generic", templateVersion: "1.0.0" },
    generationPreferences: { language: "zh-CN", style: "clear", density: "standard", targetSlideCount: 6 },
  };
}

test("Harness parses and retains the structured V2 job snapshot", () => {
  const parsed = parseRequest({
    requestId: "request-1", projectId: 7, templateId: "a12-teaching-generic", templateVersion: "1.0.0", locale: "zh-CN", targetSlideCount: 6,
    jobSnapshot: snapshot([{ materialId: 11, sourceName: "biology.pdf", chunkId: 12, text: "grounded evidence" }]),
  });

  assert.equal(parsed.jobSnapshot.confirmedGenerationPlan.pptOutline instanceof Array, true);
  assert.equal(parsed.jobSnapshot.materialEvidence[0].materialId, 11);
  assert.equal(parsed.jobSnapshot.materialEvidence[0].chunkId, 12);
});

test("Harness rejects an unbounded evidence payload before persistence", () => {
  const evidence = Array.from({ length: 21 }, (_, index) => ({ materialId: index + 1, sourceName: "book.pdf", text: "evidence" }));

  assert.throws(
    () => parseRequest({
      requestId: "request-2", projectId: 7, templateId: "a12-teaching-generic", templateVersion: "1.0.0", locale: "zh-CN", targetSlideCount: 6,
      jobSnapshot: snapshot(evidence),
    }),
    (error: unknown) => error instanceof HarnessError && error.code === "INVALID_REQUEST",
  );
});

test("Harness does not accept the legacy flat requirement snapshot as a job contract", () => {
  assert.throws(
    () => parseRequest({ requestId: "request-3", projectId: 7, templateId: "a12-teaching-generic", templateVersion: "1.0.0", locale: "zh-CN", targetSlideCount: 6, requirementSnapshot: { topic: "wrong" } }),
    (error: unknown) => error instanceof HarnessError && error.code === "INVALID_REQUEST",
  );
});
