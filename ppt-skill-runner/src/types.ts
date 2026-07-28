export const ALLOWED_VARIANTS = [
  "standard",
  "split",
  "cards-2",
  "cards-3",
  "timeline",
  "stats",
  "comparison-2col",
  "chart",
  "table",
  "image-sidebar",
  "flow"
] as const;

export const ALLOWED_PRESETS = [
  "executive-clinical",
  "bold-startup-narrative",
  "midnight-neon",
  "data-heavy-boardroom",
  "lab-report",
  "editorial-minimal",
  "paper-journal",
  "forest-research",
  "sunset-investor",
  "charcoal-safety",
  "arctic-minimal",
  "lavender-ops",
  "warm-terracotta"
  ,"a12-editorial-grid"
] as const;

export type AllowedPreset = typeof ALLOWED_PRESETS[number];

export interface GenerationRequest {
  outline: Record<string, unknown>;
  stylePreset?: string;
}

export interface QaSummary {
  passed: boolean;
  qaLevel: "AUTOMATED_GEOMETRY_ONLY";
  report: Record<string, unknown>;
}

export interface GenerationResult {
  jobId: string;
  status: "SUCCEEDED";
  fileName: "presentation.pptx";
  sizeBytes: number;
  sha256: string;
  qa: QaSummary;
  buildDurationMs: number;
  qaDurationMs: number;
  totalDurationMs: number;
  files: {
    presentation: string;
    outline: string;
    qaReport: string;
  };
}

export interface CommandSpec {
  command: string;
  args: string[];
  cwd: string;
  timeoutMs: number;
}

export interface CommandResult {
  exitCode: number;
  stdout: string;
  stderr: string;
}

export type CommandExecutor = (spec: CommandSpec) => Promise<CommandResult>;
