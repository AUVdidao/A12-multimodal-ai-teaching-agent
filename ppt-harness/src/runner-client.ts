import { HarnessConfig } from "./config.js";
import { HarnessError, PreviewFile, PreviewManifest } from "./domain.js";

export type RunnerGeneration = {
  jobId: string; status: "SUCCEEDED"; fileName: string; sizeBytes: number; sha256: string;
  qa: { passed: boolean; qaLevel: string; report: Record<string, unknown> };
  buildDurationMs: number; qaDurationMs: number; totalDurationMs: number;
  files: { presentation: string; outline: string; qaReport: string };
  preview: PreviewManifest;
};

export class PptSkillRunnerClient {
  constructor(private readonly config: HarnessConfig) {}
  async generate(outline: Record<string, unknown>, stylePreset: string): Promise<RunnerGeneration> {
    const response = await this.request("/internal/ppt-skill/v1/generations", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ outline, stylePreset }) });
    return parseRunnerGeneration(await parseJson<unknown>(response, "PPT_BUILD_FAILED"));
  }
  async download(relativePath: string): Promise<Uint8Array> {
    if (!/^\/internal\/ppt-skill\/v1\/jobs\/[0-9a-f-]{36}\/(presentation\.pptx|outline\.json|qa-report\.json)$/i.test(relativePath)) {
      throw new HarnessError("RUNNER_RESPONSE_INVALID", "Runner returned an invalid artifact reference", 502);
    }
    return this.downloadBytes(relativePath, "PPT_EMPTY_FILE");
  }
  async downloadPreview(relativePath: string): Promise<Uint8Array> {
    if (!/^\/internal\/ppt-skill\/v1\/jobs\/[0-9a-f-]{36}\/previews\/[1-9]\d{0,2}$/i.test(relativePath)) {
      throw new HarnessError("RUNNER_RESPONSE_INVALID", "Runner returned an invalid preview reference", 502);
    }
    return this.downloadBytes(relativePath, "PREVIEW_INVALID_FILE");
  }
  private async downloadBytes(relativePath: string, emptyCode: string): Promise<Uint8Array> {
    const response = await this.request(relativePath);
    const buffer = new Uint8Array(await response.arrayBuffer());
    if (!buffer.byteLength) throw new HarnessError(emptyCode, "Runner returned an empty preview/artifact", 502);
    return buffer;
  }
  private async request(path: string, init?: RequestInit): Promise<Response> {
    let response: Response;
    try { response = await fetch(`${this.config.runnerBaseUrl}${path}`, { ...init, signal: AbortSignal.timeout(this.config.runnerTimeoutMs) }); }
    catch (error) { throw new HarnessError(error instanceof DOMException && error.name === "TimeoutError" ? "RUNNER_TIMEOUT" : "RUNNER_UNAVAILABLE", "PPT generation runner is unavailable", 503); }
    if (!response.ok) {
      const failure = await readRunnerFailure(response);
      throw new HarnessError(failure.code, failure.message, 502);
    }
    return response;
  }
}

export function parseRunnerGeneration(value: unknown): RunnerGeneration {
  if (!isRecord(value) || value.status !== "SUCCEEDED" || !uuid(value.jobId) || value.fileName !== "presentation.pptx") {
    throw new HarnessError("RUNNER_RESPONSE_INVALID", "Runner returned an invalid generation result", 502);
  }
  const sizeBytes = positiveInteger(value.sizeBytes);
  const sha256 = hash(value.sha256);
  const qa = parseQa(value.qa);
  const files = parseArtifactFiles(value.files, value.jobId);
  const preview = parsePreviewManifest(value.preview, value.jobId);
  const buildDurationMs = nonNegativeInteger(value.buildDurationMs);
  const qaDurationMs = nonNegativeInteger(value.qaDurationMs);
  const totalDurationMs = nonNegativeInteger(value.totalDurationMs);
  if (sizeBytes === undefined || !sha256 || !qa || !files || buildDurationMs === undefined || qaDurationMs === undefined || totalDurationMs === undefined) {
    throw new HarnessError("RUNNER_RESPONSE_INVALID", "Runner returned incomplete generation metadata", 502);
  }
  return { jobId: value.jobId, status: "SUCCEEDED", fileName: "presentation.pptx", sizeBytes, sha256, qa, buildDurationMs, qaDurationMs, totalDurationMs, files, preview };
}

async function readRunnerFailure(response: Response): Promise<{ code: string; message: string }> {
  try {
    const body = await response.json() as { code?: unknown; message?: unknown };
    const code = typeof body.code === "string" && /^[A-Z0-9_]{3,80}$/.test(body.code)
      ? body.code
      : "PPT_BUILD_FAILED";
    const message = typeof body.message === "string" && body.message.trim().length > 0 && body.message.length <= 240
      ? body.message.trim()
      : "PPT generation runner rejected the request";
    return { code, message };
  } catch {
    return { code: "PPT_BUILD_FAILED", message: "PPT generation runner rejected the request" };
  }
}

