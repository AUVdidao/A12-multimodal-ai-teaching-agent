import crypto from "node:crypto";
import { HarnessConfig } from "./config.js";
import { HarnessError, PresentationJob, Slide, SlideSpec } from "./domain.js";
import { PptSkillRunnerClient, RunnerGeneration } from "./runner-client.js";

export const VISUAL_ISSUE_CODES = [
  "TEXT_OVERFLOW",
  "TEXT_TOO_SMALL",
  "ELEMENT_OVERLAP",
  "ELEMENT_CLIPPED",
  "UNBALANCED_LAYOUT",
  "EXCESSIVE_EMPTY_SPACE",
  "LOW_CONTRAST",
  "VISUAL_HIERARCHY_WEAK",
  "TABLE_UNREADABLE",
  "CHART_UNREADABLE",
  "FLOW_UNREADABLE",
  "DENSE_CONTENT",
  "BROKEN_RENDERING",
] as const;
export type VisualIssueCode = typeof VISUAL_ISSUE_CODES[number];
export type VisualIssueSeverity = "INFO" | "WARNING" | "ERROR";

export type VisualQaIssue = {
  slideNumber: number;
  code: VisualIssueCode;
  severity: VisualIssueSeverity;
  confidence: number;
  description: string;
  repairHint: string;
};

export type VisualQaResult = {
  jobId: string;
  passed: boolean;
  reviewedSlideCount: number;
  issues: VisualQaIssue[];
};

export type VisualQaSlideInput = {
  slideNumber: number;
  imageDataUrl: string;
  expected: {
    title: string;
    pedagogicalRole: Slide["pedagogicalRole"];
    teachingPurpose: string;
    visualIntent: Slide["visualIntent"];
    layoutIntent: string;
    density: Slide["density"];
    importance: Slide["importance"];
  };
};

export const VISUAL_QA_SLIDE_JSON_SCHEMA = {
  type: "object",
  additionalProperties: false,
  required: ["slideNumber", "issues"],
  properties: {
    slideNumber: { type: "integer" },
    issues: {
      type: "array",
      items: {
        type: "object",
        additionalProperties: false,
        required: ["slideNumber", "code", "severity", "confidence", "description", "repairHint"],
        properties: {
          slideNumber: { type: "integer" },
          code: { type: "string", enum: [...VISUAL_ISSUE_CODES] },
          severity: { type: "string", enum: ["INFO", "WARNING", "ERROR"] },
          confidence: { type: "number", minimum: 0, maximum: 1 },
          description: { type: "string", minLength: 1, maxLength: 500 },
          repairHint: { type: "string", minLength: 1, maxLength: 300 },
        },
      },
    },
  },
} as const;

export interface VisualQaProvider {
  review(input: VisualQaSlideInput): Promise<unknown>;
  repair(input: VisualQaSlideInput, invalidResult: unknown, reason: string): Promise<unknown>;
}

type KimiContentPart =
  | { type: "text"; text: string }
  | { type: "image_url"; image_url: { url: string } };

export class KimiVisualQaProvider implements VisualQaProvider {
  constructor(private readonly config: HarnessConfig) {}

  review(input: VisualQaSlideInput): Promise<unknown> {
    return this.request(input, "REVIEW");
  }

  repair(input: VisualQaSlideInput, invalidResult: unknown, reason: string): Promise<unknown> {
    return this.request(input, "REPAIR", reason, invalidResult);
  }

