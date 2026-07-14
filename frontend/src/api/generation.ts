import type { ApiResponse } from './health';
import { http } from './http';
import type { ProjectStatus } from './projects';

export interface PlanOutlineItem {
  order: number;
  title: string;
  description: string;
}

export interface GenerationPlan {
  id: number;
  projectId: number;
  provider: string;
  pptOutline: PlanOutlineItem[];
  docOutline: PlanOutlineItem[];
  interactionPlan: string[];
  confirmed: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface GenerationPlanPayload {
  pptOutline: PlanOutlineItem[];
  docOutline: PlanOutlineItem[];
  interactionPlan: string[];
}

export type ArtifactType = 'PPT' | 'DOCX' | 'INTERACTION';
export type ArtifactContent = Record<string, unknown> | unknown[];

export interface Artifact {
  id: number;
  projectId: number;
  generationPlanId: number;
  versionId: number;
  versionNumber: number;
  type: ArtifactType;
  title: string;
  schemaVersion: number;
  content: ArtifactContent;
  createdAt: string;
}

export interface GenerationTeachingIntent {
  id?: number;
  status?: string;
  generationGoal?: string;
  generationGoals?: string[];
  contentBasis?: string;
  primaryBasis?: string;
  teachingApproach?: string;
  teachingFormat?: string;
  interactionMode?: string;
  outputTypes?: string[];
  targetAudience?: string;
  stylePreference?: string;
  [key: string]: unknown;
}

export interface GenerationCapabilityFlags {
  canCreatePlan?: boolean;
  canEditPlan?: boolean;
  canConfirmPlan?: boolean;
  canGenerate?: boolean;
  canPreview?: boolean;
  [key: string]: boolean | undefined;
}

export type GenerationCapabilities = string[] | GenerationCapabilityFlags;

export interface GenerationWorkspace {
  projectId: number;
  projectName: string;
  projectStatus: ProjectStatus | string;
  provider: string;
  teachingIntent?: GenerationTeachingIntent | null;
  latestPlan?: GenerationPlan | null;
  artifacts: Artifact[];
  capabilities: GenerationCapabilities;
}

export async function getGenerationWorkspace(projectId: number | string) {
  const response = await http.get<ApiResponse<GenerationWorkspace>>(
    `/api/projects/${projectId}/generation/workspace`,
  );
  return response.data.data;
}

export async function createGenerationPlan(projectId: number | string) {
  const response = await http.post<ApiResponse<GenerationPlan>>(
    `/api/projects/${projectId}/generation-plans`,
  );
  return response.data.data;
}

export async function getLatestGenerationPlan(projectId: number | string) {
  const response = await http.get<ApiResponse<GenerationPlan | null>>(
    `/api/projects/${projectId}/generation-plans/latest`,
  );
  return response.data.data;
}

export async function updateGenerationPlan(
  projectId: number | string,
  planId: number | string,
  payload: GenerationPlanPayload,
) {
  const response = await http.put<ApiResponse<GenerationPlan>>(
    `/api/projects/${projectId}/generation-plans/${planId}`,
    payload,
  );
  return response.data.data;
}

export async function confirmGenerationPlan(
  projectId: number | string,
  planId: number | string,
) {
  const response = await http.post<ApiResponse<GenerationPlan>>(
    `/api/projects/${projectId}/generation-plans/${planId}/confirm`,
  );
  return response.data.data;
}

export async function generateArtifacts(projectId: number | string, planId: number | string) {
  const response = await http.post<ApiResponse<Artifact[]>>(
    `/api/projects/${projectId}/artifacts/generate`,
    { planId },
  );
  return response.data.data;
}

export async function getArtifacts(projectId: number | string) {
  const response = await http.get<ApiResponse<Artifact[]>>(
    `/api/projects/${projectId}/artifacts`,
  );
  return response.data.data;
}

export async function getArtifact(projectId: number | string, artifactId: number | string) {
  const response = await http.get<ApiResponse<Artifact>>(
    `/api/projects/${projectId}/artifacts/${artifactId}`,
  );
  return response.data.data;
}
