import { http } from './http';
import type { ApiResponse } from './health';

export type ProjectStatus =
  | 'CREATED'
  | 'REQUIREMENT_CONFIRMED'
  | 'MATERIAL_READY'
  | 'INTENT_CONFIRMED'
  | 'GENERATED'
  | 'FINALIZED';

export interface TeachingProject {
  id: number;
  projectName: string;
  courseName: string;
  chapterTitle: string;
  targetStudents?: string;
  lessonDuration?: number;
  description?: string;
  modelMode: string;
  status: ProjectStatus;
  createdAt: string;
  updatedAt: string;
}

export interface ProjectPayload {
  projectName?: string;
  courseName: string;
  chapterTitle: string;
  targetStudents?: string;
  lessonDuration?: number;
  description?: string;
}

export interface ModelModeOption {
  code: 'STANDARD' | 'QUALITY' | 'ECONOMY';
  name: string;
  description: string;
}

export interface ProjectModelMode {
  projectId: number;
  mode: ModelModeOption['code'];
  name: string;
  description: string;
}

export async function listProjects() {
  const response = await http.get<ApiResponse<TeachingProject[]>>('/api/projects');
  return response.data.data;
}

export async function createProject(payload: ProjectPayload) {
  const response = await http.post<ApiResponse<TeachingProject>>('/api/projects', payload);
  return response.data.data;
}

export async function getProject(projectId: number | string) {
  const response = await http.get<ApiResponse<TeachingProject>>(`/api/projects/${projectId}`);
  return response.data.data;
}

export async function updateProject(projectId: number | string, payload: ProjectPayload) {
  const response = await http.put<ApiResponse<TeachingProject>>(`/api/projects/${projectId}`, payload);
  return response.data.data;
}

export async function listModelModes() {
  const response = await http.get<ApiResponse<ModelModeOption[]>>('/api/model-modes');
  return response.data.data;
}

export async function getProjectModelMode(projectId: number | string) {
  const response = await http.get<ApiResponse<ProjectModelMode>>(`/api/projects/${projectId}/model-mode`);
  return response.data.data;
}

export async function saveProjectModelMode(projectId: number | string, mode: ModelModeOption['code']) {
  const response = await http.put<ApiResponse<ProjectModelMode>>(`/api/projects/${projectId}/model-mode`, { mode });
  return response.data.data;
}
