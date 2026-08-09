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
  jobSnapshot: PresentationJobSnapshot;
  templateId: string;
  templateVersion: string;
  targetSlideCount: number;
  locale: string;
}
export interface PresentationJobSnapshot {
  project: Record<string, unknown>;
  requirementSummary: Record<string, unknown>;
  confirmedTeachingIntent: Record<string, unknown>;
  confirmedGenerationPlan: Record<string, unknown>;
  materialEvidence: Array<Record<string, unknown>>;
  templateSelection: Record<string, unknown>;
  generationPreferences: Record<string, unknown>;
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
  jobSnapshot: PresentationJobSnapshot;
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

export const SLIDE_SPEC_SCHEMA_VERSION = 2 as const;
export const PEDAGOGICAL_ROLES = [
  "HOOK", "OBJECTIVE", "CONCEPT", "EXPLANATION", "EXAMPLE", "WORKED_EXAMPLE", "INQUIRY",
  "EXPERIMENT", "COMPARISON", "PRACTICE", "FORMATIVE_ASSESSMENT", "DISCUSSION", "SUMMARY", "ASSIGNMENT"
] as const;
export type PedagogicalRole = typeof PEDAGOGICAL_ROLES[number];

export const VISUAL_INTENT_TYPES = [
  "TEXT", "IMAGE", "DIAGRAM", "FLOW", "COMPARISON", "TABLE", "CHART", "TIMELINE", "STATS", "CONCEPT_MAP", "MIXED"
] as const;
export type VisualIntentType = typeof VISUAL_INTENT_TYPES[number];

export const ASSET_REQUEST_TYPES = ["IMAGE", "ICON", "DIAGRAM", "CHART_DATA", "TABLE"] as const;
export type AssetRequestType = typeof ASSET_REQUEST_TYPES[number];

export const INTERACTION_TYPES = ["NONE", "QUESTION", "DISCUSSION", "POLL", "PRACTICE", "OBSERVATION", "GROUP_TASK"] as const;
export type InteractionType = typeof INTERACTION_TYPES[number];

export const DENSITIES = ["LOW", "MEDIUM", "HIGH"] as const;
export type Density = typeof DENSITIES[number];

export const IMPORTANCES = ["CORE", "SUPPORTING", "OPTIONAL"] as const;
export type Importance = typeof IMPORTANCES[number];

export interface TeachingObjective { objectiveId: string; text: string; }
export interface EvidenceRef {
  chunkId?: string | number;
  materialId?: string | number;
  snapshotIndex?: number;
}
export interface VisualIntent { type: VisualIntentType; description: string; }
export interface AssetRequest {
  type: AssetRequestType;
  purpose: string;
  query?: string;
  description?: string;
  sourcePreference?: string;
}
export interface Interaction {
  type: InteractionType;
  prompt?: string;
  expectedResponse?: string;
  durationMinutes?: number;
}
export interface SlideSpec {
  schemaVersion: typeof SLIDE_SPEC_SCHEMA_VERSION;
  deckTitle: string;
  locale: string;
  templateId: string;
  templateVersion: string;
  learningObjectives?: TeachingObjective[];
  slides: Slide[];
}
export interface Slide {
  slideId: string;
  pedagogicalRole: PedagogicalRole;
  learningObjectiveIds: string[];
  teachingPurpose: string;
  title: string;
  content: Record<string, unknown>;
  evidenceRefs: EvidenceRef[];
  sourceNotes: string[];
  visualIntent: VisualIntent;
  assetRequests: AssetRequest[];
  layoutIntent: string;
  interaction?: Interaction;
  teacherNotes: string;
  density: Density;
  importance: Importance;
  outlineSectionIndex: number | null;

  // Renderer-facing V1 fields remain part of the contract until Runner V2.
  layoutId: string;
  visualStrategy: string;
  slots: Record<string, unknown>;
}

export const MAX_EVIDENCE_ITEMS = 20;
export const MAX_EVIDENCE_TEXT_CHARS = 4000;
export const MAX_TOTAL_EVIDENCE_CHARS = 24000;

export class HarnessError extends Error {
  constructor(readonly code: string, message: string, readonly statusCode = 422) { super(message); }
}
