import assert from "node:assert/strict";
import fs from "node:fs/promises";
import test from "node:test";
import {
  analyzeCalibrationReport,
  candidateSelection,
  candidateSystemPrompt,
  confidenceAnalysis,
  selectBaselineConfidencePolicy,
  simulateActionability,
} from "./visual-qa-precision-experiment.js";

async function baselineReport() {
  return JSON.parse(await fs.readFile("test/fixtures/visual-qa-gold/calibration-report.json", "utf8"));
}

test("precision experiment re-analyzes frozen baseline blocking metrics", async () => {
  const metrics = analyzeCalibrationReport(await baselineReport());
  assert.deepEqual(metrics, {
    rawPrecision: 0.1622,
    rawRecall: 0.9231,
    falsePositiveRate: 0.8571,
    cleanSlidePassRate: 0.1429,
    errorPrecision: 0.1905,
    errorRecall: 1,
    warningPrecision: 0.1212,
    warningRecall: 0.4444,
    infoPrecision: 0,
    infoRecall: 0,
    cleanHardFailRate: 0,
    brokenHardFailRecall: 0.8462,
    objective: { tp: 8, fp: 31, fn: 1, precision: 0.2051, recall: 0.8889 },
    subjective: { tp: 4, fp: 31, fn: 0, precision: 0.1143, recall: 1 },
    fpCount: 62,
    fnCount: 1,
  });
});

test("confidence analysis separates TP and FP by severity and group", async () => {
  const analysis = confidenceAnalysis(await baselineReport());
  assert.equal(analysis.all.tp.count, 13);
  assert.equal(analysis.all.fp.count, 62);
  assert.equal(analysis.bySeverity.ERROR.tp.count, 8);
  assert.equal(analysis.bySeverity.ERROR.fp.count, 13);
  assert.equal(analysis.byGroup.objective.tp.count, 9);
  assert.equal(analysis.byGroup.subjective.fp.count, 31);
});

test("baseline confidence policy preserves hard-fail and ERROR recall while removing clean issue noise", async () => {
  const report = await baselineReport();
  const selected = selectBaselineConfidencePolicy(report);
  assert.deepEqual(selected.policy, { kind: "OBJECTIVE_SUBJECTIVE", objectiveThreshold: 0.9, subjectiveThreshold: 0.95 });
  const selectedMetrics = selected.objectiveSubjective.find(item => item.policy.kind === "OBJECTIVE_SUBJECTIVE" && item.policy.objectiveThreshold === 0.9 && item.policy.subjectiveThreshold === 0.95);
  assert.equal(selectedMetrics?.cleanSlidePassRate, 1);
  assert.equal(selectedMetrics?.brokenHardFailRecall, 0.8462);
  assert.equal(selectedMetrics?.errorRecall, 1);
});

test("actionability simulation is evaluation-only and keeps subjective findings out of ACTIONABLE", async () => {
  const report = await baselineReport();
  const policy = selectBaselineConfidencePolicy(report).policy;
  const simulation = simulateActionability(report, policy);
  assert.equal(simulation.raw, 75);
  assert.equal(simulation.actionable > 0, true);
  assert.equal(simulation.byCode.EXCESSIVE_EMPTY_SPACE.ACTIONABLE, 0);
  assert.equal(simulation.byCode.EXCESSIVE_EMPTY_SPACE.REVIEW_ONLY > 0, true);
});

test("prompt selection refuses a candidate without strict clean hard-fail improvement", () => {
  const baseline = { cleanHardFailRate: 0, brokenHardFailRecall: 0.8462, errorRecall: 1, rawPrecision: 0.1622, rawRecall: 0.9231 } as any;
  const selection = candidateSelection(baseline, [
    { variant: "A", metrics: { ...baseline, rawPrecision: 0.4 } as any },
    { variant: "B", metrics: { ...baseline, rawPrecision: 0.5 } as any },
  ]);
  assert.equal(selection.recommendedPrompt, "NONE");
  assert.equal(selection.bestObservedPrompt, "B");
});

test("Candidate A and B change evaluation instructions only", () => {
  const a = candidateSystemPrompt("A", "REVIEW");
  const b = candidateSystemPrompt("B", "REVIEW");
  assert.match(a, /DEFECT-ONLY REVIEW/);
  assert.doesNotMatch(a, /EVIDENCE THRESHOLD/);
  assert.match(b, /EVIDENCE THRESHOLD/);
  assert.match(b, /concrete visible evidence/);
});
