import crypto from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { loadConfig } from "../src/config.js";
import { HarnessError, Slide } from "../src/domain.js";
import {
  KimiVisualQaProvider,
  VISUAL_ISSUE_CODES,
  VisualIssueCode,
  VisualIssueSeverity,
  VisualQaIssue,
  VisualQaProvider,
  VisualQaSlideInput,
  parseSlideResult,
  verifyPng,
} from "../src/visual-qa.js";

export type GoldCase = {
  caseId: string;
  fileName: string;
  slideNumber: number;
  expectedCodes: VisualIssueCode[];
  expectedMaxSeverity: VisualIssueSeverity;
  knownClean: boolean;
  notes: string;
};

export type CalibrationCaseResult = {
  caseId: string;
  slideNumber: number;
  knownClean: boolean;
  expectedCodes: VisualIssueCode[];
  predictedCodes: VisualIssueCode[];
  predictedIssues: VisualQaIssue[];
  expectedMaxSeverity: VisualIssueSeverity;
  predictedMaxSeverity?: VisualIssueSeverity;
  outcome: "SUCCESS" | "PROVIDER_ERROR" | "INVALID_RESULT";
  repaired: boolean;
  errorCode?: string;
  errorMessage?: string;
};

export type CalibrationReport = {
  version: 1;
  generatedAt: string;
  provider: string;
  model: string;
  caseCount: number;
  cleanCount: number;
  brokenCount: number;
  totalRequests: number;
  repairRequests: number;
  elapsedMs: number;
  metrics: {
    issuePrecision: number;
    issueRecall: number;
    falsePositiveRate: number;
    cleanSlidePassRate: number;
    errorDetectionRate: number;
    tp: number;
    fp: number;
    fn: number;
  };
  perCode: Record<VisualIssueCode, { tp: number; fp: number; fn: number; precision: number; recall: number; support: number }>;
  falsePositiveCases: Array<{ caseId: string; slideNumber: number; predictedCodes: VisualIssueCode[] }>;
  falseNegativeCases: Array<{ caseId: string; slideNumber: number; missingCodes: VisualIssueCode[] }>;
  severityMisclassifications: Array<{ caseId: string; code: VisualIssueCode; expected: VisualIssueSeverity; predicted: VisualIssueSeverity }>;
  autoRepairPolicy: { precisionThreshold: number; recallThreshold: number; autoRepairEligible: VisualIssueCode[]; humanReviewRecommended: VisualIssueCode[]; informationalOnly: VisualIssueCode[] };
  cases: CalibrationCaseResult[];
};

export type VarianceProbeObservation = {
  repeat: number;
  predictedCodes: VisualIssueCode[];
  predictedMaxSeverity?: VisualIssueSeverity;
  outcome: "SUCCESS" | "PROVIDER_ERROR" | "INVALID_RESULT";
  repaired: boolean;
  errorCode?: string;
};

export type VarianceProbeReport = {
  version: 1;
  generatedAt: string;
  provider: string;
  model: string;
  repeatsPerCase: number;
  totalRequests: number;
  elapsedMs: number;
  cases: Array<{
    caseId: string;
    knownClean: boolean;
    expectedCodes: VisualIssueCode[];
    observations: VarianceProbeObservation[];
    codeSetStable: boolean;
    severityStable: boolean;
  }>;
};

type CalibrationProviderFactory = (goldCase: GoldCase) => VisualQaProvider;

const SEVERITY_RANK: Record<VisualIssueSeverity, number> = { INFO: 1, WARNING: 2, ERROR: 3 };
const PRECISION_THRESHOLD = 0.9;
const RECALL_THRESHOLD = 0.8;

export async function loadGoldSet(goldSetPath: string): Promise<GoldCase[]> {
  const raw = JSON.parse(await fs.readFile(goldSetPath, "utf8")) as { version?: unknown; cases?: unknown };
  if (raw.version !== 1 || !Array.isArray(raw.cases) || raw.cases.length < 12 || raw.cases.length > 20) {
    throw new HarnessError("CALIBRATION_GOLD_INVALID", "Gold Set must contain 12-20 cases and version 1 metadata", 422);
  }
  const cases = raw.cases.map(parseGoldCase);
  const cleanCount = cases.filter(item => item.knownClean).length;
  const brokenCount = cases.length - cleanCount;
  if (!cleanCount || !brokenCount) throw new HarnessError("CALIBRATION_GOLD_INVALID", "Gold Set must contain clean and intentionally broken cases", 422);
  const missingCodes = VISUAL_ISSUE_CODES.filter(code => !cases.some(item => item.expectedCodes.includes(code)));
  if (missingCodes.length) throw new HarnessError("CALIBRATION_GOLD_INVALID", `Gold Set is missing issue codes: ${missingCodes.join(", ")}`, 422);
  return cases;
}

