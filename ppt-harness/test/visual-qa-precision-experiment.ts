import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { loadConfig, HarnessConfig } from "../src/config.js";
import { HarnessError } from "../src/domain.js";
import {
  VISUAL_ISSUE_CODES,
  VisualIssueCode,
  VisualIssueSeverity,
  VisualQaIssue,
  VisualQaProvider,
  VisualQaSlideInput,
} from "../src/visual-qa.js";
import {
  CalibrationCaseResult,
  CalibrationReport,
  GoldCase,
  evaluateGoldSet,
  loadGoldSet,
} from "./visual-qa-calibration.js";

export type PromptVariant = "A" | "B";
export type IssueGroup = "objective" | "subjective";
export type DecisionLabel = "ACTIONABLE" | "REVIEW_ONLY" | "IGNORED_FOR_GATE";

export const OBJECTIVE_CODES: VisualIssueCode[] = [
  "TEXT_OVERFLOW",
  "TEXT_TOO_SMALL",
  "ELEMENT_OVERLAP",
  "ELEMENT_CLIPPED",
  "LOW_CONTRAST",
  "TABLE_UNREADABLE",
  "CHART_UNREADABLE",
  "FLOW_UNREADABLE",
  "BROKEN_RENDERING",
];

export const SUBJECTIVE_CODES: VisualIssueCode[] = [
  "UNBALANCED_LAYOUT",
  "EXCESSIVE_EMPTY_SPACE",
  "VISUAL_HIERARCHY_WEAK",
  "DENSE_CONTENT",
];

const ISSUE_GROUP: Record<VisualIssueCode, IssueGroup> = Object.fromEntries([
  ...OBJECTIVE_CODES.map(code => [code, "objective"]),
  ...SUBJECTIVE_CODES.map(code => [code, "subjective"]),
]) as Record<VisualIssueCode, IssueGroup>;

const SEVERITIES: VisualIssueSeverity[] = ["INFO", "WARNING", "ERROR"];
const THRESHOLDS = [0.5, 0.6, 0.7, 0.75, 0.8, 0.85, 0.9, 0.95, 1];

const VISUAL_QA_RESPONSE_SCHEMA = {
  type: "object",
  additionalProperties: false,
  required: ["slideNumber", "issues"],
  properties: {
    slideNumber: { type: "integer", minimum: 1 },
    issues: {
      type: "array",
      items: {
        type: "object",
        additionalProperties: false,
        required: ["slideNumber", "code", "severity", "confidence", "description", "repairHint"],
        properties: {
          slideNumber: { type: "integer", minimum: 1 },
          code: { type: "string", enum: VISUAL_ISSUE_CODES },
          severity: { type: "string", enum: SEVERITIES },
          confidence: { type: "number", minimum: 0, maximum: 1 },
          description: { type: "string" },
          repairHint: { type: "string" },
        },
      },
    },
  },
} as const;

export type MetricSummary = {
  rawPrecision: number;
  rawRecall: number;
  falsePositiveRate: number;
  cleanSlidePassRate: number;
  errorPrecision: number;
  errorRecall: number;
  warningPrecision: number;
  warningRecall: number;
  infoPrecision: number;
  infoRecall: number;
  cleanHardFailRate: number;
  brokenHardFailRecall: number;
  objective: GroupMetric;
  subjective: GroupMetric;
  fpCount: number;
  fnCount: number;
};

export type GroupMetric = {
  tp: number;
  fp: number;
  fn: number;
  precision: number;
  recall: number;
};

export type ConfidenceStats = {
  count: number;
  min?: number;
  p25?: number;
  median?: number;
  p75?: number;
  max?: number;
  mean?: number;
};

export type ConfidenceBucket = {
  tp: ConfidenceStats;
  fp: ConfidenceStats;
};

export type ConfidenceAnalysis = {
  all: ConfidenceBucket;
  bySeverity: Record<VisualIssueSeverity, ConfidenceBucket>;
  byGroup: Record<IssueGroup, ConfidenceBucket>;
  byCleanBroken: { clean: ConfidenceBucket; broken: ConfidenceBucket };
};

