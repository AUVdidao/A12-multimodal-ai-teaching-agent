export const JOB_STATUSES = [
  "QUEUED", "LOADING_REQUIREMENT", "LOADING_TEMPLATE", "BUILDING_TEMPLATE_CONTEXT",
  "GENERATING_SLIDE_SPEC", "VALIDATING_SLIDE_SPEC", "REPAIRING_SLIDE_SPEC", "RENDERING_PPTX",
  "RENDERING_PREVIEW", "RUNNING_DETERMINISTIC_QA", "VISUAL_REVIEW", "REVISING", "FINALIZING",
  "RETRY_PENDING", "SUCCEEDED", "FAILED", "CANCELLED"
] as const;
export type JobStatus = typeof JOB_STATUSES[number];

export interface PresentationJobRequest {
  requestId: string;
  projectId: number;
  requirementSnapshot: Record<string, unknown>;
  templateId: string;
  templateVersion: string;
  targetSlideCount: number;
  locale: string;
}
export interface JobArtifact {
  fileName: string;
  sizeBytes: number;
  sha256: string;
  qaLevel: string;
  qaPassed: boolean;
  runnerJobId: string;
  downloadRef: string;
}
export interface PresentationJob {
  id: string;
  requestId: string;
  projectId: number;
  status: JobStatus;
  templateId: string;
  templateVersion: string;
  locale: string;
  targetSlideCount: number;
  currentStep?: JobStatus;
  progressPercent: number;
  attemptCount: number;
  requirementSnapshot: Record<string, unknown>;
  artifact?: JobArtifact;
  errorCode?: string;
  errorMessage?: string;
  createdAt: string;
  updatedAt: string;
  completedAt?: string;
}
export interface JobEvent { id: number; status: JobStatus; message: string; progressPercent: number; createdAt: string; }
export interface TemplateSpec { templateId: string; version: string; name: string; locale: string; previewRef: string; stylePreset: string; layouts: TemplateLayout[]; }
export interface TemplateLayout { layoutId: string; slots: string[]; capacity: Record<string, number>; }
export interface SlideSpec { deckTitle: string; locale: string; templateId: string; templateVersion: string; slides: Slide[]; }
export interface Slide { slideId: string; layoutId: string; title: string; visualStrategy: string; slots: Record<string, unknown>; }

export class HarnessError extends Error {
  constructor(readonly code: string, message: string, readonly statusCode = 422) { super(message); }
}