  private async request(input: VisualQaSlideInput, mode: "REVIEW" | "REPAIR", reason?: string, invalidResult?: unknown): Promise<unknown> {
    if (!this.config.kimiVisionEnabled || !this.config.kimiVisionModel) {
      throw new HarnessError("VISUAL_REVIEW_MODEL_NOT_VISION_CAPABLE", "Visual QA requires an explicitly configured vision-capable model", 503);
    }
    if (!this.config.kimiApiKey) {
      throw new HarnessError("VISUAL_REVIEW_PROVIDER_UNAVAILABLE", "Visual QA requires a server-side MOONSHOT_API_KEY", 503);
    }
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), this.config.kimiTimeoutMs);
    try {
      const response = await fetch(`${this.config.kimiBaseUrl}/chat/completions`, {
        method: "POST",
        signal: controller.signal,
        headers: {
          "Content-Type": "application/json",
          "Authorization": `Bearer ${this.config.kimiApiKey}`,
        },
        body: JSON.stringify({
          model: this.config.kimiVisionModel,
          thinking: { type: "disabled" },
          response_format: {
            type: "json_schema",
            json_schema: {
              name: "visual_qa_slide_result",
              strict: true,
              schema: VISUAL_QA_SLIDE_JSON_SCHEMA,
            },
          },
          messages: [
            { role: "system", content: visualSystemPrompt(mode) },
            {
              role: "user",
              content: visualUserContent(input, mode, reason, invalidResult),
            },
          ],
        }),
      });
      if (!response.ok) {
        throw new HarnessError("VISUAL_REVIEW_PROVIDER_FAILED", `Visual QA provider failed with HTTP ${response.status}: ${safeProviderError()}`, 502);
      }
      const payload = await response.json() as { choices?: Array<{ message?: { content?: unknown } }> };
      const content = responseContent(payload.choices?.[0]?.message?.content);
      if (!content) return "";
      try {
        return JSON.parse(content) as unknown;
      } catch {
        return content;
      }
    } catch (error) {
      if (error instanceof HarnessError) throw error;
      if (error instanceof Error && error.name === "AbortError") {
        throw new HarnessError("VISUAL_REVIEW_PROVIDER_UNAVAILABLE", "Visual QA provider timed out", 503);
      }
      throw new HarnessError("VISUAL_REVIEW_PROVIDER_UNAVAILABLE", "Visual QA provider is unavailable", 503);
    } finally {
      clearTimeout(timeout);
    }
  }
}

export type PreviewDownloader = Pick<PptSkillRunnerClient, "downloadPreview">;

export class VisualQaService {
  constructor(private readonly runner: PreviewDownloader, private readonly provider: VisualQaProvider) {}

  async review(job: PresentationJob, spec: SlideSpec, generation: RunnerGeneration): Promise<VisualQaResult> {
    const expectedSlideCount = spec.slides.length;
    if (!generation.preview.rendered || generation.preview.format !== "png" || generation.preview.slideCount !== expectedSlideCount || generation.preview.files.length !== expectedSlideCount) {
      throw new HarnessError("VISUAL_REVIEW_COUNT_MISMATCH", "Visual QA preview manifest does not match the SlideSpec slide count", 502);
    }
    const issues: VisualQaIssue[] = [];
    let reviewedSlideCount = 0;
    for (const [index, file] of generation.preview.files.entries()) {
      const slide = spec.slides[file.slideNumber - 1];
      if (!slide || file.slideNumber !== index + 1 || file.slideNumber < 1 || file.slideNumber > expectedSlideCount) {
        throw new HarnessError("VISUAL_REVIEW_COUNT_MISMATCH", "Visual QA preview slide ordering is invalid", 502);
      }
      if (!controlledPreviewRef(generation.jobId, file.downloadRef, file.slideNumber)) {
        throw new HarnessError("VISUAL_REVIEW_PNG_REFERENCE_INVALID", "Visual QA rejected an uncontrolled preview reference", 502);
      }
      const bytes = await this.runner.downloadPreview(file.downloadRef);
      verifyPng(bytes, file.sizeBytes, file.sha256, file.width, file.height, file.slideNumber);
      const input: VisualQaSlideInput = {
        slideNumber: file.slideNumber,
        imageDataUrl: `data:image/png;base64,${Buffer.from(bytes).toString("base64")}`,
        expected: visualContext(slide),
      };
      let raw: unknown;
      try {
        raw = await this.provider.review(input);
      } catch (error) {
        throw error;
      }
      let parsed = parseSlideResult(raw, file.slideNumber);
      if (!parsed.result) {
        raw = await this.provider.repair(input, raw, parsed.reason);
        parsed = parseSlideResult(raw, file.slideNumber);
      }
      if (!parsed.result) {
        throw new HarnessError("VISUAL_REVIEW_INVALID_RESULT", `Visual QA result for slide ${file.slideNumber} failed schema validation`, 502);
      }
      issues.push(...parsed.result.issues);
      reviewedSlideCount += 1;
    }
    const result = {
      jobId: job.id,
      passed: !issues.some(issue => issue.severity === "ERROR"),
      reviewedSlideCount,
      issues: issues.sort(compareIssues),
    };
    return validateVisualQaResult(result, job.id, expectedSlideCount);
  }
}