export type ConfidencePolicy =
  | { kind: "GLOBAL"; threshold: number }
  | { kind: "OBJECTIVE_SUBJECTIVE"; objectiveThreshold: number; subjectiveThreshold: number };

export type ThresholdEvaluation = MetricSummary & { policy: ConfidencePolicy };

export type ActionabilitySimulation = {
  policy: ConfidencePolicy;
  raw: number;
  actionable: number;
  reviewOnly: number;
  ignoredForGate: number;
  actionableCases: number;
  cleanActionableCases: number;
  brokenActionableCases: number;
  byCode: Record<VisualIssueCode, { RAW: number; ACTIONABLE: number; REVIEW_ONLY: number; IGNORED_FOR_GATE: number }>;
};

export type VariantSummary = {
  variant: "BASELINE" | PromptVariant;
  prompt: string;
  metrics: MetricSummary;
  calibrationReportPath?: string;
  actionability?: ActionabilitySimulation;
};

export type PrecisionExperimentReport = {
  version: 1;
  generatedAt: string;
  baselineSource: string;
  provider: string;
  model: string;
  newMainRequests: number;
  newApiRequests: number;
  repairRequests: number;
  baseline: VariantSummary;
  candidateA: VariantSummary;
  candidateB: VariantSummary;
  confidenceAnalysis: ConfidenceAnalysis;
  baselineGlobalThresholdSweep: ThresholdEvaluation[];
  baselineObjectiveSubjectiveThresholdSweep: ThresholdEvaluation[];
  recommendedConfidencePolicy: ConfidencePolicy;
  promptSelection: {
    recommendedPrompt: "BASELINE" | "A" | "B" | "NONE";
    bestObservedPrompt: "A" | "B" | "NONE";
    rationale: string;
  };
  proposedGatePolicy: string;
  brokenRenderingAnalysis: {
    caseId: string;
    slideNumber: number;
    goldCode: "BROKEN_RENDERING";
    predictedCodes: VisualIssueCode[];
    predictedSeverities: VisualIssueSeverity[];
    semanticCodeMiss: boolean;
    blockingSuccess: boolean;
    manualFinding: string;
  };
};

type ExperimentCase = Pick<CalibrationCaseResult, "expectedCodes" | "predictedIssues" | "knownClean" | "expectedMaxSeverity">;
type IssueRow = { issue: VisualQaIssue; label: "TP" | "FP"; group: IssueGroup; clean: boolean };

export function issueGroup(code: VisualIssueCode): IssueGroup {
  return ISSUE_GROUP[code];
}

export function analyzeCalibrationReport(report: CalibrationReport): MetricSummary {
  return summarizeCases(report.cases);
}

export function confidenceAnalysis(report: CalibrationReport): ConfidenceAnalysis {
  const rows = issueRows(report.cases);
  const bucket = (items: IssueRow[]): ConfidenceBucket => ({
    tp: confidenceStats(items.filter(item => item.label === "TP").map(item => item.issue.confidence)),
    fp: confidenceStats(items.filter(item => item.label === "FP").map(item => item.issue.confidence)),
  });
  return {
    all: bucket(rows),
    bySeverity: Object.fromEntries(SEVERITIES.map(severity => [severity, bucket(rows.filter(item => item.issue.severity === severity))])) as Record<VisualIssueSeverity, ConfidenceBucket>,
    byGroup: {
      objective: bucket(rows.filter(item => item.group === "objective")),
      subjective: bucket(rows.filter(item => item.group === "subjective")),
    },
    byCleanBroken: {
      clean: bucket(rows.filter(item => item.clean)),
      broken: bucket(rows.filter(item => !item.clean)),
    },
  };
}

export function simulateConfidencePolicy(report: CalibrationReport, policy: ConfidencePolicy): MetricSummary {
  return summarizeCases(report.cases.map(item => ({
    ...item,
    predictedIssues: item.predictedIssues.filter(issue => issue.confidence >= thresholdFor(policy, issue.code)),
  })));
}

