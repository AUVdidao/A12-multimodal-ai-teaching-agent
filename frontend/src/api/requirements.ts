import { http } from './http';
import type { ApiResponse } from './health';

export interface RequirementInputPayload {
  gradeLevel: string;
  subject: string;
  topic: string;
  lessonDuration: string;
  teachingGoals: string;
  keyPoints: string;
  difficultPoints: string;
  outputTypes: string[];
  rawRequirementText: string;
}

export interface RequirementInput extends RequirementInputPayload {
  id: number;
  projectId: number;
  createdAt: string;
  updatedAt: string;
}

export async function saveRequirementInput(
  projectId: number | string,
  payload: RequirementInputPayload,
) {
  const response = await http.post<ApiResponse<RequirementInput>>(
    `/api/projects/${projectId}/requirements`,
    payload,
  );
  return response.data.data;
}

export async function getLatestRequirementInput(projectId: number | string) {
  const response = await http.get<ApiResponse<RequirementInput | null>>(
    `/api/projects/${projectId}/requirements/latest`,
  );
  return response.data.data;
}
