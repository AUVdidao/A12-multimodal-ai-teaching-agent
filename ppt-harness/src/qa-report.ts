import { VisualQaIssue } from "./visual-qa.js";

export type PublicQaReport = {
  qaLevel: string;
  passed: boolean;
  summary: {
    expectedSlideCount?: number;
    geometryErrorCount?: number;
    geometryWarningCount?: number;
    designErrorCount?: number;
    designWarningCount?: number;
    placeholderHits: string[];
    warnings: Array<{ type?: string; severity?: string; slideIndex?: number; suggestedFix?: string }>;
  };
  limitations: {
    renderSkipped: boolean;
    manualReviewSkipped: boolean;
    previewRenderingImplemented: boolean;
    visualReviewImplemented: boolean;
  };
  visualQa?: { jobId: string; passed: boolean; reviewedSlideCount: number; issues: VisualQaIssue[] };
};

/**
 * Runner reports are retained in the Harness database for audit, but REST and
 * MCP callers only receive this safe, path-free quality summary.
 */
export function toPublicQaReport(qaLevel: string, passed: boolean, report: Record<string, unknown>): PublicQaReport {
  const warnings = Array.isArray(report.geometry_violations)
    ? report.geometry_violations.map(toWarning).filter((warning): warning is PublicQaReport["summary"]["warnings"][number] => warning !== undefined)
    : [];
  const visualQa = publicVisualQa(report.visualQa);
  return {
    qaLevel,
    passed,
    summary: {
      expectedSlideCount: numberValue(report.expected_slide_count),
      geometryErrorCount: numberValue(report.geometry_error_count),
      geometryWarningCount: numberValue(report.geometry_warning_count),
      designErrorCount: numberValue(report.design_error_count),
      designWarningCount: numberValue(report.design_warning_count),
      placeholderHits: stringArray(report.placeholder_hits),
      warnings,
    },
    limitations: {
      renderSkipped: report.renderSkipped === true,
      manualReviewSkipped: report.manualReviewSkipped === true,
      previewRenderingImplemented: report.previewRenderingImplemented === true,
      visualReviewImplemented: report.visualReviewImplemented === true,
    },
    ...(visualQa ? { visualQa } : {}),
  };
}

function publicVisualQa(value: unknown): PublicQaReport["visualQa"] {
  if (!value || typeof value !== "object" || Array.isArray(value)) return undefined;
  const visual = value as Record<string, unknown>;
  const reviewedSlideCount = numberValue(visual.reviewedSlideCount);
  if (typeof visual.jobId !== "string" || typeof visual.passed !== "boolean" || reviewedSlideCount === undefined || !Number.isSafeInteger(reviewedSlideCount) || !Array.isArray(visual.issues)) return undefined;
  if (!visual.issues.every(issue => issue && typeof issue === "object" && !Array.isArray(issue))) return undefined;
  return { jobId: visual.jobId, passed: visual.passed, reviewedSlideCount, issues: visual.issues as VisualQaIssue[] };
}

function toWarning(value: unknown): PublicQaReport["summary"]["warnings"][number] | undefined {
  if (!value || typeof value !== "object" || Array.isArray(value)) return undefined;
  const warning = value as Record<string, unknown>;
  return {
    type: stringValue(warning.type),
    severity: stringValue(warning.severity),
    slideIndex: numberValue(warning.slide_index),
    suggestedFix: stringValue(warning.suggested_fix),
  };
}

function stringValue(value: unknown): string | undefined {
  return typeof value === "string" ? value : undefined;
}

function numberValue(value: unknown): number | undefined {
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

function stringArray(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === "string") : [];
}
