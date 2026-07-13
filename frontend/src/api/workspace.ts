import { http } from './http';
import type { ApiResponse } from './health';
import type { MaterialFileType, MaterialParseStatus, MaterialUsageType } from './materials';
import type { ProjectStatus } from './projects';
import type { RequirementSummaryStatus } from './requirementSummaries';
import type { TeachingIntentStatus } from './teachingIntents';

export interface ProjectCounts {
  materialCount: number;
  parsedMaterialCount: number;
  knowledgeChunkCount: number;
  artifactCount: number;
  versionCount: number;
  exportCount: number;
}

export interface ProjectBrief {
  id: number;
  projectName: string;
  subtitle: string;
  courseName: string;
  chapterTitle: string;
  targetStudents?: string;
  lessonDurationMinutes?: number;
  lessonDurationLabel: string;
  modelMode: string;
  status: ProjectStatus;
  stage: string;
  stageLabel: string;
  progress: number;
  nextAction: string;
  actionPath: string;
  counts: ProjectCounts;
  createdAt: string;
  updatedAt: string;
}

export interface WorkspaceMetrics {
  projectCount: number;
  activeProjectCount: number;
  pendingTaskCount: number;
  materialCount: number;
  confirmedIntentCount: number;
  generatedArtifactCount: number;
}

export interface PendingTask {
  code: string;
  projectId: number;
  title: string;
  description: string;
  priority: string;
  actionPath: string;
  derived: boolean;
}

export interface Activity {
  type: string;
  projectId: number;
  title: string;
  description: string;
  occurredAt: string;
}

export interface Suggestion {
  code: string;
  projectId: number;
  title: string;
  description: string;
  actionPath: string;
}

export interface TeacherWorkspace {
  metrics: WorkspaceMetrics;
  continueProjects: ProjectBrief[];
  pendingTasks: PendingTask[];
  recentActivities: Activity[];
  suggestions: Suggestion[];
  generatedAt: string;
}

