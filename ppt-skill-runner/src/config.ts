import path from "node:path";
import os from "node:os";

export interface RunnerConfig {
  port: number;
  host: string;
  skillHome: string;
  tempRoot: string;
  resultRoot: string;
  pythonCommand: string;
  nodeCommand: string;
  timeoutMs: number;
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
    defaultPreset: env.PPT_SKILL_STYLE_PRESET?.trim() || "forest-research"
  };
}

function parsePositiveInt(value: string | undefined, fallback: number): number {
  if (!value) return fallback;
  const parsed = Number.parseInt(value, 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}