export function selectBaselineConfidencePolicy(report: CalibrationReport): {
  policy: ConfidencePolicy;
  global: ThresholdEvaluation[];
  objectiveSubjective: ThresholdEvaluation[];
} {
  const baseline = analyzeCalibrationReport(report);
  const global = THRESHOLDS.map(threshold => {
    const policy: ConfidencePolicy = { kind: "GLOBAL", threshold };
    return { policy, ...simulateConfidencePolicy(report, policy) };
  });
  const objectiveSubjective = THRESHOLDS.flatMap(objectiveThreshold => THRESHOLDS.map(subjectiveThreshold => {
    const policy: ConfidencePolicy = { kind: "OBJECTIVE_SUBJECTIVE", objectiveThreshold, subjectiveThreshold };
    return { policy, ...simulateConfidencePolicy(report, policy) };
  }));
  const safe = [...global, ...objectiveSubjective].filter(item => item.cleanSlidePassRate >= 1 && item.brokenHardFailRecall >= baseline.brokenHardFailRecall && item.errorRecall >= baseline.errorRecall);
  const pool = safe.length ? safe : [...global, ...objectiveSubjective];
  pool.sort(comparePolicyEvaluation);
  return { policy: pool[0].policy, global, objectiveSubjective };
}

export function simulateActionability(report: CalibrationReport, policy: ConfidencePolicy): ActionabilitySimulation {
  const byCode = Object.fromEntries(VISUAL_ISSUE_CODES.map(code => [code, { RAW: 0, ACTIONABLE: 0, REVIEW_ONLY: 0, IGNORED_FOR_GATE: 0 }])) as ActionabilitySimulation["byCode"];
  let raw = 0;
  let actionable = 0;
  let reviewOnly = 0;
  let ignoredForGate = 0;
  let actionableCases = 0;
  let cleanActionableCases = 0;
  let brokenActionableCases = 0;
  for (const item of report.cases) {
    let caseActionable = false;
    for (const issue of item.predictedIssues) {
      raw += 1;
      byCode[issue.code].RAW += 1;
      const decision = issue.confidence < thresholdFor(policy, issue.code)
        ? "IGNORED_FOR_GATE"
        : issue.severity === "ERROR" && issueGroup(issue.code) === "objective"
          ? "ACTIONABLE"
          : "REVIEW_ONLY";
      byCode[issue.code][decision] += 1;
      if (decision === "ACTIONABLE") { actionable += 1; caseActionable = true; }
      if (decision === "REVIEW_ONLY") reviewOnly += 1;
      if (decision === "IGNORED_FOR_GATE") ignoredForGate += 1;
    }
    if (caseActionable) {
      actionableCases += 1;
      if (item.knownClean) cleanActionableCases += 1;
      else brokenActionableCases += 1;
    }
  }
  return { policy, raw, actionable, reviewOnly, ignoredForGate, actionableCases, cleanActionableCases, brokenActionableCases, byCode };
}

export function candidateSelection(baseline: MetricSummary, candidates: Array<{ variant: "A" | "B"; metrics: MetricSummary }>): PrecisionExperimentReport["promptSelection"] {
  const observed = [...candidates].sort((left, right) => compareCandidateMetrics(left.metrics, right.metrics));
  const bestObservedPrompt = observed[0]?.variant ?? "NONE";
  const safe = candidates.filter(candidate => candidate.metrics.cleanHardFailRate < baseline.cleanHardFailRate && candidate.metrics.brokenHardFailRecall >= baseline.brokenHardFailRecall && candidate.metrics.errorRecall >= baseline.errorRecall);
  const recommendedPrompt = safe.length ? [...safe].sort((left, right) => compareCandidateMetrics(left.metrics, right.metrics))[0].variant : "NONE";
  const rationale = recommendedPrompt === "NONE"
    ? "Neither prompt produced a strict clean hard-fail improvement while preserving broken hard-fail recall; keep production unchanged."
    : `${recommendedPrompt} strictly improves clean hard-fail rate without reducing broken hard-fail recall.`;
  return { recommendedPrompt, bestObservedPrompt, rationale };
}

