import assert from "node:assert/strict";
import test from "node:test";
import { HarnessError } from "../src/domain.js";
import { VisualQaProvider, VisualQaSlideInput } from "../src/visual-qa.js";
import { GoldCase, evaluateGoldSet, loadGoldSet, runVarianceProbe } from "./visual-qa-calibration.js";

const goldCases: GoldCase[] = [
  { caseId: "clean", fileName: "clean.png", slideNumber: 1, expectedCodes: [], expectedMaxSeverity: "INFO", knownClean: true, notes: "clean" },
  { caseId: "broken", fileName: "broken.png", slideNumber: 1, expectedCodes: ["TEXT_OVERFLOW"], expectedMaxSeverity: "ERROR", knownClean: false, notes: "broken" },
];

function minimalPng(): Uint8Array {
  const bytes = Buffer.alloc(33, 0);
  Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]).copy(bytes, 0);
  bytes.writeUInt32BE(13, 8);
  Buffer.from("IHDR").copy(bytes, 12);
  bytes.writeUInt32BE(2, 16);
  bytes.writeUInt32BE(2, 20);
  return bytes;
}

function perfectProvider(goldCase: GoldCase): VisualQaProvider {
  return {
    async review(_input: VisualQaSlideInput) { return { slideNumber: 1, issues: goldCase.expectedCodes.map(code => ({ slideNumber: 1, code, severity: goldCase.expectedMaxSeverity, confidence: 1, description: "fixture issue", repairHint: "fixture hint" })) }; },
    async repair() { throw new Error("repair not expected"); },
  };
}

async function fixtureDir(): Promise<string> {
  const fs = await import("node:fs/promises");
  const path = await import("node:path");
  const root = await fs.mkdtemp(path.join(process.cwd(), "calibration-test-"));
  const bytes = minimalPng();
  await fs.writeFile(path.join(root, "clean.png"), bytes);
  await fs.writeFile(path.join(root, "broken.png"), bytes);
  return root;
}

test("Gold Set metadata requires clean/broken cases and all 13 codes", async () => {
  const fs = await import("node:fs/promises");
  const path = await import("node:path");
  const root = await fs.mkdtemp(path.join(process.cwd(), "gold-set-test-"));
  const allCodes = ["TEXT_OVERFLOW", "TEXT_TOO_SMALL", "ELEMENT_OVERLAP", "ELEMENT_CLIPPED", "UNBALANCED_LAYOUT", "EXCESSIVE_EMPTY_SPACE", "LOW_CONTRAST", "VISUAL_HIERARCHY_WEAK", "TABLE_UNREADABLE", "CHART_UNREADABLE", "FLOW_UNREADABLE", "DENSE_CONTENT", "BROKEN_RENDERING"];
  const cases = [{ caseId: "clean", fileName: "clean.png", slideNumber: 1, expectedCodes: [], expectedMaxSeverity: "INFO", knownClean: true, notes: "" }, ...allCodes.map((code, index) => ({ caseId: code, fileName: "clean.png", slideNumber: index + 1, expectedCodes: [code], expectedMaxSeverity: "WARNING", knownClean: false, notes: "" }))];
  const file = path.join(root, "gold.json");
  await fs.writeFile(file, JSON.stringify({ version: 1, cases }));
  assert.equal((await loadGoldSet(file)).length, 14);
});

test("calibration runner computes precision, recall, clean pass, and error detection", async () => {
  const report = await evaluateGoldSet(goldCases, await fixtureDir(), perfectProvider, "fake", "fake-model");
  assert.deepEqual(report.metrics, { issuePrecision: 1, issueRecall: 1, falsePositiveRate: 0, cleanSlidePassRate: 1, errorDetectionRate: 1, tp: 1, fp: 0, fn: 0 });
  assert.equal(report.totalRequests, 2);
  assert.deepEqual(report.autoRepairPolicy.autoRepairEligible, ["TEXT_OVERFLOW"]);
});

test("calibration runner records FP/FN and severity mismatch without changing Gold", async () => {
  const provider = (goldCase: GoldCase): VisualQaProvider => ({
    async review() { return { slideNumber: 1, issues: [{ slideNumber: 1, code: "ELEMENT_OVERLAP", severity: "WARNING", confidence: 0.7, description: "wrong prediction", repairHint: "review" }] }; },
    async repair() { throw new Error("not expected"); },
  });
  const report = await evaluateGoldSet(goldCases, await fixtureDir(), provider, "fake", "fake-model");
  assert.equal(report.metrics.tp, 0);
  assert.equal(report.metrics.fp, 2);
  assert.equal(report.metrics.fn, 1);
  assert.equal(report.falsePositiveCases.length, 2);
  assert.equal(report.falseNegativeCases.length, 1);
  assert.equal(report.severityMisclassifications.length, 0);
  assert.deepEqual(goldCases[1].expectedCodes, ["TEXT_OVERFLOW"]);
});

test("calibration runner allows one repair and counts requests deterministically", async () => {
  let reviews = 0;
  let repairs = 0;
  const provider = (_goldCase: GoldCase): VisualQaProvider => ({
    async review() { reviews += 1; return { invalid: true }; },
    async repair() { repairs += 1; return { slideNumber: 1, issues: [] }; },
  });
  const report = await evaluateGoldSet([goldCases[0]], await fixtureDir(), provider, "fake", "fake-model");
  assert.equal(reviews, 1);
  assert.equal(repairs, 1);
  assert.equal(report.totalRequests, 2);
  assert.equal(report.repairRequests, 1);
  assert.equal(report.cases[0].repaired, true);
});

test("variance probe stays separate and performs exactly the requested repeats", async () => {
  const report = await runVarianceProbe(goldCases, await fixtureDir(), perfectProvider, "fake", "fake-model", ["clean", "broken"], 3);
  assert.equal(report.totalRequests, 6);
  assert.equal(report.cases.length, 2);
  assert.equal(report.cases.every(item => item.codeSetStable), true);
  assert.equal(report.cases.every(item => item.severityStable), true);
  assert.deepEqual(report.cases[1].observations[0].predictedCodes, ["TEXT_OVERFLOW"]);
});