export async function evaluateGoldSet(
  goldCases: GoldCase[],
  fixtureDir: string,
  providerFactory: CalibrationProviderFactory,
  providerName: string,
  model: string,
): Promise<CalibrationReport> {
  const started = Date.now();
  let totalRequests = 0;
  let repairRequests = 0;
  const results: CalibrationCaseResult[] = [];

  for (const goldCase of goldCases) {
    const bytes = await fs.readFile(path.join(fixtureDir, goldCase.fileName));
    const dimensions = pngDimensions(bytes);
    const sha256 = crypto.createHash("sha256").update(bytes).digest("hex");
    verifyPng(bytes, bytes.byteLength, sha256, dimensions.width, dimensions.height, goldCase.slideNumber);
    const input = calibrationInput(goldCase, bytes);
    const provider = providerFactory(goldCase);
    let repaired = false;
    let raw: unknown;
    try {
      totalRequests += 1;
      raw = await provider.review(input);
    } catch (error) {
      results.push(failedCase(goldCase, "PROVIDER_ERROR", error));
      continue;
    }
    let parsed = parseSlideResult(raw, goldCase.slideNumber);
    if (!parsed.result) {
      repaired = true;
      repairRequests += 1;
      totalRequests += 1;
      try {
        raw = await provider.repair(input, raw, parsed.reason);
      } catch (error) {
        results.push({ ...failedCase(goldCase, "PROVIDER_ERROR", error), repaired });
        continue;
      }
      parsed = parseSlideResult(raw, goldCase.slideNumber);
    }
    if (!parsed.result) {
      results.push({ ...failedCase(goldCase, "INVALID_RESULT", new HarnessError("CALIBRATION_INVALID_RESULT", "Visual QA result failed local schema validation", 502)), repaired });
      continue;
    }
    const predictedIssues = parsed.result.issues;
    results.push({
      caseId: goldCase.caseId,
      slideNumber: goldCase.slideNumber,
      knownClean: goldCase.knownClean,
      expectedCodes: goldCase.expectedCodes,
      predictedCodes: uniqueCodes(predictedIssues),
      predictedIssues,
      expectedMaxSeverity: goldCase.expectedMaxSeverity,
      predictedMaxSeverity: maxSeverity(predictedIssues),
      outcome: "SUCCESS",
      repaired,
    });
  }

  return buildReport(results, totalRequests, repairRequests, Date.now() - started, providerName, model);
}

export function calibrationInput(goldCase: GoldCase, bytes: Uint8Array): VisualQaSlideInput {
  const expected: VisualQaSlideInput["expected"] = {
    title: "Visual QA calibration slide",
    pedagogicalRole: "CONCEPT" as Slide["pedagogicalRole"],
    teachingPurpose: "Inspect visible presentation quality only",
    visualIntent: { type: "MIXED", description: "Assess visible structure, readability, and rendering" },
    layoutIntent: "Evaluate visible layout only",
    density: "MEDIUM",
    importance: "CORE",
  };
  return { slideNumber: goldCase.slideNumber, imageDataUrl: `data:image/png;base64,${Buffer.from(bytes).toString("base64")}`, expected };
}