export function buildPromptVariantProvider(config: HarnessConfig, variant: PromptVariant): VisualQaProvider {
  const request = async (input: VisualQaSlideInput, mode: "REVIEW" | "REPAIR", reason?: string, invalidResult?: unknown): Promise<unknown> => {
    if (!config.kimiApiKey) throw new HarnessError("VISUAL_REVIEW_PROVIDER_UNAVAILABLE", "Visual QA requires a server-side MOONSHOT_API_KEY", 503);
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), config.kimiTimeoutMs);
    try {
      const response = await fetch(`${config.kimiBaseUrl}/chat/completions`, {
        method: "POST",
        signal: controller.signal,
        headers: { "Content-Type": "application/json", "Authorization": `Bearer ${config.kimiApiKey}` },
        body: JSON.stringify({
          model: config.kimiModel,
          thinking: { type: "disabled" },
          response_format: { type: "json_schema", json_schema: { name: "visual_qa_slide_result_v1", strict: true, schema: VISUAL_QA_RESPONSE_SCHEMA } },
          messages: [
            { role: "system", content: candidateSystemPrompt(variant, mode) },
            { role: "user", content: candidateUserContent(input, mode, reason, invalidResult) },
          ],
        }),
      });
      if (!response.ok) throw new HarnessError("VISUAL_REVIEW_PROVIDER_FAILED", `Visual QA provider failed with HTTP ${response.status}: ${safeProviderError(await response.text())}`, 502);
      const payload = await response.json() as { choices?: Array<{ message?: { content?: unknown } }> };
      const content = responseContent(payload.choices?.[0]?.message?.content);
      if (!content) return "";
      try { return JSON.parse(content) as unknown; } catch { return content; }
    } catch (error) {
      if (error instanceof HarnessError) throw error;
      if (error instanceof Error && error.name === "AbortError") throw new HarnessError("VISUAL_REVIEW_PROVIDER_UNAVAILABLE", "Visual QA provider timed out", 503);
      throw new HarnessError("VISUAL_REVIEW_PROVIDER_UNAVAILABLE", "Visual QA provider is unavailable", 503);
    } finally {
      clearTimeout(timeout);
    }
  };
  return {
    review: input => request(input, "REVIEW"),
    repair: (input, invalidResult, reason) => request(input, "REPAIR", reason, invalidResult),
  };
}

