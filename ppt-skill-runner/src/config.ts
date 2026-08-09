import path from "node:path";
import os from "node:os";
import { RESULT_RETENTION_DAYS_DEFAULT } from "./retention";

export interface RunnerConfig {
  port: number;
  host: string;
  skillHome: string;
  tempRoot: string;
  resultRoot: string;
  pythonCommand: string;
  nodeCommand: string;
  timeoutMs: number;
  previewTimeoutMs: number;
  previewDpi: number;
  previewMaxFileBytes: number;
  previewMaxTotalBytes: number;
  resultRetentionDays?: number;
  defaultPreset: string;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): RunnerConfig {
  const projectRoot = path.resolve(__dirname, "..", "..");
  return {
    port: parsePositiveInt(env.PORT, 8090),
    host: env.HOST?.trim() || "0.0.0.0",
    skillHome: path.resolve(env.PRESENTATION_SKILL_HOME || path.join(projectRoot, "vendor", "presentation-skill")),
    tempRoot: path.resolve(env.PPT_SKILL_TEMP_ROOT || path.join(os.tmpdir(), "a12-ppt-skill-runner")),
    resultRoot: path.resolve(env.PPT_SKILL_RESULT_ROOT || path.join(projectRoot, "data", "results")),
    pythonCommand: env.PYTHON_COMMAND?.trim() || "python3",
    nodeCommand: env.NODE_COMMAND?.trim() || process.execPath,
    timeoutMs: parsePositiveInt(env.PPT_SKILL_TIMEOUT_MS, 600_000),
    previewTimeoutMs: parsePositiveInt(env.PPT_SKILL_PREVIEW_TIMEOUT_MS, 180_000),
    previewDpi: parsePositiveInt(env.PPT_SKILL_PREVIEW_DPI, 150),
    previewMaxFileBytes: parsePositiveInt(env.PPT_SKILL_PREVIEW_MAX_FILE_BYTES, 20 * 1024 * 1024),
    previewMaxTotalBytes: parsePositiveInt(env.PPT_SKILL_PREVIEW_MAX_TOTAL_BYTES, 200 * 1024 * 1024),
    resultRetentionDays: parsePositiveInt(env.PPT_SKILL_RESULT_RETENTION_DAYS, RESULT_RETENTION_DAYS_DEFAULT),
    defaultPreset: env.PPT_SKILL_STYLE_PRESET?.trim() || "forest-research"
  };
}

function parsePositiveInt(value: string | undefined, fallback: number): number {
  if (!value) return fallback;
  const parsed = Number.parseInt(value, 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}