function buildReport(results: CalibrationCaseResult[], totalRequests: number, repairRequests: number, elapsedMs: number, provider: string, model: string): CalibrationReport {
  const perCode = Object.fromEntries(VISUAL_ISSUE_CODES.map(code => [code, { tp: 0, fp: 0, fn: 0, precision: 0, recall: 0, support: 0 }])) as CalibrationReport["perCode"];
  const falsePositiveCases: CalibrationReport["falsePositiveCases"] = [];
  const falseNegativeCases: CalibrationReport["falseNegativeCases"] = [];
  const severityMisclassifications: CalibrationReport["severityMisclassifications"] = [];
  let tp = 0;
  let fp = 0;
  let fn = 0;
  for (const result of results) {
    const expected = new Set(result.expectedCodes);
    const predicted = new Set(result.predictedCodes);
    const extra = result.predictedCodes.filter(code => !expected.has(code));
    const missing = result.expectedCodes.filter(code => !predicted.has(code));
    if (extra.length) falsePositiveCases.push({ caseId: result.caseId, slideNumber: result.slideNumber, predictedCodes: extra });
    if (missing.length) falseNegativeCases.push({ caseId: result.caseId, slideNumber: result.slideNumber, missingCodes: missing });
    for (const code of VISUAL_ISSUE_CODES) {
      const expectedCode = expected.has(code);
      const predictedCode = predicted.has(code);
      if (expectedCode) perCode[code].support += 1;
      if (expectedCode && predictedCode) { perCode[code].tp += 1; tp += 1; }
      if (!expectedCode && predictedCode) { perCode[code].fp += 1; fp += 1; }
      if (expectedCode && !predictedCode) { perCode[code].fn += 1; fn += 1; }
    }
    if (result.outcome === "SUCCESS" && result.predictedMaxSeverity && result.predictedIssues.length) {
      for (const code of result.expectedCodes) {
        const predictedIssue = result.predictedIssues.find(issue => issue.code === code);
        if (predictedIssue && predictedIssue.severity !== result.expectedMaxSeverity) {
          severityMisclassifications.push({ caseId: result.caseId, code, expected: result.expectedMaxSeverity, predicted: predictedIssue.severity });
        }
      }
    }
  }
  for (const code of VISUAL_ISSUE_CODES) {
    perCode[code].precision = ratio(perCode[code].tp, perCode[code].tp + perCode[code].fp);
    perCode[code].recall = ratio(perCode[code].tp, perCode[code].tp + perCode[code].fn);
  }
  const clean = results.filter(result => result.knownClean);
  const broken = results.filter(result => !result.knownClean);
  const cleanWithAnyIssue = clean.filter(result => result.predictedCodes.length > 0).length;
  const cleanPassed = clean.filter(result => result.predictedCodes.length === 0).length;
  const brokenWithError = broken.filter(result => result.predictedCodes.length > 0).length;
  const severityMismatchCodes = new Set(severityMisclassifications.map(item => item.code));
  const autoRepairEligible = VISUAL_ISSUE_CODES.filter(code => perCode[code].support > 0 && perCode[code].precision >= PRECISION_THRESHOLD && perCode[code].recall >= RECALL_THRESHOLD && !severityMismatchCodes.has(code));
  const humanReviewRecommended = VISUAL_ISSUE_CODES.filter(code => perCode[code].support > 0 && !autoRepairEligible.includes(code));
  const informationalOnly = VISUAL_ISSUE_CODES.filter(code => perCode[code].support === 0);
  return {
    version: 1,
    generatedAt: new Date().toISOString(),
    provider,
    model,
    caseCount: results.length,
    cleanCount: clean.length,
    brokenCount: broken.length,
    totalRequests,
    repairRequests,
    elapsedMs,
    metrics: {
      issuePrecision: ratio(tp, tp + fp),
      issueRecall: ratio(tp, tp + fn),
      falsePositiveRate: ratio(cleanWithAnyIssue, clean.length),
      cleanSlidePassRate: ratio(cleanPassed, clean.length),
      errorDetectionRate: ratio(brokenWithError, broken.length),
      tp, fp, fn,
    },
    perCode,
    falsePositiveCases,
    falseNegativeCases,
    severityMisclassifications,
    autoRepairPolicy: { precisionThreshold: PRECISION_THRESHOLD, recallThreshold: RECALL_THRESHOLD, autoRepairEligible, humanReviewRecommended, informationalOnly },
    cases: results,
  };
}

