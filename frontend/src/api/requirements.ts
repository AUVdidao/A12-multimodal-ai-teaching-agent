import { http } from '@/api/http';

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

export interface RequirementInputResponse extends RequirementInputPayload {
  id: number;
  projectId: number;
  createdAt: string;
  updatedAt: string;
}

interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
  timestamp: string;
}

export async function saveRequirementInput(projectId: number, payload: RequirementInputPayload) {
  const response = await http.post<ApiResponse<RequirementInputResponse>>(
    `/api/projects/${projectId}/requirements`,
    payload,
  );
  return response.data.data;
}

export async function getLatestRequirementInput(projectId: number) {
  const response = await http.get<ApiResponse<RequirementInputResponse | null>>(
    `/api/projects/${projectId}/requirements/latest`,
  );
  return response.data.data;
}