export function candidateSystemPrompt(variant: PromptVariant, mode: "REVIEW" | "REPAIR"): string {
  const base = [
    "You are a rendered-slide visual QA classifier.",
    "Inspect the supplied PNG itself and classify visible presentation quality only.",
    "Do not judge factual correctness, evidence truth, teaching objectives, or whether lesson content is pedagogically correct.",
    "Use only these issue codes: " + VISUAL_ISSUE_CODES.join(", ") + ".",
    "Use severity INFO, WARNING, or ERROR and confidence from 0 to 1.",
  ];
  const candidate = variant === "A"
    ? [
      "DEFECT-ONLY REVIEW.",
      "Report only an actual, visible, demonstrable defect that materially harms reading, understanding, or presentation use.",
      "Do not report aesthetic preferences, ordinary asymmetry, normal whitespace, or a simple chart/table/flow that is readable.",
      "Do not report EXCESSIVE_EMPTY_SPACE unless whitespace clearly leaves content incomplete, clusters content into a broken local region, or makes the page look unfinished.",
      "Report UNBALANCED_LAYOUT only when the imbalance is obvious and disrupts the reading path.",
      "Report VISUAL_HIERARCHY_WEAK only when the title, main content, or reading order cannot be identified.",
      "Report CHART_UNREADABLE only when labels, legend, data, axes, or marks cannot be recognized; simple charts and non-ideal design are not defects.",
      "Report TABLE_UNREADABLE only when text, rows, columns, or table structure cannot be normally read.",
      "Report TEXT_TOO_SMALL only when text is actually illegible at presentation viewing size, not because it merely feels small.",
      "Report LOW_CONTRAST only when text or key graphics are genuinely difficult to distinguish.",
      "INFO is not a praise or style-feedback channel. If there is no clear defect, return issues as an empty array.",
    ]
    : [
      "DEFECT-ONLY REVIEW with an EVIDENCE THRESHOLD.",
      "Every reported issue must have concrete visible evidence, material impact on reading or use, and a specific repair reason.",
      "The description must identify the visible element and location; the repairHint must explain the concrete correction.",
      "If you cannot clearly state which element is affected, where it is, and why it materially hurts the slide, do not output that issue.",
      "Do not report aesthetic preferences, ordinary asymmetry, normal whitespace, or a simple chart/table/flow that is readable.",
      "Do not report EXCESSIVE_EMPTY_SPACE unless whitespace clearly causes incomplete layout or harmful content clustering.",
      "Report UNBALANCED_LAYOUT or VISUAL_HIERARCHY_WEAK only when the visible imbalance materially disrupts reading order.",
      "Report CHART_UNREADABLE or TABLE_UNREADABLE only when the actual chart/table content cannot be normally read.",
      "Report TEXT_TOO_SMALL or LOW_CONTRAST only when legibility is materially impaired, not from subjective preference.",
      "INFO is not a praise or style-feedback channel. If evidence and impact are insufficient, return issues as an empty array.",
    ];
  const instruction = mode === "REPAIR" ? "The previous response failed local schema validation. Return one corrected JSON object only. Do not use Markdown fences." : "Return one JSON object only. Do not use Markdown prose or code fences.";
  return [...base, ...candidate, instruction].join(" ");
}

function summarizeCases(cases: ExperimentCase[]): MetricSummary {
  let tp = 0;
  let fp = 0;
  let fn = 0;
  const perCode = Object.fromEntries(VISUAL_ISSUE_CODES.map(code => [code, { tp: 0, fp: 0, fn: 0 }])) as Record<VisualIssueCode, { tp: number; fp: number; fn: number }>;
  const severity = Object.fromEntries(SEVERITIES.map(value => [value, { expected: 0, predicted: 0, tp: 0 }])) as Record<VisualIssueSeverity, { expected: number; predicted: number; tp: number }>;
  for (const item of cases) {
    const expected = new Set(item.expectedCodes);
    const predicted = new Set(item.predictedIssues.map(issue => issue.code));
    for (const code of VISUAL_ISSUE_CODES) {
      const e = expected.has(code);
      const p = predicted.has(code);
      if (e) { perCode[code].tp += Number(p); perCode[code].fn += Number(!p); }
      else perCode[code].fp += Number(p);
      if (e && p) tp += 1;
      if (!e && p) fp += 1;
      if (e && !p) fn += 1;
    }
    for (const code of item.expectedCodes) severity[item.expectedMaxSeverity].expected += 1;
    for (const issue of item.predictedIssues) {
      severity[issue.severity].predicted += 1;
      if (expected.has(issue.code) && issue.severity === item.expectedMaxSeverity) severity[issue.severity].tp += 1;
    }
  }
  const clean = cases.filter(item => item.knownClean);
  const broken = cases.filter(item => !item.knownClean);
  const cleanWithIssues = clean.filter(item => item.predictedIssues.length > 0).length;
  const cleanHardFails = clean.filter(item => item.predictedIssues.some(issue => issue.severity === "ERROR")).length;
  const brokenHardFails = broken.filter(item => item.predictedIssues.some(issue => issue.severity === "ERROR")).length;
  const severityMetric = (value: VisualIssueSeverity): { precision: number; recall: number } => ({
    precision: ratio(severity[value].tp, severity[value].predicted),
    recall: ratio(severity[value].tp, severity[value].expected),
  });
  const groupMetric = (codes: VisualIssueCode[]): GroupMetric => {
    const totals = codes.reduce((acc, code) => ({ tp: acc.tp + perCode[code].tp, fp: acc.fp + perCode[code].fp, fn: acc.fn + perCode[code].fn }), { tp: 0, fp: 0, fn: 0 });
    return { ...totals, precision: ratio(totals.tp, totals.tp + totals.fp), recall: ratio(totals.tp, totals.tp + totals.fn) };
  };
  const info = severityMetric("INFO");
  const warning = severityMetric("WARNING");
  const error = severityMetric("ERROR");
  return {
    rawPrecision: ratio(tp, tp + fp),
    rawRecall: ratio(tp, tp + fn),
    falsePositiveRate: ratio(cleanWithIssues, clean.length),
    cleanSlidePassRate: ratio(clean.length - cleanWithIssues, clean.length),
    errorPrecision: error.precision,
    errorRecall: error.recall,
    warningPrecision: warning.precision,
    warningRecall: warning.recall,
    infoPrecision: info.precision,
    infoRecall: info.recall,
    cleanHardFailRate: ratio(cleanHardFails, clean.length),
    brokenHardFailRecall: ratio(brokenHardFails, broken.length),
    objective: groupMetric(OBJECTIVE_CODES),
    subjective: groupMetric(SUBJECTIVE_CODES),
    fpCount: fp,
    fnCount: fn,
  };
}