export async function runVarianceProbe(
  goldCases: GoldCase[],
  fixtureDir: string,
  providerFactory: CalibrationProviderFactory,
  providerName: string,
  model: string,
  caseIds: string[],
  repeatsPerCase = 3,
): Promise<VarianceProbeReport> {
  if (repeatsPerCase < 1 || !Number.isSafeInteger(repeatsPerCase)) {
    throw new HarnessError("CALIBRATION_VARIANCE_INVALID", "Variance probe repeats must be a positive integer", 422);
  }
  const selected = caseIds.map(caseId => {
    const goldCase = goldCases.find(item => item.caseId === caseId);
    if (!goldCase) throw new HarnessError("CALIBRATION_VARIANCE_INVALID", `Variance probe case not found: ${caseId}`, 422);
    return goldCase;
  });
  const started = Date.now();
  let totalRequests = 0;
  const cases: VarianceProbeReport["cases"] = [];
  for (const goldCase of selected) {
    const bytes = await fs.readFile(path.join(fixtureDir, goldCase.fileName));
    const dimensions = pngDimensions(bytes);
    const sha256 = crypto.createHash("sha256").update(bytes).digest("hex");
    verifyPng(bytes, bytes.byteLength, sha256, dimensions.width, dimensions.height, goldCase.slideNumber);
    const input = calibrationInput(goldCase, bytes);
    const observations: VarianceProbeObservation[] = [];
    for (let repeat = 1; repeat <= repeatsPerCase; repeat += 1) {
      const provider = providerFactory(goldCase);
      let repaired = false;
      let raw: unknown;
      try {
        totalRequests += 1;
        raw = await provider.review(input);
      } catch (error) {
        observations.push({ repeat, predictedCodes: [], outcome: "PROVIDER_ERROR", repaired, errorCode: error instanceof HarnessError ? error.code : "CALIBRATION_PROVIDER_ERROR" });
        continue;
      }
      let parsed = parseSlideResult(raw, goldCase.slideNumber);
      if (!parsed.result) {
        repaired = true;
        totalRequests += 1;
        try {
          raw = await provider.repair(input, raw, parsed.reason);
        } catch (error) {
          observations.push({ repeat, predictedCodes: [], outcome: "PROVIDER_ERROR", repaired, errorCode: error instanceof HarnessError ? error.code : "CALIBRATION_PROVIDER_ERROR" });
          continue;
        }
        parsed = parseSlideResult(raw, goldCase.slideNumber);
      }
      if (!parsed.result) {
        observations.push({ repeat, predictedCodes: [], outcome: "INVALID_RESULT", repaired, errorCode: "CALIBRATION_INVALID_RESULT" });
        continue;
      }
      observations.push({ repeat, predictedCodes: uniqueCodes(parsed.result.issues), predictedMaxSeverity: maxSeverity(parsed.result.issues), outcome: "SUCCESS", repaired });
    }
    const codeSets = observations.map(observation => JSON.stringify(observation.predictedCodes));
    const severities = observations.map(observation => observation.predictedMaxSeverity ?? "NONE");
    cases.push({
      caseId: goldCase.caseId,
      knownClean: goldCase.knownClean,
      expectedCodes: goldCase.expectedCodes,
      observations,
      codeSetStable: new Set(codeSets).size <= 1,
      severityStable: new Set(severities).size <= 1,
    });
  }
  return { version: 1, generatedAt: new Date().toISOString(), provider: providerName, model, repeatsPerCase, totalRequests, elapsedMs: Date.now() - started, cases };
}

function parseGoldCase(value: unknown): GoldCase {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new HarnessError("CALIBRATION_GOLD_INVALID", "Gold case must be an object", 422);
  const item = value as Record<string, unknown>;
  const expectedCodes = Array.isArray(item.expectedCodes) ? item.expectedCodes : [];
  const caseId = typeof item.caseId === "string" ? item.caseId : undefined;
  const fileName = typeof item.fileName === "string" ? item.fileName : undefined;
  const slideNumber = typeof item.slideNumber === "number" && Number.isSafeInteger(item.slideNumber) ? item.slideNumber : undefined;
  const expectedMaxSeverity = isSeverity(item.expectedMaxSeverity) ? item.expectedMaxSeverity : undefined;
  const knownClean = typeof item.knownClean === "boolean" ? item.knownClean : undefined;
  const notes = typeof item.notes === "string" ? item.notes : undefined;
  if (caseId === undefined || fileName === undefined || slideNumber === undefined || slideNumber < 1 || !expectedCodes.every(code => typeof code === "string" && VISUAL_ISSUE_CODES.includes(code as VisualIssueCode)) || expectedMaxSeverity === undefined || knownClean === undefined || notes === undefined) {
    throw new HarnessError("CALIBRATION_GOLD_INVALID", "Gold case schema is invalid", 422);
  }
  return { caseId, fileName, slideNumber, expectedCodes: expectedCodes as VisualIssueCode[], expectedMaxSeverity, knownClean, notes };
}

