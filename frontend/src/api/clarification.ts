import { http } from './http';
import type { ApiResponse } from './health';
import type { TeachingRequirementPayload } from './requirements';

export interface MissingField {
  field: string;
  label: string;
  reason: string;
}

export interface ClarificationResult {
  complete: boolean;
  missingFields: MissingField[];
  questions: string[];
}

export async function checkClarification(
  projectId: number | string,
  payload: TeachingRequirementPayload,
) {
  const response = await http.post<ApiResponse<ClarificationResult>>(
    `/api/projects/${projectId}/clarification/check`,
    payload,
  );
  return response.data.data;
}

export async function getClarificationQuestions(
  projectId: number | string,
  payload: TeachingRequirementPayload,
) {
  const response = await http.post<ApiResponse<ClarificationResult>>(
    `/api/projects/${projectId}/clarification/questions`,
    payload,
  );
  return response.data.data;
}
