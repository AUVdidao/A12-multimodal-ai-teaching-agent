import crypto from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { HarnessConfig, loadConfig } from "../src/config.js";
import { HarnessError } from "../src/domain.js";
import {
  parseSlideResult,
  VisualIssueCode,
  VisualIssueSeverity,
  VisualQaIssue,
  VisualQaProvider,
  VisualQaSlideInput,
  VISUAL_ISSUE_CODES,
} from "../src/visual-qa.js";
import { OBJECTIVE_CODES, SUBJECTIVE_CODES, candidateSystemPrompt, issueGroup } from "./visual-qa-precision-experiment.js";

export type HoldoutGoldCase = {
  caseId: string;
  fileName: string;
  slideNumber: number;
  slideType: string;
  knownClean: boolean;
  expectedCodes: VisualIssueCode[];
  expectedMaxSeverity: VisualIssueSeverity;
  notes: string;
  sourceFixture: string;
  mutationDescription: string;
  sourceSlideNumber: number;
};

export type HoldoutCaseResult = {
  caseId: string;
  fileName: string;
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

export type CodeMetric = { tp: number; fp: number; fn: number; precision: number; recall: number; support: number };
export type GroupMetric = { tp: number; fp: number; fn: number; precision: number; recall: number };
export type ActionabilityMetric = {
  objectiveThreshold: number;
  subjectiveThreshold: number;
  predicted: number;
  truePositive: number;
  falsePositive: number;
  expected: number;
  precision: number;
  recall: number;
  fpCount: number;
  cleanActionableCases: number;
  brokenActionableCases: number;
};

export type HoldoutMetrics = {
  rawPrecision: number;
  rawRecall: number;
  falsePositiveRate: number;
  cleanPassRate: number;
  errorPrecision: number;
  errorRecall: number;
  cleanHardFailRate: number;
  brokenHardFailRecall: number;
  objective: GroupMetric;
  subjective: GroupMetric;
  tp: number;
  fp: number;
  fn: number;
  perCode: Record<VisualIssueCode, CodeMetric>;
  actionability: ActionabilityMetric;
  cleanChartIssues: number;
  cleanTableIssues: number;
  cleanFlowIssues: number;
};

export type UsageTotals = { promptTokens: number; completionTokens: number; totalTokens: number; observedResponses: number };
export type RequestLedger = { mainRequests: number; repairRequests: number; elapsedMs: number; usage: UsageTotals };
export type HoldoutVariantReport = {
  variant: "BASELINE" | "CANDIDATE_A";
  model: string;
  promptHash: string;
  mainRequests: number;
  repairRequests: number;
  totalRequests: number;
  elapsedMs: number;
  usage: UsageTotals;
  metrics: HoldoutMetrics;
  cases: HoldoutCaseResult[];
};

export type FrozenCandidateA = {
  version: number;
  variant: "CANDIDATE_A";
  sourceCommit: string;
  model: string;
  prompt: { review: string; repair: string };
  promptHash: string;
  policyHash: string;
  taxonomy: { objective: VisualIssueCode[]; subjective: VisualIssueCode[]; all: VisualIssueCode[] };
  policy: { objectiveThreshold: number; subjectiveThreshold: number; automaticRepairEligible: boolean; actionability: string };
};

export type GeneralizationResult = {
  conclusion: "CANDIDATE_A_GENERALIZES" | "PARTIALLY_GENERALIZES" | "DOES_NOT_GENERALIZE";
  productionRecommendation: "ADVISORY_ONLY" | "NOT_RECOMMENDED";
  autoRepairEligible: false;
  criteria: {
    cleanHardFailNotWorse: boolean;
    objectivePrecisionImproves: boolean;
    brokenHardFailRecallNotCollapsed: boolean;
    errorPrecisionUsable: boolean;
    specialCleanPagesSafe: boolean;
  };
  rationale: string;
};

const RESPONSE_SCHEMA = {
  type: "object",
  additionalProperties: false,
  required: ["slideNumber", "issues"],
  properties: {
    slideNumber: { type: "integer", minimum: 1 },
    issues: { type: "array", items: { type: "object", additionalProperties: false, required: ["slideNumber", "code", "severity", "confidence", "description", "repairHint"], properties: { slideNumber: { type: "integer", minimum: 1 }, code: { type: "string", enum: VISUAL_ISSUE_CODES }, severity: { type: "string", enum: ["INFO", "WARNING", "ERROR"] }, confidence: { type: "number", minimum: 0, maximum: 1 }, description: { type: "string" }, repairHint: { type: "string" } } } },
  },
} as const;

const BASELINE_PROMPTS = {
  review: [
    "You are a rendered-slide visual QA classifier.",
    "Inspect the supplied PNG itself and classify visible presentation quality only.",
    "Do not judge factual correctness, evidence truth, teaching objectives, or whether the lesson content is pedagogically correct; those are out of scope.",
    "Report only visible layout, legibility, contrast, hierarchy, density, clipping, overlap, and rendering defects.",
    "Use only these issue codes: " + VISUAL_ISSUE_CODES.join(", ") + ".",
    "Use severity INFO, WARNING, or ERROR and confidence from 0 to 1.",
    "Return one JSON object only. Do not use Markdown prose or code fences.",
  ].join(" "),
  repair: [
    "You are a rendered-slide visual QA classifier.",
    "Inspect the supplied PNG itself and classify visible presentation quality only.",
    "Do not judge factual correctness, evidence truth, teaching objectives, or whether the lesson content is pedagogically correct; those are out of scope.",
    "Report only visible layout, legibility, contrast, hierarchy, density, clipping, overlap, and rendering defects.",
    "Use only these issue codes: " + VISUAL_ISSUE_CODES.join(", ") + ".",
    "Use severity INFO, WARNING, or ERROR and confidence from 0 to 1.",
    "The previous response failed local schema validation. Return one corrected JSON object only. Do not use Markdown fences.",
  ].join(" "),
};

const SEVERITY_RANK: Record<VisualIssueSeverity, number> = { INFO: 1, WARNING: 2, ERROR: 3 };
const ACTIONABILITY_POLICY = { objectiveThreshold: 0.90, subjectiveThreshold: 0.95 } as const;

export function ratio(numerator: number, denominator: number): number {
  return denominator === 0 ? 0 : Number((numerator / denominator).toFixed(4));
}

export function sha256Json(value: unknown): string {
  return crypto.createHash("sha256").update(JSON.stringify(value)).digest("hex");
}

export async function loadHoldoutGoldSet(filePath: string): Promise<HoldoutGoldCase[]> {
  const raw = JSON.parse(await fs.readFile(filePath, "utf8")) as { version?: unknown; cases?: unknown };
  if (raw.version !== 2 || !Array.isArray(raw.cases)) throw new HarnessError("HOLDOUT_GOLD_INVALID", "Holdout Gold Set version or cases are invalid", 422);
  return validateHoldoutGold(raw.cases.map(parseGoldCase));
}

export function validateHoldoutGold(cases: HoldoutGoldCase[]): HoldoutGoldCase[] {
  if (cases.length < 26 || cases.length > 40) throw new HarnessError("HOLDOUT_GOLD_INVALID", "Holdout must contain 26-40 cases", 422);
  const clean = cases.filter(item => item.knownClean);
  const broken = cases.filter(item => !item.knownClean);
  if (clean.length !== 12 || broken.length !== 18) throw new HarnessError("HOLDOUT_GOLD_INVALID", "PPT-016 requires exactly 12 clean and 18 broken cases", 422);
  const missingCodes = VISUAL_ISSUE_CODES.filter(code => !cases.some(item => item.expectedCodes.includes(code)));
  if (missingCodes.length) throw new HarnessError("HOLDOUT_GOLD_INVALID", `Holdout Gold Set is missing codes: ${missingCodes.join(", ")}`, 422);
  if (broken.filter(item => item.expectedCodes.length >= 2).length < 4) throw new HarnessError("HOLDOUT_GOLD_INVALID", "Holdout requires at least four multi-defect broken cases", 422);
  if (cases.some(item => !item.caseId || !item.fileName || !item.slideType || !item.notes || !item.sourceFixture || !item.mutationDescription || item.sourceSlideNumber < 1)) {
    throw new HarnessError("HOLDOUT_GOLD_INVALID", "Holdout case metadata is incomplete", 422);
  }
  return cases;
}

export async function assertHoldoutIntegrity(goldCases: HoldoutGoldCase[], fixtureDir: string, integrityPath: string): Promise<Record<string, unknown>> {
  const integrity = JSON.parse(await fs.readFile(integrityPath, "utf8")) as Record<string, unknown>;
  if (integrity.passed !== true || integrity.caseCount !== goldCases.length || integrity.cleanCount !== 12 || integrity.brokenCount !== 18) {
    throw new HarnessError("HOLDOUT_INTEGRITY_FAILED", "Holdout integrity report did not pass", 422);
  }
  const seen = new Set<string>();
  for (const goldCase of goldCases) {
    const bytes = await fs.readFile(path.join(fixtureDir, goldCase.fileName));
    const digest = crypto.createHash("sha256").update(bytes).digest("hex");
    if (seen.has(digest)) throw new HarnessError("HOLDOUT_INTEGRITY_FAILED", `Duplicate PNG detected: ${goldCase.caseId}`, 422);
    seen.add(digest);
    if (bytes.byteLength < 24 || bytes.readUInt32BE(16) < 1 || bytes.readUInt32BE(20) < 1) throw new HarnessError("HOLDOUT_INTEGRITY_FAILED", `Invalid PNG dimensions: ${goldCase.caseId}`, 422);
  }
  return integrity;
}

export function summarizeHoldoutCases(cases: HoldoutCaseResult[]): HoldoutMetrics {
  const perCode = Object.fromEntries(VISUAL_ISSUE_CODES.map(code => [code, { tp: 0, fp: 0, fn: 0, precision: 0, recall: 0, support: 0 }])) as Record<VisualIssueCode, CodeMetric>;
  let tp = 0;
  let fp = 0;
  let fn = 0;
  let errorTp = 0;
  let errorFp = 0;
  let errorFn = 0;
  for (const item of cases) {
    const expected = new Set(item.expectedCodes);
    const predicted = new Map(item.predictedIssues.map(issue => [issue.code, issue]));
    for (const code of VISUAL_ISSUE_CODES) {
      const expectedCode = expected.has(code);
      const predictedIssue = predicted.get(code);
      const predictedCode = Boolean(predictedIssue);
      if (expectedCode) perCode[code].support += 1;
      if (expectedCode && predictedCode) { perCode[code].tp += 1; tp += 1; }
      if (!expectedCode && predictedCode) { perCode[code].fp += 1; fp += 1; }
      if (expectedCode && !predictedCode) { perCode[code].fn += 1; fn += 1; }
      const expectedError = expectedCode && item.expectedMaxSeverity === "ERROR";
      const predictedError = predictedIssue?.severity === "ERROR";
      if (expectedError && predictedError) errorTp += 1;
      if (!expectedError && predictedError) errorFp += 1;
      if (expectedError && !predictedError) errorFn += 1;
    }
  }
  for (const code of VISUAL_ISSUE_CODES) {
    perCode[code].precision = ratio(perCode[code].tp, perCode[code].tp + perCode[code].fp);
    perCode[code].recall = ratio(perCode[code].tp, perCode[code].tp + perCode[code].fn);
  }
  const groupMetric = (codes: VisualIssueCode[]): GroupMetric => {
    const total = codes.reduce((sum, code) => ({ tp: sum.tp + perCode[code].tp, fp: sum.fp + perCode[code].fp, fn: sum.fn + perCode[code].fn }), { tp: 0, fp: 0, fn: 0 });
    return { ...total, precision: ratio(total.tp, total.tp + total.fp), recall: ratio(total.tp, total.tp + total.fn) };
  };
  const clean = cases.filter(item => item.knownClean);
  const broken = cases.filter(item => !item.knownClean);
  const cleanWithIssues = clean.filter(item => item.predictedIssues.length > 0).length;
  const cleanHardFails = clean.filter(item => item.predictedIssues.some(issue => issue.severity === "ERROR")).length;
  const brokenHardFails = broken.filter(item => item.predictedIssues.some(issue => issue.severity === "ERROR")).length;
  const actionability = summarizeActionability(cases);
  return {
    rawPrecision: ratio(tp, tp + fp),
    rawRecall: ratio(tp, tp + fn),
    falsePositiveRate: ratio(cleanWithIssues, clean.length),
    cleanPassRate: ratio(clean.length - cleanWithIssues, clean.length),
    errorPrecision: ratio(errorTp, errorTp + errorFp),
    errorRecall: ratio(errorTp, errorTp + errorFn),
    cleanHardFailRate: ratio(cleanHardFails, clean.length),
    brokenHardFailRecall: ratio(brokenHardFails, broken.length),
    objective: groupMetric(OBJECTIVE_CODES),
    subjective: groupMetric(SUBJECTIVE_CODES),
    tp, fp, fn, perCode, actionability,
    cleanChartIssues: clean.filter(item => item.caseId.includes("chart") && item.predictedIssues.length > 0).length,
    cleanTableIssues: clean.filter(item => item.caseId.includes("table") && item.predictedIssues.length > 0).length,
    cleanFlowIssues: clean.filter(item => (item.caseId.includes("flow") || item.caseId.includes("timeline") || item.caseId.includes("diagram")) && item.predictedIssues.length > 0).length,
  };
}

export function summarizeActionability(cases: HoldoutCaseResult[]): ActionabilityMetric {
  let predicted = 0;
  let truePositive = 0;
  let falsePositive = 0;
  let expected = 0;
  let cleanActionableCases = 0;
  let brokenActionableCases = 0;
  for (const item of cases) {
    expected += item.expectedCodes.filter(code => issueGroup(code) === "objective").length;
    let caseActionable = false;
    const expectedCodes = new Set(item.expectedCodes);
    for (const issue of item.predictedIssues) {
      const threshold = issueGroup(issue.code) === "objective" ? ACTIONABILITY_POLICY.objectiveThreshold : ACTIONABILITY_POLICY.subjectiveThreshold;
      if (issue.confidence < threshold || issue.severity !== "ERROR" || issueGroup(issue.code) !== "objective") continue;
      predicted += 1;
      caseActionable = true;
      if (expectedCodes.has(issue.code)) truePositive += 1;
      else falsePositive += 1;
    }
    if (caseActionable && item.knownClean) cleanActionableCases += 1;
    if (caseActionable && !item.knownClean) brokenActionableCases += 1;
  }
  return { ...ACTIONABILITY_POLICY, predicted, truePositive, falsePositive, expected, precision: ratio(truePositive, predicted), recall: ratio(truePositive, expected), fpCount: falsePositive, cleanActionableCases, brokenActionableCases };
}

export function classifyGeneralization(baseline: HoldoutMetrics, candidateA: HoldoutMetrics): GeneralizationResult {
  const criteria = {
    cleanHardFailNotWorse: candidateA.cleanHardFailRate <= baseline.cleanHardFailRate,
    objectivePrecisionImproves: candidateA.objective.precision > baseline.objective.precision,
    brokenHardFailRecallNotCollapsed: candidateA.brokenHardFailRecall >= baseline.brokenHardFailRecall - 0.10,
    errorPrecisionUsable: candidateA.errorPrecision >= 0.25,
    specialCleanPagesSafe: candidateA.cleanChartIssues + candidateA.cleanTableIssues + candidateA.cleanFlowIssues <= 1,
  };
  const corePass = criteria.cleanHardFailNotWorse && criteria.objectivePrecisionImproves;
  const allPass = Object.values(criteria).every(Boolean);
  const conclusion = allPass ? "CANDIDATE_A_GENERALIZES" : corePass ? "PARTIALLY_GENERALIZES" : "DOES_NOT_GENERALIZE";
  const rationale = `cleanHardFailNotWorse=${criteria.cleanHardFailNotWorse}; objectivePrecisionImproves=${criteria.objectivePrecisionImproves}; brokenHardFailRecallNotCollapsed=${criteria.brokenHardFailRecallNotCollapsed}; errorPrecisionUsable=${criteria.errorPrecisionUsable}; specialCleanPagesSafe=${criteria.specialCleanPagesSafe}`;
  return { conclusion, productionRecommendation: allPass ? "ADVISORY_ONLY" : "NOT_RECOMMENDED", autoRepairEligible: false, criteria, rationale };
}

export function holdoutInput(goldCase: HoldoutGoldCase, bytes: Uint8Array): VisualQaSlideInput {
  return {
    slideNumber: 1,
    imageDataUrl: `data:image/png;base64,${Buffer.from(bytes).toString("base64")}`,
    expected: {
      title: goldCase.slideType,
      pedagogicalRole: "CONCEPT",
      teachingPurpose: "Inspect visible presentation quality only",
      visualIntent: { type: "MIXED", description: "Evaluate visible layout, legibility, and rendering" },
      layoutIntent: "Evaluate visible layout only",
      density: "MEDIUM",
      importance: "CORE",
    },
  };
}

export class HoldoutKimiProvider implements VisualQaProvider {
  constructor(private readonly config: HarnessConfig, private readonly variant: "BASELINE" | "CANDIDATE_A", private readonly frozen: FrozenCandidateA | undefined, private readonly ledger: RequestLedger) {}

  review(input: VisualQaSlideInput): Promise<unknown> { return this.request(input, "REVIEW"); }
  repair(input: VisualQaSlideInput, invalidResult: unknown, reason: string): Promise<unknown> { return this.request(input, "REPAIR", reason, invalidResult); }

  private async request(input: VisualQaSlideInput, mode: "REVIEW" | "REPAIR", reason?: string, invalidResult?: unknown): Promise<unknown> {
    if (!this.config.kimiApiKey) throw new HarnessError("VISUAL_REVIEW_PROVIDER_UNAVAILABLE", "Visual QA requires a server-side MOONSHOT_API_KEY", 503);
    const started = Date.now();
    if (mode === "REVIEW") this.ledger.mainRequests += 1;
    else this.ledger.repairRequests += 1;
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), this.config.kimiTimeoutMs);
    try {
      const response = await fetch(`${this.config.kimiBaseUrl}/chat/completions`, {
        method: "POST",
        signal: controller.signal,
        headers: { "Content-Type": "application/json", "Authorization": `Bearer ${this.config.kimiApiKey}` },
        body: JSON.stringify({
          model: this.config.kimiModel,
          thinking: { type: "disabled" },
          response_format: { type: "json_schema", json_schema: { name: "visual_qa_slide_result_v1", strict: true, schema: RESPONSE_SCHEMA } },
          messages: [
            { role: "system", content: this.systemPrompt(mode) },
            { role: "user", content: holdoutUserContent(input, mode, reason, invalidResult) },
          ],
        }),
      });
      if (!response.ok) throw new HarnessError("VISUAL_REVIEW_PROVIDER_FAILED", `Visual QA provider failed with HTTP ${response.status}`, 502);
      const payload = await response.json() as { choices?: Array<{ message?: { content?: unknown } }>; usage?: { prompt_tokens?: number; completion_tokens?: number; total_tokens?: number } };
      const usage = payload.usage;
      if (usage) {
        this.ledger.usage.observedResponses += 1;
        this.ledger.usage.promptTokens += usage.prompt_tokens ?? 0;
        this.ledger.usage.completionTokens += usage.completion_tokens ?? 0;
        this.ledger.usage.totalTokens += usage.total_tokens ?? 0;
      }
      const content = responseContent(payload.choices?.[0]?.message?.content);
      if (!content) return "";
      try { return JSON.parse(content) as unknown; } catch { return content; }
    } catch (error) {
      if (error instanceof HarnessError) throw error;
      if (error instanceof Error && error.name === "AbortError") throw new HarnessError("VISUAL_REVIEW_PROVIDER_UNAVAILABLE", "Visual QA provider timed out", 503);
      throw new HarnessError("VISUAL_REVIEW_PROVIDER_UNAVAILABLE", "Visual QA provider is unavailable", 503);
    } finally {
      clearTimeout(timeout);
      this.ledger.elapsedMs += Date.now() - started;
    }
  }

  private systemPrompt(mode: "REVIEW" | "REPAIR"): string {
    if (this.variant === "CANDIDATE_A") {
      if (!this.frozen) throw new HarnessError("HOLDOUT_PROMPT_INVALID", "Frozen Candidate A prompt is required", 422);
      return this.frozen.prompt[mode === "REVIEW" ? "review" : "repair"];
    }
    return BASELINE_PROMPTS[mode === "REVIEW" ? "review" : "repair"];
  }
}

