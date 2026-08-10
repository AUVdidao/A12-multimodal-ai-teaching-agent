export interface HarnessConfig {
  port: number; host: string; databaseUrl: string; runnerBaseUrl: string; runnerTimeoutMs: number;
  generationSource: "FIXTURE" | "KIMI"; visualReviewMode: VisualReviewMode; mcpBearerToken?: string;
  eventPollIntervalMs: number; artifactRetentionDays: number; maxRepairAttempts: number;
  kimiApiKey?: string; kimiBaseUrl: string; kimiModel: string; kimiTimeoutMs: number;
  kimiVisionEnabled: boolean; kimiVisionModel?: string;
}

export const VISUAL_REVIEW_MODES = ["DISABLED", "ADVISORY", "ENFORCING"] as const;
export type VisualReviewMode = typeof VISUAL_REVIEW_MODES[number];

export function loadConfig(env: NodeJS.ProcessEnv = process.env): HarnessConfig {
  return {
    port: positive(env.PORT, 8091), host: env.HOST?.trim() || "0.0.0.0",
    databaseUrl: env.PPT_HARNESS_DATABASE_URL?.trim() || "postgres://a12_harness:a12_harness_dev@postgres:5432/a12_harness",
    runnerBaseUrl: (env.PPT_SKILL_RUNNER_BASE_URL?.trim() || "http://ppt-skill-runner:8090").replace(/\/$/, ""),
    runnerTimeoutMs: positive(env.PPT_HARNESS_RUNNER_TIMEOUT_MS, 600000),
    generationSource: env.PPT_HARNESS_GENERATION_SOURCE === "KIMI" ? "KIMI" : "FIXTURE",
    visualReviewMode: parseVisualReviewMode(env),
    mcpBearerToken: env.PPT_HARNESS_MCP_BEARER_TOKEN?.trim() || undefined,
    eventPollIntervalMs: positive(env.PPT_HARNESS_EVENT_POLL_INTERVAL_MS, 750),
    artifactRetentionDays: positive(env.PPT_HARNESS_TEMP_RETENTION_DAYS, 7),
    maxRepairAttempts: positive(env.PPT_HARNESS_MAX_REPAIR_ATTEMPTS, 1),
    kimiApiKey: env.MOONSHOT_API_KEY?.trim() || undefined,
    kimiBaseUrl: (env.KIMI_API_BASE_URL?.trim() || "https://api.moonshot.ai/v1").replace(/\/$/, ""),
    kimiModel: env.KIMI_MODEL?.trim() || "kimi-k2.6",
    kimiTimeoutMs: positive(env.KIMI_TIMEOUT_MS, 120000),
    kimiVisionEnabled: env.KIMI_VISION_ENABLED === "true",
    kimiVisionModel: env.KIMI_VISION_MODEL?.trim() || undefined
  };
}
function positive(value: string | undefined, fallback: number) { const parsed = Number.parseInt(value || "", 10); return parsed > 0 ? parsed : fallback; }

export function parseVisualReviewMode(env: NodeJS.ProcessEnv): VisualReviewMode {
  const configured = env.PPT_HARNESS_VISUAL_REVIEW_MODE?.trim().toUpperCase();
  if (isVisualReviewMode(configured)) return configured;

  // Legacy compatibility is intentionally one-way: old false disables review,
  // old true opts into the safe advisory path, and the new mode always wins.
  const legacy = env.PPT_HARNESS_VISUAL_REVIEW_ENABLED?.trim().toLowerCase();
  if (legacy === "false") return "DISABLED";
  if (legacy === "true") return "ADVISORY";
  return "ADVISORY";
}

function isVisualReviewMode(value: string | undefined): value is VisualReviewMode {
  return value !== undefined && VISUAL_REVIEW_MODES.includes(value as VisualReviewMode);
}
