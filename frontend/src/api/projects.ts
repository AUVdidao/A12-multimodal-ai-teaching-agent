import { http } from '@/api/http';

export type GenerationMode = 'STANDARD' | 'HIGH_QUALITY' | 'ECONOMY' | 'MOCK';

export interface ProjectCreatePayload {
  projectName: string;
  courseName: string;
  chapterTopic: string;
  targetAudience: string;
  lessonDurationMinutes: number | null;
  generationMode: GenerationMode;
}

export interface ProjectResponse {
  id: number;
  projectName: string;
  courseName: string;
  chapterTopic: string;
  targetAudience: string;
  lessonDurationMinutes: number | null;
  generationMode: GenerationMode;
  status: string;
  createdAt: string;
  updatedAt: string;
}

interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
  timestamp: string;
}

export async function createProject(payload: ProjectCreatePayload) {
  const response = await http.post<ApiResponse<ProjectResponse>>('/api/projects', payload);
  return response.data.data;
}