export async function evaluateHoldoutVariant(goldCases: HoldoutGoldCase[], fixtureDir: string, config: HarnessConfig, variant: "BASELINE" | "CANDIDATE_A", frozen?: FrozenCandidateA): Promise<HoldoutVariantReport> {
  const ledger: RequestLedger = { mainRequests: 0, repairRequests: 0, elapsedMs: 0, usage: { promptTokens: 0, completionTokens: 0, totalTokens: 0, observedResponses: 0 } };
  const started = Date.now();
  const results: HoldoutCaseResult[] = [];
  for (const goldCase of goldCases) {
    const bytes = await fs.readFile(path.join(fixtureDir, goldCase.fileName));
    const input = holdoutInput(goldCase, bytes);
    const provider = new HoldoutKimiProvider(config, variant, frozen, ledger);
    let repaired = false;
    let raw: unknown;
    try {
      raw = await provider.review(input);
    } catch (error) {
      results.push(failedCase(goldCase, "PROVIDER_ERROR", error, repaired));
      continue;
    }
    let parsed = parseSlideResult(raw, 1);
    if (!parsed.result) {
      repaired = true;
      try { raw = await provider.repair(input, raw, parsed.reason); }
      catch (error) { results.push(failedCase(goldCase, "PROVIDER_ERROR", error, repaired)); continue; }
      parsed = parseSlideResult(raw, 1);
    }
    if (!parsed.result) { results.push(failedCase(goldCase, "INVALID_RESULT", new HarnessError("HOLDOUT_INVALID_RESULT", "Visual QA result failed local schema validation", 502), repaired)); continue; }
    results.push({ caseId: goldCase.caseId, fileName: goldCase.fileName, knownClean: goldCase.knownClean, expectedCodes: goldCase.expectedCodes, predictedCodes: uniqueCodes(parsed.result.issues), predictedIssues: parsed.result.issues, expectedMaxSeverity: goldCase.expectedMaxSeverity, predictedMaxSeverity: maxSeverity(parsed.result.issues), outcome: "SUCCESS", repaired });
  }
  return { variant, model: config.kimiModel, promptHash: variant === "CANDIDATE_A" ? frozen?.promptHash ?? "" : sha256Json(BASELINE_PROMPTS), mainRequests: ledger.mainRequests, repairRequests: ledger.repairRequests, totalRequests: ledger.mainRequests + ledger.repairRequests, elapsedMs: Date.now() - started, usage: ledger.usage, metrics: summarizeHoldoutCases(results), cases: results };
}