function issueRows(cases: ExperimentCase[]): IssueRow[] {
  return cases.flatMap(item => item.predictedIssues.map(issue => ({ issue, label: item.expectedCodes.includes(issue.code) ? "TP" : "FP", group: issueGroup(issue.code), clean: item.knownClean })));
}

function confidenceStats(values: number[]): ConfidenceStats {
  if (!values.length) return { count: 0 };
  const sorted = [...values].sort((a, b) => a - b);
  const mean = sorted.reduce((sum, value) => sum + value, 0) / sorted.length;
  return {
    count: sorted.length,
    min: sorted[0],
    p25: sorted[Math.floor((sorted.length - 1) * 0.25)],
    median: sorted[Math.floor((sorted.length - 1) * 0.5)],
    p75: sorted[Math.floor((sorted.length - 1) * 0.75)],
    max: sorted[sorted.length - 1],
    mean: Number(mean.toFixed(4)),
  };
}

function thresholdFor(policy: ConfidencePolicy, code: VisualIssueCode): number {
  return policy.kind === "GLOBAL" ? policy.threshold : ISSUE_GROUP[code] === "objective" ? policy.objectiveThreshold : policy.subjectiveThreshold;
}

function comparePolicyEvaluation(left: ThresholdEvaluation, right: ThresholdEvaluation): number {
  return right.cleanSlidePassRate - left.cleanSlidePassRate
    || right.brokenHardFailRecall - left.brokenHardFailRecall
    || right.errorRecall - left.errorRecall
    || right.rawPrecision - left.rawPrecision
    || right.rawRecall - left.rawRecall
    || policyComplexity(left.policy) - policyComplexity(right.policy);
}

function compareCandidateMetrics(left: MetricSummary, right: MetricSummary): number {
  return left.cleanHardFailRate - right.cleanHardFailRate
    || right.brokenHardFailRecall - left.brokenHardFailRecall
    || right.errorRecall - left.errorRecall
    || right.rawPrecision - left.rawPrecision
    || right.rawRecall - left.rawRecall;
}

function policyComplexity(policy: ConfidencePolicy): number {
  return policy.kind === "GLOBAL" ? policy.threshold : policy.objectiveThreshold + policy.subjectiveThreshold;
}

function ratio(numerator: number, denominator: number): number {
  return denominator === 0 ? 0 : Number((numerator / denominator).toFixed(4));
}

