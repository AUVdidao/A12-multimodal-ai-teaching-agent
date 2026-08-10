import assert from "node:assert/strict";
import test from "node:test";
import { VisualIssueCode, VisualQaIssue } from "../src/visual-qa.js";
import { classifyGeneralization, HoldoutCaseResult, summarizeHoldoutCases, validateHoldoutGold } from "./visual-qa-holdout.js";

function caseResult(caseId: string, knownClean: boolean, expectedCodes: VisualIssueCode[], predictedIssues: VisualQaIssue[], expectedMaxSeverity: "INFO" | "WARNING" | "ERROR" = "ERROR"): HoldoutCaseResult {
  return { caseId, fileName: `${caseId}.png`, knownClean, expectedCodes, predictedCodes: predictedIssues.map(issue => issue.code), predictedIssues, expectedMaxSeverity, predictedMaxSeverity: predictedIssues[0]?.severity, outcome: "SUCCESS", repaired: false };
}

const issue = (code: VisualIssueCode, severity: "INFO" | "WARNING" | "ERROR" = "ERROR", confidence = 0.99): VisualQaIssue => ({ slideNumber: 1, code, severity, confidence, description: "visible defect", repairHint: "concrete repair" });

test("holdout gold validation requires 12 clean, 18 broken, every code, and four multi-defect cases", () => {
  const allCodes: VisualIssueCode[] = ["TEXT_OVERFLOW", "TEXT_TOO_SMALL", "ELEMENT_OVERLAP", "ELEMENT_CLIPPED", "LOW_CONTRAST", "TABLE_UNREADABLE", "CHART_UNREADABLE", "FLOW_UNREADABLE", "BROKEN_RENDERING", "UNBALANCED_LAYOUT", "EXCESSIVE_EMPTY_SPACE", "VISUAL_HIERARCHY_WEAK", "DENSE_CONTENT"];
  const cases: any[] = Array.from({ length: 12 }, (_, index) => ({ caseId: `clean-${index}`, fileName: `clean-${index}.png`, slideNumber: 1, slideType: "chart", knownClean: true, expectedCodes: [], expectedMaxSeverity: "INFO", notes: "clean", sourceFixture: "fixture", mutationDescription: "none", sourceSlideNumber: 1 }));
  allCodes.forEach((code, index) => cases.push({ caseId: `broken-${index}`, fileName: `broken-${index}.png`, slideNumber: 1, slideType: "broken", knownClean: false, expectedCodes: [code, ...(index < 4 ? [allCodes[(index + 1) % allCodes.length]] : [])], expectedMaxSeverity: "ERROR", notes: "broken", sourceFixture: "fixture", mutationDescription: "mutation", sourceSlideNumber: 1 }));
  for (let index = 0; index < 5; index += 1) cases.push({ caseId: `broken-extra-${index}`, fileName: `broken-extra-${index}.png`, slideNumber: 1, slideType: "broken", knownClean: false, expectedCodes: ["TEXT_OVERFLOW"], expectedMaxSeverity: "ERROR", notes: "broken", sourceFixture: "fixture", mutationDescription: "mutation", sourceSlideNumber: 1 });
  assert.equal(validateHoldoutGold(cases as any).length, 30);
});

test("holdout metrics count multi-defect per-code TP/FP/FN and hard-fail rates", () => {
  const cases = [
    caseResult("clean-chart", true, [], []),
    caseResult("clean-table", true, [], [issue("TABLE_UNREADABLE", "WARNING")], "INFO"),
    caseResult("broken", false, ["TEXT_OVERFLOW", "ELEMENT_OVERLAP"], [issue("TEXT_OVERFLOW"), issue("LOW_CONTRAST")]),
  ];
  const metrics = summarizeHoldoutCases(cases);
  assert.equal(metrics.perCode.TEXT_OVERFLOW.tp, 1);
  assert.equal(metrics.perCode.ELEMENT_OVERLAP.fn, 1);
  assert.equal(metrics.perCode.LOW_CONTRAST.fp, 1);
  assert.equal(metrics.cleanHardFailRate, 0);
  assert.equal(metrics.brokenHardFailRecall, 1);
});

test("actionability remains frozen at objective .90 and subjective .95", () => {
  const metrics = summarizeHoldoutCases([
    caseResult("clean", true, [], [issue("TEXT_OVERFLOW", "ERROR", 0.95)]),
    caseResult("broken", false, ["TEXT_OVERFLOW"], [issue("TEXT_OVERFLOW", "ERROR", 0.89)]),
  ]);
  assert.equal(metrics.actionability.predicted, 1);
  assert.equal(metrics.actionability.truePositive, 0);
  assert.equal(metrics.actionability.fpCount, 1);
});

test("generalization refuses automatic repair even when all evaluation criteria pass", () => {
  const base = summarizeHoldoutCases([caseResult("clean", true, [], []), caseResult("broken", false, ["TEXT_OVERFLOW"], [issue("TEXT_OVERFLOW")])]);
  const candidate = summarizeHoldoutCases([caseResult("clean", true, [], []), caseResult("broken", false, ["TEXT_OVERFLOW"], [issue("TEXT_OVERFLOW")])]);
  const result = classifyGeneralization(base, candidate);
  assert.equal(result.conclusion, "DOES_NOT_GENERALIZE");
  assert.equal(result.autoRepairEligible, false);
});