export async function runHoldoutVariance(goldCases: HoldoutGoldCase[], fixtureDir: string, config: HarnessConfig, variant: "BASELINE" | "CANDIDATE_A", frozen: FrozenCandidateA | undefined, caseIds: string[], repeats = 2): Promise<{ variant: "BASELINE" | "CANDIDATE_A"; repeats: number; totalRequests: number; elapsedMs: number; cases: Array<{ caseId: string; observations: Array<{ repeat: number; predictedCodes: VisualIssueCode[]; predictedMaxSeverity?: VisualIssueSeverity; outcome: string; repaired: boolean }>; codeSetStable: boolean; severityStable: boolean }> }> {
  const selected = caseIds.map(caseId => {
    const goldCase = goldCases.find(item => item.caseId === caseId);
    if (!goldCase) throw new HarnessError("HOLDOUT_VARIANCE_INVALID", `Variance case not found: ${caseId}`, 422);
    return goldCase;
  });
  const ledger: RequestLedger = { mainRequests: 0, repairRequests: 0, elapsedMs: 0, usage: { promptTokens: 0, completionTokens: 0, totalTokens: 0, observedResponses: 0 } };
  const started = Date.now();
  const cases = [] as Array<{ caseId: string; observations: Array<{ repeat: number; predictedCodes: VisualIssueCode[]; predictedMaxSeverity?: VisualIssueSeverity; outcome: string; repaired: boolean }>; codeSetStable: boolean; severityStable: boolean }>;
  for (const goldCase of selected) {
    const bytes = await fs.readFile(path.join(fixtureDir, goldCase.fileName));
    const observations = [] as Array<{ repeat: number; predictedCodes: VisualIssueCode[]; predictedMaxSeverity?: VisualIssueSeverity; outcome: string; repaired: boolean }>;
    for (let repeat = 1; repeat <= repeats; repeat += 1) {
      const provider = new HoldoutKimiProvider(config, variant, frozen, ledger);
      let repaired = false;
      let raw: unknown;
      try { raw = await provider.review(holdoutInput(goldCase, bytes)); }
      catch { observations.push({ repeat, predictedCodes: [], outcome: "PROVIDER_ERROR", repaired }); continue; }
      let parsed = parseSlideResult(raw, 1);
      if (!parsed.result) {
        repaired = true;
        try { raw = await provider.repair(holdoutInput(goldCase, bytes), raw, parsed.reason); }
        catch { observations.push({ repeat, predictedCodes: [], outcome: "PROVIDER_ERROR", repaired }); continue; }
        parsed = parseSlideResult(raw, 1);
      }
      observations.push(parsed.result ? { repeat, predictedCodes: uniqueCodes(parsed.result.issues), predictedMaxSeverity: maxSeverity(parsed.result.issues), outcome: "SUCCESS", repaired } : { repeat, predictedCodes: [], outcome: "INVALID_RESULT", repaired });
    }
    cases.push({ caseId: goldCase.caseId, observations, codeSetStable: new Set(observations.map(item => JSON.stringify(item.predictedCodes))).size <= 1, severityStable: new Set(observations.map(item => item.predictedMaxSeverity ?? "NONE")).size <= 1 });
  }
  return { variant, repeats, totalRequests: ledger.mainRequests + ledger.repairRequests, elapsedMs: Date.now() - started, cases };
}