function candidateUserContent(input: VisualQaSlideInput, mode: "REVIEW" | "REPAIR", reason?: string, invalidResult?: unknown): Array<{ type: "text"; text: string } | { type: "image_url"; image_url: { url: string } }> {
  const context = {
    slideNumber: input.slideNumber,
    expectedVisualContext: input.expected,
    outputShape: {
      slideNumber: input.slideNumber,
      issues: [{ slideNumber: input.slideNumber, code: "TEXT_OVERFLOW", severity: "WARNING", confidence: 0.8, description: "short visible description", repairHint: "short localized layout hint" }],
    },
    ...(mode === "REPAIR" ? { schemaRepair: { reason, previousResponse: truncate(JSON.stringify(invalidResult) ?? String(invalidResult)) } } : {}),
  };
  return [{ type: "text", text: JSON.stringify(context) }, { type: "image_url", image_url: { url: input.imageDataUrl } }];
}

function responseContent(value: unknown): string | undefined {
  if (typeof value === "string" && value.trim()) return value.trim();
  if (!Array.isArray(value)) return undefined;
  const text = value.map(item => typeof item === "object" && item !== null && "text" in item && typeof item.text === "string" ? item.text : "").join("").trim();
  return text || undefined;
}

function safeProviderError(value: string): string {
  return value.replace(/\s+/g, " ").replace(/Bearer\s+[A-Za-z0-9._-]+/gi, "Bearer [REDACTED]").slice(0, 180);
}

function truncate(value: string): string {
  return value.length > 2000 ? `${value.slice(0, 2000)}...` : value;
}

