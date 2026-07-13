import { http } from './http';
import type { ApiResponse } from './health';

export interface TeachingRequirementPayload {
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
}

export interface TeachingRequirement extends TeachingRequirementPayload {
  id: number;
  projectId: number;
  createdAt: string;
  updatedAt: string;
}

export async function saveTeachingRequirement(
  projectId: number | string,
  payload: TeachingRequirementPayload,
) {
  const response = await http.post<ApiResponse<TeachingRequirement>>(
    `/api/projects/${projectId}/requirements`,
    payload,
  );
  return response.data.data;
}

export async function getLatestTeachingRequirement(projectId: number | string) {
  const response = await http.get<ApiResponse<TeachingRequirement | null>>(
    `/api/projects/${projectId}/requirements/latest`,
  );
  return response.data.data;
}