function parseGoldCase(value: unknown): HoldoutGoldCase {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new HarnessError("HOLDOUT_GOLD_INVALID", "Gold case must be an object", 422);
  const item = value as Record<string, unknown>;
  const expectedCodes = Array.isArray(item.expectedCodes) ? item.expectedCodes : [];
  if (typeof item.caseId !== "string" || typeof item.fileName !== "string" || typeof item.slideType !== "string" || typeof item.slideNumber !== "number" || !Number.isSafeInteger(item.slideNumber) || typeof item.knownClean !== "boolean" || typeof item.expectedMaxSeverity !== "string" || typeof item.notes !== "string" || typeof item.sourceFixture !== "string" || typeof item.mutationDescription !== "string" || typeof item.sourceSlideNumber !== "number" || !expectedCodes.every(code => typeof code === "string" && VISUAL_ISSUE_CODES.includes(code as VisualIssueCode))) throw new HarnessError("HOLDOUT_GOLD_INVALID", "Holdout Gold case schema is invalid", 422);
  return { caseId: item.caseId, fileName: item.fileName, slideNumber: item.slideNumber, slideType: item.slideType, knownClean: item.knownClean, expectedCodes: expectedCodes as VisualIssueCode[], expectedMaxSeverity: item.expectedMaxSeverity as VisualIssueSeverity, notes: item.notes, sourceFixture: item.sourceFixture, mutationDescription: item.mutationDescription, sourceSlideNumber: item.sourceSlideNumber };
}