function failedCase(goldCase: GoldCase, outcome: "PROVIDER_ERROR" | "INVALID_RESULT", error: unknown): CalibrationCaseResult {
  const failure = error instanceof Error ? error : new Error(String(error));
  return { caseId: goldCase.caseId, slideNumber: goldCase.slideNumber, knownClean: goldCase.knownClean, expectedCodes: goldCase.expectedCodes, predictedCodes: [], predictedIssues: [], expectedMaxSeverity: goldCase.expectedMaxSeverity, outcome, repaired: false, errorCode: error instanceof HarnessError ? error.code : "CALIBRATION_PROVIDER_ERROR", errorMessage: failure.message };
}

function uniqueCodes(issues: VisualQaIssue[]): VisualIssueCode[] { return [...new Set(issues.map(issue => issue.code))]; }
function maxSeverity(issues: VisualQaIssue[]): VisualIssueSeverity | undefined { return issues.reduce<VisualIssueSeverity | undefined>((current, issue) => !current || SEVERITY_RANK[issue.severity] > SEVERITY_RANK[current] ? issue.severity : current, undefined); }
function ratio(numerator: number, denominator: number): number { return denominator === 0 ? 0 : Number((numerator / denominator).toFixed(4)); }
function isSeverity(value: unknown): value is VisualIssueSeverity { return value === "INFO" || value === "WARNING" || value === "ERROR"; }
function pngDimensions(bytes: Uint8Array): { width: number; height: number } {
  if (bytes.byteLength < 24) throw new HarnessError("CALIBRATION_PNG_INVALID", "Calibration fixture is too small to be a PNG", 422);
  return { width: Buffer.from(bytes).readUInt32BE(16), height: Buffer.from(bytes).readUInt32BE(20) };
}

async function main(): Promise<void> {
  const testDir = path.dirname(fileURLToPath(import.meta.url));
  const fixtureDir = process.env.PPT_VISUAL_QA_GOLD_DIR || path.join(testDir, "fixtures", "visual-qa-gold");
  const goldSetPath = process.env.PPT_VISUAL_QA_GOLD_SET || path.join(testDir, "fixtures", "visual-qa-gold-set.json");
  const outputPath = process.env.PPT_VISUAL_QA_REPORT || path.join(fixtureDir, "calibration-report.json");
  const config = loadConfig();
  const goldCases = await loadGoldSet(goldSetPath);
  const report = await evaluateGoldSet(goldCases, fixtureDir, () => new KimiVisualQaProvider(config), "KimiVisualQaProvider", config.kimiModel);
  await fs.writeFile(outputPath, `${JSON.stringify(report, null, 2)}\n`, "utf8");
  const varianceIds = (process.env.PPT_VISUAL_QA_VARIANCE_CASE_IDS ?? "").split(",").map(value => value.trim()).filter(Boolean);
  const varianceOutputPath = process.env.PPT_VISUAL_QA_VARIANCE_REPORT || path.join(fixtureDir, "variance-report.json");
  let varianceProbe: VarianceProbeReport | undefined;
  if (varianceIds.length) {
    varianceProbe = await runVarianceProbe(goldCases, fixtureDir, () => new KimiVisualQaProvider(config), "KimiVisualQaProvider", config.kimiModel, varianceIds, 3);
    await fs.writeFile(varianceOutputPath, `${JSON.stringify(varianceProbe, null, 2)}\n`, "utf8");
  }
  process.stdout.write(`${JSON.stringify({ reportPath: outputPath, provider: report.provider, model: report.model, caseCount: report.caseCount, totalRequests: report.totalRequests, elapsedMs: report.elapsedMs, metrics: report.metrics }, null, 2)}\n`);
  if (varianceProbe) process.stdout.write(`${JSON.stringify({ varianceReportPath: varianceOutputPath, totalRequests: varianceProbe.totalRequests, elapsedMs: varianceProbe.elapsedMs, cases: varianceProbe.cases }, null, 2)}\n`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(error => { process.stderr.write(`${error instanceof Error ? error.stack ?? error.message : String(error)}\n`); process.exitCode = 1; });
}
