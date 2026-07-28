import { HarnessConfig } from "./config.js";
import { HarnessError } from "./domain.js";

export type RunnerGeneration = {
  jobId: string; status: "SUCCEEDED"; fileName: string; sizeBytes: number; sha256: string;
  qa: { passed: boolean; qaLevel: string; report: Record<string, unknown> };
  buildDurationMs: number; qaDurationMs: number; totalDurationMs: number;
  files: { presentation: string; outline: string; qaReport: string };
};

export class PptSkillRunnerClient {
  constructor(private readonly config: HarnessConfig) {}
  async generate(outline: Record<string, unknown>, stylePreset: string): Promise<RunnerGeneration> {
    const response = await this.request("/internal/ppt-skill/v1/generations", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ outline, stylePreset }) });
    return await parseJson<RunnerGeneration>(response, "PPT_BUILD_FAILED");
  }
  async download(relativePath: string): Promise<Uint8Array> {
    if (!/^\/internal\/ppt-skill\/v1\/jobs\/[0-9a-f-]{36}\/(presentation\.pptx|outline\.json|qa-report\.json)$/i.test(relativePath)) {
      throw new HarnessError("RUNNER_RESPONSE_INVALID", "Runner returned an invalid artifact reference", 502);
    }
    const response = await this.request(relativePath);
    const buffer = new Uint8Array(await response.arrayBuffer());
    if (!buffer.byteLength) throw new HarnessError("PPT_EMPTY_FILE", "Runner returned an empty artifact", 502);
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
