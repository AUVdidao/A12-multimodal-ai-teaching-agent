import { http } from './http';
import type { ApiResponse } from './health';
import type { MaterialUsageType } from './materials';

export type TeachingIntentStatus = 'DRAFT' | 'CONFIRMED';

export interface TeachingIntentEvidence {
  materialId: number;
  knowledgeChunkId: number;
  sourceFilename: string;
  usageTypes: MaterialUsageType[];
  hitReason: string;
  contentExcerpt: string;
}

export interface TeachingIntentPayload {
  generationGoal: string;
  contentBasis: string;
  teachingApproach: string;
  interactionMode: string;
  outputTypes: string[];
  stylePreference?: string;
}

export interface TeachingIntent extends TeachingIntentPayload {
  id: number;
  projectId: number;
  requirementSummaryId: number;
  evidenceItems: TeachingIntentEvidence[];
  status: TeachingIntentStatus;
  createdAt: string;
  updatedAt: string;
  confirmedAt?: string;
  prototype: boolean;
}

export async function generateTeachingIntent(projectId: number) {
  const response = await http.post<ApiResponse<TeachingIntent>>(
    `/api/projects/${projectId}/teaching-intents/generate`,
  );
  return response.data.data;
}

export async function getLatestTeachingIntent(projectId: number) {
  const response = await http.get<ApiResponse<TeachingIntent | null>>(
    `/api/projects/${projectId}/teaching-intents/latest`,
  );
  return response.data.data;
}

export async function updateTeachingIntent(projectId: number, intentId: number, payload: TeachingIntentPayload) {
  const response = await http.put<ApiResponse<TeachingIntent>>(
    `/api/projects/${projectId}/teaching-intents/${intentId}`,
    payload,
  );
  return response.data.data;
}

export async function confirmTeachingIntent(projectId: number, intentId: number) {
  const response = await http.post<ApiResponse<TeachingIntent>>(
    `/api/projects/${projectId}/teaching-intents/${intentId}/confirm`,
  );
  return response.data.data;
}

export async function createTeachingIntentRevision(projectId: number, intentId: number) {
  const response = await http.post<ApiResponse<TeachingIntent>>(
    `/api/projects/${projectId}/teaching-intents/${intentId}/revisions`,
  );
  return response.data.data;
}