function failedCase(goldCase: HoldoutGoldCase, outcome: "PROVIDER_ERROR" | "INVALID_RESULT", error: unknown, repaired: boolean): HoldoutCaseResult {
  const failure = error instanceof Error ? error : new Error(String(error));
  return { caseId: goldCase.caseId, fileName: goldCase.fileName, knownClean: goldCase.knownClean, expectedCodes: goldCase.expectedCodes, predictedCodes: [], predictedIssues: [], expectedMaxSeverity: goldCase.expectedMaxSeverity, outcome, repaired, errorCode: error instanceof HarnessError ? error.code : "HOLDOUT_PROVIDER_ERROR", errorMessage: failure.message.slice(0, 240) };
}

function uniqueCodes(issues: VisualQaIssue[]): VisualIssueCode[] { return [...new Set(issues.map(issue => issue.code))]; }
function maxSeverity(issues: VisualQaIssue[]): VisualIssueSeverity | undefined { return issues.reduce<VisualIssueSeverity | undefined>((current, issue) => !current || SEVERITY_RANK[issue.severity] > SEVERITY_RANK[current] ? issue.severity : current, undefined); }
function holdoutUserContent(input: VisualQaSlideInput, mode: "REVIEW" | "REPAIR", reason?: string, invalidResult?: unknown): Array<{ type: "text"; text: string } | { type: "image_url"; image_url: { url: string } }> {
  const context = { slideNumber: input.slideNumber, expectedVisualContext: input.expected, outputShape: { slideNumber: input.slideNumber, issues: [{ slideNumber: input.slideNumber, code: "TEXT_OVERFLOW", severity: "WARNING", confidence: 0.8, description: "short visible description", repairHint: "short localized layout hint" }] }, ...(mode === "REPAIR" ? { schemaRepair: { reason, previousResponse: truncate(JSON.stringify(invalidResult) ?? String(invalidResult)) } } : {}) };
  return [{ type: "text", text: JSON.stringify(context) }, { type: "image_url", image_url: { url: input.imageDataUrl } }];
}
function responseContent(value: unknown): string | undefined { if (typeof value === "string" && value.trim()) return value.trim(); if (!Array.isArray(value)) return undefined; const text = value.map(item => typeof item === "object" && item !== null && "text" in item && typeof item.text === "string" ? item.text : "").join("").trim(); return text || undefined; }
function truncate(value: string): string { return value.length > 2000 ? `${value.slice(0, 2000)}...` : value; }