export function validateVisualQaResult(value: unknown, expectedJobId: string, expectedSlideCount: number): VisualQaResult {
  if (!isRecord(value) || value.jobId !== expectedJobId || value.reviewedSlideCount !== expectedSlideCount || typeof value.passed !== "boolean" || !Array.isArray(value.issues)) {
    throw new HarnessError("VISUAL_REVIEW_COUNT_MISMATCH", "Visual QA reviewed slide count is invalid", 502);
  }
  const issues = value.issues.map(item => parseIssue(item)).filter((item): item is VisualQaIssue => item !== undefined);
  if (issues.length !== value.issues.length) throw new HarnessError("VISUAL_REVIEW_INVALID_RESULT", "Visual QA issue schema is invalid", 502);
  const derivedPassed = !issues.some(issue => issue.severity === "ERROR");
  if (value.passed !== derivedPassed) throw new HarnessError("VISUAL_REVIEW_INVALID_RESULT", "Visual QA overall pass/fail must be derived from ERROR issues", 502);
  return { jobId: expectedJobId, passed: derivedPassed, reviewedSlideCount: expectedSlideCount, issues: issues.sort(compareIssues) };
}

export function parseSlideResult(value: unknown, expectedSlideNumber: number): { result?: { slideNumber: number; issues: VisualQaIssue[] }; reason: string } {
  if (!isRecord(value) || value.slideNumber !== expectedSlideNumber || !Array.isArray(value.issues)) {
    return { reason: `Expected an object with slideNumber=${expectedSlideNumber} and an issues array` };
  }
  const issues = value.issues.map(item => parseIssue(item)).filter((item): item is VisualQaIssue => item !== undefined);
  if (issues.length !== value.issues.length || issues.some(issue => issue.slideNumber !== expectedSlideNumber)) return { reason: "Every visual issue must satisfy the VisualQaIssue schema and match the reviewed slide number" };
  return { result: { slideNumber: expectedSlideNumber, issues }, reason: "" };
}

export function verifyPng(bytes: Uint8Array, expectedSize: number, expectedSha256: string, expectedWidth: number, expectedHeight: number, slideNumber: number): void {
  const signature = [137, 80, 78, 71, 13, 10, 26, 10];
  if (bytes.byteLength !== expectedSize) throw new HarnessError("VISUAL_REVIEW_PNG_SIZE_MISMATCH", `Preview PNG size mismatch for slide ${slideNumber}`, 502);
  const digest = crypto.createHash("sha256").update(bytes).digest("hex");
  if (digest !== expectedSha256) throw new HarnessError("VISUAL_REVIEW_PNG_HASH_MISMATCH", `Preview PNG hash mismatch for slide ${slideNumber}`, 502);
  if (bytes.byteLength < 24 || !signature.every((byte, index) => bytes[index] === byte) || ascii(bytes, 12, 4) !== "IHDR") {
    throw new HarnessError("VISUAL_REVIEW_PNG_INVALID", `Preview for slide ${slideNumber} is not a PNG`, 502);
  }
  const png = Buffer.from(bytes);
  const width = png.readUInt32BE(16);
  const height = png.readUInt32BE(20);
  if (width !== expectedWidth || height !== expectedHeight || width < 1 || height < 1) {
    throw new HarnessError("VISUAL_REVIEW_PNG_DIMENSIONS_MISMATCH", `Preview PNG dimensions mismatch for slide ${slideNumber}`, 502);
  }
}

function visualContext(slide: Slide): VisualQaSlideInput["expected"] {
  return {
    title: slide.title.slice(0, 160),
    pedagogicalRole: slide.pedagogicalRole,
    teachingPurpose: slide.teachingPurpose.slice(0, 240),
    visualIntent: { type: slide.visualIntent.type, description: slide.visualIntent.description.slice(0, 240) },
    layoutIntent: slide.layoutIntent.slice(0, 240),
    density: slide.density,
    importance: slide.importance,
  };
}

