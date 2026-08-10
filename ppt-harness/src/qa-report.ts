import { VisualQaIssue } from "./visual-qa.js";
import { VISUAL_REVIEW_MODES, VisualReviewMode } from "./config.js";
import { VisualIssueCounts, VisualReviewState } from "./visual-qa.js";

export type PublicQaReport = {
  qaLevel: string;
  mode: VisualReviewMode;
  reviewState: VisualReviewState;
  reviewedSlideCount: number;
  issueCounts: VisualIssueCounts;
  visualAssessmentPassed: boolean | null;
  artifactGatePassed: boolean;
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
  visualReview: {
    mode: VisualReviewMode;
    reviewState: VisualReviewState;
    reviewedSlideCount: number;
    issueCounts: VisualIssueCounts;
    visualAssessmentPassed: boolean | null;
    issues: VisualQaIssue[];
    failure?: { code: string; message: string };
  };
};

/**
 * Runner reports are retained in the Harness database for audit, but REST and
 * MCP callers only receive this safe, path-free quality summary.
 */
export function toPublicQaReport(qaLevel: string, artifactGatePassed: boolean, report: Record<string, unknown>): PublicQaReport {
  const warnings = Array.isArray(report.geometry_violations)
    ? report.geometry_violations.map(toWarning).filter((warning): warning is PublicQaReport["summary"]["warnings"][number] => warning !== undefined)
    : [];
  const legacyVisual = report.visualQa;
  const visualReview = publicVisualReview(
    report.visualReview ?? legacyVisual,
    report.visualReviewMode ?? (legacyVisual ? "ADVISORY" : undefined),
    report.visualReviewState,
    report.reviewedSlideCount,
    report.issueCounts,
    report.visualAssessmentPassed,
  );
  return {
    qaLevel,
    mode: visualReview.mode,
    reviewState: visualReview.reviewState,
    reviewedSlideCount: visualReview.reviewedSlideCount,
    issueCounts: visualReview.issueCounts,
    visualAssessmentPassed: visualReview.visualAssessmentPassed,
    artifactGatePassed,
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
    visualReview,
  };
}

function publicVisualReview(
  value: unknown,
  modeValue: unknown,
  stateValue: unknown,
  reviewedSlideCountValue: unknown,
  issueCountsValue: unknown,
  assessmentValue: unknown,
): PublicQaReport["visualReview"] {
  const visual = value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {};
  const mode = visualMode(visual.mode ?? modeValue) ?? (reportWasReviewed(modeValue, stateValue) ? "ADVISORY" : "DISABLED");
  const reviewState = visualState(visual.state ?? stateValue) ?? (mode === "DISABLED" ? "NOT_RUN" : "FAILED_TO_REVIEW");
  const reviewedSlideCount = numberValue(visual.reviewedSlideCount ?? reviewedSlideCountValue) ?? 0;
  const issues = Array.isArray(visual.issues) && visual.issues.every(issue => issue && typeof issue === "object" && !Array.isArray(issue))
    ? visual.issues as VisualQaIssue[]
    : [];
  const issueCounts = validIssueCounts(visual.issueCounts ?? issueCountsValue) ? visual.issueCounts as VisualIssueCounts : countIssues(issues);
  const assessment = visual.visualAssessmentPassed ?? visual.passed ?? assessmentValue;
  const visualAssessmentPassed = typeof assessment === "boolean" ? assessment : null;
  const failure = visual.failure && typeof visual.failure === "object" && !Array.isArray(visual.failure)
    ? {
        code: stringValue((visual.failure as Record<string, unknown>).code) ?? "VISUAL_REVIEW_FAILED",
        message: stringValue((visual.failure as Record<string, unknown>).message) ?? "Visual QA review did not complete",
      }
    : undefined;
  return { mode, reviewState, reviewedSlideCount, issueCounts, visualAssessmentPassed, issues, ...(failure ? { failure } : {}) };
}

function visualMode(value: unknown): VisualReviewMode | undefined {
  return typeof value === "string" && VISUAL_REVIEW_MODES.includes(value as VisualReviewMode) ? value as VisualReviewMode : undefined;
}

function visualState(value: unknown): VisualReviewState | undefined {
  return value === "NOT_RUN" || value === "SUCCEEDED" || value === "UNAVAILABLE" || value === "FAILED_TO_REVIEW" ? value : undefined;
}

function reportWasReviewed(mode: unknown, state: unknown): boolean {
  return (typeof mode === "string" && mode !== "DISABLED") || state === "SUCCEEDED" || state === "UNAVAILABLE" || state === "FAILED_TO_REVIEW";
}

function validIssueCounts(value: unknown): value is VisualIssueCounts {
  return !!value && typeof value === "object" && !Array.isArray(value)
    && ["total", "info", "warning", "error"].every(key => Number.isSafeInteger((value as Record<string, unknown>)[key]) && Number((value as Record<string, unknown>)[key]) >= 0);
}

function countIssues(issues: VisualQaIssue[]): VisualIssueCounts {
  return {
    total: issues.length,
    info: issues.filter(issue => issue.severity === "INFO").length,
    warning: issues.filter(issue => issue.severity === "WARNING").length,
    error: issues.filter(issue => issue.severity === "ERROR").length,
  };
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