export async function loadFrozenCandidateA(filePath: string): Promise<FrozenCandidateA> {
  const frozen = JSON.parse(await fs.readFile(filePath, "utf8")) as FrozenCandidateA;
  if (frozen.version !== 2 || frozen.variant !== "CANDIDATE_A" || !frozen.promptHash || !frozen.policyHash || frozen.policy.objectiveThreshold !== ACTIONABILITY_POLICY.objectiveThreshold || frozen.policy.subjectiveThreshold !== ACTIONABILITY_POLICY.subjectiveThreshold) throw new HarnessError("HOLDOUT_PROMPT_INVALID", "candidate-a-frozen.json is incomplete or policy drifted", 422);
  return frozen;
}

export async function runHoldoutEvaluation(options: { fixtureDir: string; goldSetPath: string; integrityPath: string; frozenPath: string; outputDir: string; config?: HarnessConfig }): Promise<{ report: Record<string, unknown>; baseline: HoldoutVariantReport; candidateA: HoldoutVariantReport }> {
  const config = options.config ?? loadConfig();
  const goldCases = await loadHoldoutGoldSet(options.goldSetPath);
  const integrity = await assertHoldoutIntegrity(goldCases, options.fixtureDir, options.integrityPath);
  const frozen = await loadFrozenCandidateA(options.frozenPath);
  const currentCommit = process.env.PPT_VISUAL_QA_SOURCE_COMMIT?.trim();
  if (currentCommit && frozen.sourceCommit !== currentCommit) throw new HarnessError("HOLDOUT_PROMPT_INVALID", "Frozen Candidate A source commit does not match requested source commit", 422);
  const baseline = await evaluateHoldoutVariant(goldCases, options.fixtureDir, config, "BASELINE");
  const candidateA = await evaluateHoldoutVariant(goldCases, options.fixtureDir, config, "CANDIDATE_A", frozen);
  const varianceCaseIds = ["clean-06-chart", "broken-07-chart-complex"];
  const variance = {
    caseIds: varianceCaseIds,
    repeatsPerCase: 2,
    baseline: await runHoldoutVariance(goldCases, options.fixtureDir, config, "BASELINE", undefined, varianceCaseIds, 2),
    candidateA: await runHoldoutVariance(goldCases, options.fixtureDir, config, "CANDIDATE_A", frozen, varianceCaseIds, 2),
  };
  const providerErrorCount = [...baseline.cases, ...candidateA.cases].filter(item => item.outcome === "PROVIDER_ERROR").length;
  const evaluationStatus = providerErrorCount > 0 ? "PROVIDER_BLOCKED" : "COMPLETE";
  const generalization = evaluationStatus === "COMPLETE" ? classifyGeneralization(baseline.metrics, candidateA.metrics) : null;
  const report = {
    version: 2,
    generatedAt: new Date().toISOString(),
    sourceCommit: frozen.sourceCommit,
    candidateAFrozen: { promptHash: frozen.promptHash, policyHash: frozen.policyHash, sourceCommit: frozen.sourceCommit },
    holdout: { caseCount: goldCases.length, cleanCount: 12, brokenCount: 18, goldSetSha256: sha256Json(goldCases), integrity, specialCleanCaseIds: varianceCaseIds.slice(0, 1) },
    requestBudget: { mainRequestCap: 60, mainRequests: baseline.mainRequests + candidateA.mainRequests, repairRequests: baseline.repairRequests + candidateA.repairRequests, totalMainAndRepairRequests: baseline.totalRequests + candidateA.totalRequests },
    evaluationStatus,
    providerBlocker: providerErrorCount > 0 ? { providerErrorCount, firstErrorCode: baseline.cases.find(item => item.outcome === "PROVIDER_ERROR")?.errorCode, firstErrorMessage: baseline.cases.find(item => item.outcome === "PROVIDER_ERROR")?.errorMessage } : undefined,
    baseline,
    candidateA,
    variance,
    generalization,
    production: { recommendation: generalization?.productionRecommendation ?? "NOT_RECOMMENDED", autoRepairEligible: false, productionFilesModified: false },
  };
  await fs.mkdir(options.outputDir, { recursive: true });
  await fs.writeFile(path.join(options.outputDir, "baseline-holdout-report.json"), `${JSON.stringify(baseline, null, 2)}\n`, "utf8");
  await fs.writeFile(path.join(options.outputDir, "candidate-a-holdout-report.json"), `${JSON.stringify(candidateA, null, 2)}\n`, "utf8");
  await fs.writeFile(path.join(options.outputDir, "chart-variance-report.json"), `${JSON.stringify(variance, null, 2)}\n`, "utf8");
  await fs.writeFile(path.join(options.outputDir, "holdout-evaluation-report.json"), `${JSON.stringify(report, null, 2)}\n`, "utf8");
  return { report, baseline, candidateA };
}

export function baselinePromptHash(): string { return sha256Json(BASELINE_PROMPTS); }

export const holdoutActionabilityPolicy = ACTIONABILITY_POLICY;