export async function runPrecisionExperiment(): Promise<PrecisionExperimentReport> {
  const testDir = path.dirname(fileURLToPath(import.meta.url));
  const fixtureDir = process.env.PPT_VISUAL_QA_GOLD_DIR || path.join(testDir, "fixtures", "visual-qa-gold");
  const goldSetPath = process.env.PPT_VISUAL_QA_GOLD_SET || path.join(testDir, "fixtures", "visual-qa-gold-set.json");
  const outputDir = process.env.PPT_VISUAL_QA_EXPERIMENT_DIR || path.join(fixtureDir, "precision-experiment");
  const config = loadConfig();
  const goldCases = await loadGoldSet(goldSetPath);
  const baselineReport = JSON.parse(await fs.readFile(path.join(fixtureDir, "calibration-report.json"), "utf8")) as CalibrationReport;
  const candidateAReport = await evaluateGoldSet(goldCases, fixtureDir, () => buildPromptVariantProvider(config, "A"), "KimiVisualQaProvider:CandidateA", config.kimiModel);
  const candidateBReport = await evaluateGoldSet(goldCases, fixtureDir, () => buildPromptVariantProvider(config, "B"), "KimiVisualQaProvider:CandidateB", config.kimiModel);
  const baselineMetrics = analyzeCalibrationReport(baselineReport);
  const confidence = confidenceAnalysis(baselineReport);
  const threshold = selectBaselineConfidencePolicy(baselineReport);
  const selection = candidateSelection(baselineMetrics, [
    { variant: "A", metrics: analyzeCalibrationReport(candidateAReport) },
    { variant: "B", metrics: analyzeCalibrationReport(candidateBReport) },
  ]);
  const actionabilityPolicy = threshold.policy;
  const baselineSummary: VariantSummary = { variant: "BASELINE", prompt: "Frozen PPT-012 production prompt", metrics: baselineMetrics, calibrationReportPath: path.join(fixtureDir, "calibration-report.json"), actionability: simulateActionability(baselineReport, actionabilityPolicy) };
  const candidateASummary: VariantSummary = { variant: "A", prompt: "DEFECT-ONLY REVIEW", metrics: analyzeCalibrationReport(candidateAReport), actionability: simulateActionability(candidateAReport, actionabilityPolicy) };
  const candidateBSummary: VariantSummary = { variant: "B", prompt: "DEFECT-ONLY REVIEW + EVIDENCE THRESHOLD", metrics: analyzeCalibrationReport(candidateBReport), actionability: simulateActionability(candidateBReport, actionabilityPolicy) };
  await fs.mkdir(outputDir, { recursive: true });
  const candidateAPath = path.join(outputDir, "candidate-a-calibration-report.json");
  const candidateBPath = path.join(outputDir, "candidate-b-calibration-report.json");
  await fs.writeFile(candidateAPath, `${JSON.stringify(candidateAReport, null, 2)}\n`, "utf8");
  await fs.writeFile(candidateBPath, `${JSON.stringify(candidateBReport, null, 2)}\n`, "utf8");
  candidateASummary.calibrationReportPath = candidateAPath;
  candidateBSummary.calibrationReportPath = candidateBPath;
  const brokenRendering = baselineReport.cases.find(item => item.caseId === "broken-rendering");
  if (!brokenRendering) throw new HarnessError("CALIBRATION_BASELINE_INVALID", "Frozen baseline is missing broken-rendering", 422);
  const brokenRenderingAnalysis = {
    caseId: brokenRendering.caseId,
    slideNumber: 1,
    goldCode: "BROKEN_RENDERING" as const,
    predictedCodes: [...new Set(brokenRendering.predictedCodes)],
    predictedSeverities: [...new Set(brokenRendering.predictedIssues.map(issue => issue.severity))],
    semanticCodeMiss: !brokenRendering.predictedCodes.includes("BROKEN_RENDERING"),
    blockingSuccess: brokenRendering.predictedIssues.some(issue => issue.severity === "ERROR"),
    manualFinding: "The fixture is visibly corrupted; baseline missed the semantic BROKEN_RENDERING code but emitted ERROR-level secondary findings, so blocking succeeded while code recall failed.",
  };
  return {
    version: 1,
    generatedAt: new Date().toISOString(),
    baselineSource: "PPT-012 frozen calibration-report.json; no baseline re-call",
    provider: "KimiVisualQaProvider",
    model: config.kimiModel,
    newMainRequests: candidateAReport.caseCount + candidateBReport.caseCount,
    newApiRequests: candidateAReport.totalRequests + candidateBReport.totalRequests,
    repairRequests: candidateAReport.repairRequests + candidateBReport.repairRequests,
    baseline: baselineSummary,
    candidateA: candidateASummary,
    candidateB: candidateBSummary,
    confidenceAnalysis: confidence,
    baselineGlobalThresholdSweep: threshold.global,
    baselineObjectiveSubjectiveThresholdSweep: threshold.objectiveSubjective,
    recommendedConfidencePolicy: actionabilityPolicy,
    promptSelection: selection,
    proposedGatePolicy: "Run high-confidence objective ERROR findings through ACTIONABLE simulation; keep subjective findings and INFO/WARNING as REVIEW_ONLY; ignore below the baseline-derived confidence policy for automatic gate decisions. This remains evaluation-only and is not safe for production automatic blocking.",
    brokenRenderingAnalysis,
  };
}

async function main(): Promise<void> {
  const report = await runPrecisionExperiment();
  const testDir = path.dirname(fileURLToPath(import.meta.url));
  const fixtureDir = process.env.PPT_VISUAL_QA_GOLD_DIR || path.join(testDir, "fixtures", "visual-qa-gold");
  const outputDir = process.env.PPT_VISUAL_QA_EXPERIMENT_DIR || path.join(fixtureDir, "precision-experiment");
  await fs.writeFile(path.join(outputDir, "precision-experiment-report.json"), `${JSON.stringify(report, null, 2)}\n`, "utf8");
  process.stdout.write(`${JSON.stringify({ outputDir, newMainRequests: report.newMainRequests, newApiRequests: report.newApiRequests, repairRequests: report.repairRequests, recommendedConfidencePolicy: report.recommendedConfidencePolicy, promptSelection: report.promptSelection, baseline: report.baseline.metrics, candidateA: report.candidateA.metrics, candidateB: report.candidateB.metrics }, null, 2)}\n`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(error => { process.stderr.write(`${error instanceof Error ? error.stack ?? error.message : String(error)}\n`); process.exitCode = 1; });
}
