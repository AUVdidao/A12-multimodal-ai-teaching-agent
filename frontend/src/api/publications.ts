import type { ArtifactType } from './generation';
import type { ApiResponse } from './health';
import { http } from './http';

export type PublicationStatus = 'PUBLISHED' | 'WITHDRAWN';

export interface Publication {
  id: number;
  approvalRequestId: number;
  artifactVersionId: number;
  projectId: number;
  projectName: string;
  courseId: number;
  courseName: string;
  classId: number;
  className: string;
  title: string;
  summary?: string | null;
  publishedBy: number;
  publishedByName: string;
  status: PublicationStatus;
  publishedAt: string;
  withdrawnAt?: string | null;
}

export interface CreatePublicationPayload {
  approvalRequestId: number;
  classId: number;
  title: string;
  summary?: string;
}

export interface LearningTaskSummary {
  publicationId: number;
  approvalRequestId: number;
  artifactVersionId: number;
  projectId: number;
  projectName: string;
  courseId: number;
  courseName: string;
  classId: number;
  className: string;
  title: string;
  summary?: string | null;
  publishedAt: string;
}

export interface ArtifactVersionMetadata {
  id: number;
  versionNumber: number;
  description?: string | null;
  finalVersion: boolean;
  createdAt: string;
}

export interface PublishedArtifact {
  artifactType: ArtifactType;
  title: string;
  contentJson: string;
  schemaVersion: number;
}

export interface LearningTaskDetail extends LearningTaskSummary {
  artifactVersion: ArtifactVersionMetadata;
  artifacts: PublishedArtifact[];
}

const publicationsPath = '/api/v1/publications';
const learningTasksPath = '/api/v1/student/learning-tasks';

export async function listPublications(status?: PublicationStatus) {
  const response = await http.get<ApiResponse<Publication[]>>(publicationsPath, {
    params: status ? { status } : undefined,
  });
  return response.data.data;
}

export async function getPublication(publicationId: number | string) {
  const response = await http.get<ApiResponse<Publication>>(`${publicationsPath}/${publicationId}`);
  return response.data.data;
}

export async function createPublication(payload: CreatePublicationPayload) {
  const response = await http.post<ApiResponse<Publication>>(publicationsPath, payload);
  return response.data.data;
}

export async function withdrawPublication(publicationId: number | string) {
  const response = await http.post<ApiResponse<Publication>>(
    `${publicationsPath}/${publicationId}/withdraw`,
  );
  return response.data.data;
}

export async function listLearningTasks() {
  const response = await http.get<ApiResponse<LearningTaskSummary[]>>(learningTasksPath);
  return response.data.data;
}

export async function getLearningTask(publicationId: number | string) {
  const response = await http.get<ApiResponse<LearningTaskDetail>>(
    `${learningTasksPath}/${publicationId}`,
  );
  return response.data.data;
}