export interface ProjectPage {
  items: ProjectBrief[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  sort: string;
  query?: string;
  stage?: string;
}

export interface TimelineStep {
  code: string;
  label: string;
  state: string;
  completedAt?: string;
}

export interface ProjectOverviewMetrics {
  overallProgress: number;
  pptCount: number;
  docxCount: number;
  interactionCount: number;
  uploadedMaterialCount: number;
  parsedMaterialCount: number;
  indexedMaterialCount: number;
  knowledgeChunkCount: number;
  versionCount: number;
  currentVersion?: number;
  finalVersionConfirmed: boolean;
  exportCount: number;
}

export interface QuickAction {
  code: string;
  label: string;
  path: string;
  enabled: boolean;
}

export interface ProjectOverview {
  project: ProjectBrief;
  timeline: TimelineStep[];
  metrics: ProjectOverviewMetrics;
  recentActivities: Activity[];
  quickActions: QuickAction[];
}

export interface RequirementInputView {
  id: number;
  projectId: number;
  gradeLevel?: string;
  subject?: string;
  topic?: string;
  baselineLevel?: string;
  lessonDuration?: string;
  teachingGoals?: string;
  keyPoints?: string;
  difficultPoints?: string;
  stylePreference?: string;
  interactionType?: string;
  outputTypes: string[];
  rawRequirementText?: string;
  createdAt: string;
  updatedAt: string;
}

export interface RequirementFieldState {
  code: string;
  label: string;
  value?: string;
  completed: boolean;
}

export interface RequirementCompleteness {
  collected: number;
  total: number;
  percentage: number;
  fields: RequirementFieldState[];
}

export interface DialogMessageView {
  id: number;
  sessionId: string;
  sender: string;
  content: string;
  roundNo: number;
  createdAt: string;
}

export interface RequirementWorkspace {
  project: ProjectBrief;
  latestRequirement?: RequirementInputView | null;
  dialogues: DialogMessageView[];
  completeness: RequirementCompleteness;
  suggestedQuestions: string[];
  canGenerateSummary: boolean;
}

export interface RequirementSummaryView {
  id: number;
  sourceRequirementId: number;
  gradeLevel?: string;
  subject?: string;
  topic?: string;
  baselineLevel?: string;
  lessonDuration?: string;
  teachingGoals?: string;
  keyPoints?: string;
  difficultPoints?: string;
  outputTypes: string[];
  stylePreference?: string;
  interactionType?: string;
  generationMode: string;
  status: RequirementSummaryStatus;
  createdAt: string;
  updatedAt: string;
  confirmedAt?: string;
}

export interface RequirementSourceView {
  requirementId: number;
  sourceType: string;
  submittedAt: string;
}

export interface RequirementSummaryWorkspace {
  project: ProjectBrief;
  summary?: RequirementSummaryView | null;
  source?: RequirementSourceView | null;
  editable: boolean;
  canConfirm: boolean;
  nextStageCapabilities: string[];
}

export interface UploadPolicy {
  maxFileSizeBytes: number;
  maxFileSizeMb: number;
  supportedExtensions: string[];
  requiresConfirmedSummary: boolean;
  uploadEnabled: boolean;
}

export interface PurposeOption {
  code: MaterialUsageType;
  label: string;
  description: string;
}

export interface ParsePreview {
  parseResultId?: number;
  status: MaterialParseStatus;
  summary?: string;
  keywords: string[];
  applicableTeachingStages: string[];
  failureReason?: string;
  parsedAt?: string;
  prototype: boolean;
}

export interface MaterialWorkspaceItem {
  id: number;
  originalFilename: string;
  fileExtension: string;
  fileType: MaterialFileType;
  contentType: string;
  fileSize: number;
  description?: string;
  parseStatus: MaterialParseStatus;
  usageTypes: MaterialUsageType[];
  usageNote?: string;
  uploadedAt: string;
  downloadPath: string;
  parsePreview?: ParsePreview | null;
}

export interface MaterialStatistics {
  total: number;
  parsing: number;
  parsed: number;
  failed: number;
  indexed: number;
}

export interface MaterialWorkspace {
  project: ProjectBrief;
  uploadPolicy: UploadPolicy;
  purposeOptions: PurposeOption[];
  statistics: MaterialStatistics;
  materials: MaterialWorkspaceItem[];
}

export interface KnowledgeWorkspaceSearchRequest {
  query: string;
  materialId?: number;
  matchMode: 'PRECISE' | 'BROAD';
  caseSensitive: boolean;
  page: number;
  size: number;
}

export interface KnowledgeWorkspaceHit {
  chunkId: number;
  materialId: number;
  chunkNo: number;
  scorePercent: number;
  title: string;
  content: string;
  sourceFilename: string;
  sourceLocation: string;
  keywords: string[];
  usageTypes: MaterialUsageType[];
  hitReason: string;
}

export interface KnowledgeWorkspaceSearchResult {
  projectId: number;
  query: string;
  matchMode: string;
  caseSensitive: boolean;
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hits: KnowledgeWorkspaceHit[];
  algorithm: string;
  prototype: boolean;
}

export interface IntentEvidenceView {
  materialId: number;
  knowledgeChunkId: number;
  sourceFilename: string;
  usageTypes: MaterialUsageType[];
  hitReason: string;
  contentExcerpt: string;
}

export interface TeachingIntentView {
  id: number;
  requirementSummaryId: number;
  generationGoals: string[];
  generationGoal: string;
  primaryBasis: string;
  supplementalBasis: string[];
  contentBasis: string;
  targetAudience: string;
  totalHours?: number;
  teachingFormat: string;
  teachingApproach: string;
  interactionMode: string;
  outputTypes: string[];
  stylePreference?: string;
  notes?: string;
  evidenceItems: IntentEvidenceView[];
  status: TeachingIntentStatus;
  createdAt: string;
  updatedAt: string;
  confirmedAt?: string;
}

export interface IntentOption {
  code: string;
  label: string;
}

export interface TeachingIntentWorkspace {
  project: ProjectBrief;
  intent?: TeachingIntentView | null;
  options: {
    generationGoals: IntentOption[];
    contentBases: IntentOption[];
    teachingFormats: IntentOption[];
    outputTypes: IntentOption[];
  };
  canGenerate: boolean;
  canEdit: boolean;
  canConfirm: boolean;
  evidenceCount: number;
}

export interface TeachingIntentWorkspacePayload {
  generationGoals: string[];
  primaryBasis: string;
  supplementalBasis: string[];
  targetAudience: string;
  totalHours?: number;
  teachingFormat: string;
  outputTypes: string[];
  stylePreference?: string;
  notes?: string;
}

export async function getTeacherWorkspace() {
  const response = await http.get<ApiResponse<TeacherWorkspace>>('/api/workspace/overview');
  return response.data.data;
}

export async function getWorkspaceProjects(params: {
  query?: string;
  stage?: string;
  page?: number;
  size?: number;
  sort?: string;
} = {}) {
  const response = await http.get<ApiResponse<ProjectPage>>('/api/workspace/projects', { params });
  return response.data.data;
}

export async function getProjectWorkspaceOverview(projectId: number | string) {
  const response = await http.get<ApiResponse<ProjectOverview>>(`/api/projects/${projectId}/workspace-overview`);
  return response.data.data;
}

export async function getRequirementWorkspace(projectId: number | string) {
  const response = await http.get<ApiResponse<RequirementWorkspace>>(
    `/api/projects/${projectId}/requirements/workspace`,
  );
  return response.data.data;
}

export async function getRequirementSummaryWorkspace(projectId: number | string) {
  const response = await http.get<ApiResponse<RequirementSummaryWorkspace>>(
    `/api/projects/${projectId}/requirement-summaries/workspace`,
  );
  return response.data.data;
}

export async function getMaterialWorkspace(projectId: number | string) {
  const response = await http.get<ApiResponse<MaterialWorkspace>>(`/api/projects/${projectId}/materials/workspace`);
  return response.data.data;
}

export async function searchKnowledgeWorkspace(
  projectId: number | string,
  payload: KnowledgeWorkspaceSearchRequest,
) {
  const response = await http.post<ApiResponse<KnowledgeWorkspaceSearchResult>>(
    `/api/projects/${projectId}/knowledge/workspace-search`,
    payload,
  );
  return response.data.data;
}

export async function getTeachingIntentWorkspace(projectId: number | string) {
  const response = await http.get<ApiResponse<TeachingIntentWorkspace>>(
    `/api/projects/${projectId}/teaching-intents/workspace`,
  );
  return response.data.data;
}

export async function updateTeachingIntentWorkspace(
  projectId: number | string,
  intentId: number | string,
  payload: TeachingIntentWorkspacePayload,
) {
  const response = await http.put<ApiResponse<TeachingIntentWorkspace>>(
    `/api/projects/${projectId}/teaching-intents/${intentId}/workspace`,
    payload,
  );
  return response.data.data;
}