function visualSystemPrompt(mode: "REVIEW" | "REPAIR"): string {
  const instruction = mode === "REPAIR"
    ? "The previous response failed local schema validation. Return one corrected JSON object only. Do not use Markdown fences."
    : "Return one JSON object only. Do not use Markdown prose or code fences.";
  return [
    "You are a rendered-slide visual QA classifier.",
    "Inspect the supplied PNG itself and classify visible presentation quality only.",
    "Do not judge factual correctness, evidence truth, teaching objectives, or whether the lesson content is pedagogically correct; those are out of scope.",
    "Report only visible layout, legibility, contrast, hierarchy, density, clipping, overlap, and rendering defects.",
    "Use only these issue codes: " + VISUAL_ISSUE_CODES.join(", ") + ".",
    "Use severity INFO, WARNING, or ERROR and confidence from 0 to 1.",
    instruction,
  ].join(" ");
}

function visualUserContent(input: VisualQaSlideInput, mode: "REVIEW" | "REPAIR", reason?: string, invalidResult?: unknown): KimiContentPart[] {
  const context = {
    slideNumber: input.slideNumber,
    expectedVisualContext: input.expected,
      outputShape: {
      slideNumber: input.slideNumber,
      issues: [{ slideNumber: input.slideNumber, code: "TEXT_OVERFLOW", severity: "WARNING", confidence: 0.8, description: "short visible description", repairHint: "short localized layout hint" }],
    },
    ...(mode === "REPAIR" ? { schemaRepair: { reason, previousResponse: truncate(JSON.stringify(invalidResult) ?? String(invalidResult)) } } : {}),
  };
  return [
    { type: "text", text: JSON.stringify(context) },
    { type: "image_url", image_url: { url: input.imageDataUrl } },
  ];
}

function parseIssue(value: unknown): VisualQaIssue | undefined {
  if (!isRecord(value) || !Number.isSafeInteger(value.slideNumber) || value.slideNumber < 1 || typeof value.code !== "string" || !VISUAL_ISSUE_CODES.includes(value.code as VisualIssueCode) || !isSeverity(value.severity) || typeof value.confidence !== "number" || !Number.isFinite(value.confidence) || value.confidence < 0 || value.confidence > 1 || !nonEmpty(value.description, 500) || !nonEmpty(value.repairHint, 300)) return undefined;
  return {
    slideNumber: value.slideNumber,
    code: value.code as VisualIssueCode,
    severity: value.severity,
    confidence: value.confidence,
    description: value.description,
    repairHint: value.repairHint,
  };
}

function compareIssues(a: VisualQaIssue, b: VisualQaIssue): number {
  return a.slideNumber - b.slideNumber || a.code.localeCompare(b.code) || a.severity.localeCompare(b.severity) || a.description.localeCompare(b.description);
}

function controlledPreviewRef(jobId: string, ref: string, slideNumber: number): boolean {
  return ref === `/internal/ppt-skill/v1/jobs/${jobId}/previews/${slideNumber}`;
}

function responseContent(value: unknown): string | undefined {
  if (typeof value === "string" && value.trim()) return value.trim();
  if (!Array.isArray(value)) return undefined;
  const text = value.map(item => isRecord(item) && typeof item.text === "string" ? item.text : "").join("").trim();
  return text || undefined;
}

function safeProviderError(): string {
  return "upstream response rejected";
}

function truncate(value: string): string { return value.length > 2000 ? `${value.slice(0, 2000)}...` : value; }
function ascii(bytes: Uint8Array, offset: number, length: number): string { return String.fromCharCode(...bytes.slice(offset, offset + length)); }
function nonEmpty(value: unknown, max: number): value is string { return typeof value === "string" && value.trim().length > 0 && value.length <= max; }
function isSeverity(value: unknown): value is VisualIssueSeverity { return value === "INFO" || value === "WARNING" || value === "ERROR"; }
function isRecord(value: unknown): value is Record<string, any> { return typeof value === "object" && value !== null && !Array.isArray(value); }