async function parseJson<T>(response: Response, code: string): Promise<T> {
  try { return await response.json() as T; } catch { throw new HarnessError(code, "PPT generation runner returned invalid JSON", 502); }
}

function parseQa(value: unknown): RunnerGeneration["qa"] | undefined {
  if (!isRecord(value) || typeof value.passed !== "boolean" || typeof value.qaLevel !== "string" || !isRecord(value.report)) return undefined;
  return { passed: value.passed, qaLevel: value.qaLevel, report: value.report };
}

function parseArtifactFiles(value: unknown, jobId: string): RunnerGeneration["files"] | undefined {
  if (!isRecord(value)) return undefined;
  const presentation = internalRef(value.presentation, jobId, /presentation\.pptx$/i);
  const outline = internalRef(value.outline, jobId, /outline\.json$/i);
  const qaReport = internalRef(value.qaReport, jobId, /qa-report\.json$/i);
  return presentation && outline && qaReport ? { presentation, outline, qaReport } : undefined;
}

function parsePreviewManifest(value: unknown, jobId: string): PreviewManifest {
  if (!isRecord(value) || value.rendered !== true || value.format !== "png") {
    throw new HarnessError("RUNNER_RESPONSE_INVALID", "Runner returned an invalid preview manifest", 502);
  }
  const dpi = positiveInteger(value.dpi);
  const slideCount = positiveInteger(value.slideCount);
  const totalSizeBytes = positiveInteger(value.totalSizeBytes);
  if (dpi === undefined || slideCount === undefined || totalSizeBytes === undefined || !Array.isArray(value.files) || value.files.length !== slideCount) {
    throw new HarnessError("RUNNER_RESPONSE_INVALID", "Runner returned incomplete preview metadata", 502);
  }
  const files: PreviewFile[] = [];
  let total = 0;
  for (const [index, item] of value.files.entries()) {
    const slideNumber = isRecord(item) ? positiveInteger(item.slideNumber) : undefined;
    if (!isRecord(item) || slideNumber !== index + 1 || typeof item.fileName !== "string" || !/^slide-\d+\.png$/i.test(item.fileName)) {
      throw new HarnessError("RUNNER_RESPONSE_INVALID", "Runner preview ordering or filename is invalid", 502);
    }
    const sizeBytes = positiveInteger(item.sizeBytes);
    const width = positiveInteger(item.width);
    const height = positiveInteger(item.height);
    const sha256 = hash(item.sha256);
    const downloadRef = internalPreviewRef(item.downloadRef, jobId, slideNumber);
    if (sizeBytes === undefined || width === undefined || height === undefined || !sha256 || !downloadRef) {
      throw new HarnessError("RUNNER_RESPONSE_INVALID", "Runner returned invalid preview file metadata", 502);
    }
    total += sizeBytes;
    files.push({ slideNumber, fileName: item.fileName, sizeBytes, sha256, width, height, downloadRef });
  }
  if (total !== totalSizeBytes) throw new HarnessError("RUNNER_RESPONSE_INVALID", "Runner preview total size does not match file metadata", 502);
  return { rendered: true, format: "png", dpi, slideCount, totalSizeBytes, files };
}

function internalRef(value: unknown, jobId: string, suffix: RegExp): string | undefined {
  if (typeof value !== "string") return undefined;
  const expected = new RegExp(`^/internal/ppt-skill/v1/jobs/${jobId}/[^/]+$`, "i");
  return expected.test(value) && suffix.test(value) ? value : undefined;
}

function internalPreviewRef(value: unknown, jobId: string, slideNumber: number): string | undefined {
  if (typeof value !== "string") return undefined;
  const expected = `/internal/ppt-skill/v1/jobs/${jobId}/previews/${slideNumber}`;
  return value === expected ? value : undefined;
}

function uuid(value: unknown): value is string { return typeof value === "string" && /^[0-9a-f-]{36}$/i.test(value); }
function hash(value: unknown): string | undefined { return typeof value === "string" && /^[0-9a-f]{64}$/i.test(value) ? value : undefined; }
function positiveInteger(value: unknown): number | undefined { return typeof value === "number" && Number.isSafeInteger(value) && value > 0 ? value : undefined; }
function nonNegativeInteger(value: unknown): number | undefined { return typeof value === "number" && Number.isSafeInteger(value) && value >= 0 ? value : undefined; }
function isRecord(value: unknown): value is Record<string, unknown> { return typeof value === "object" && value !== null && !Array.isArray(value); }
