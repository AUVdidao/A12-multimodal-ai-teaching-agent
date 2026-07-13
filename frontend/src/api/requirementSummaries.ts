import { http } from './http';
import type { ApiResponse } from './health';

export type RequirementSummaryStatus = 'DRAFT' | 'CONFIRMED';

export interface RequirementSummaryPayload {
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
}

export interface RequirementSummary extends RequirementSummaryPayload {
  id: number;
  projectId: number;
  sourceRequirementId: number;
  generationMode: string;
  status: RequirementSummaryStatus;
  createdAt: string;
  updatedAt: string;
  confirmedAt?: string;
}

export async function generateRequirementSummary(projectId: number | string) {
  const response = await http.post<ApiResponse<RequirementSummary>>(
    `/api/projects/${projectId}/requirement-summaries/generate`,
  );
  return response.data.data;
}

export async function getLatestRequirementSummary(projectId: number | string) {
  const response = await http.get<ApiResponse<RequirementSummary | null>>(
    `/api/projects/${projectId}/requirement-summaries/latest`,
  );
  return response.data.data;
}

export async function updateRequirementSummary(
  projectId: number | string,
  summaryId: number | string,
  payload: RequirementSummaryPayload,
) {
  const response = await http.put<ApiResponse<RequirementSummary>>(
    `/api/projects/${projectId}/requirement-summaries/${summaryId}`,
    payload,
  );
  return response.data.data;
}

export async function confirmRequirementSummary(
  projectId: number | string,
  summaryId: number | string,
) {
  const response = await http.post<ApiResponse<RequirementSummary>>(
    `/api/projects/${projectId}/requirement-summaries/${summaryId}/confirm`,
  );
  return response.data.data;
}
