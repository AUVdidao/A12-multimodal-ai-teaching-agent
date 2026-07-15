import type { ApiResponse } from './health';
import { http } from './http';

export interface ArtifactVersion {
  id: number;
  projectId: number;
  generationPlanId?: number | null;
  versionNumber: number;
  description?: string | null;
  finalVersion: boolean;
  artifactCount: number;
  createdAt: string;
}

export async function listArtifactVersions(projectId: number | string) {
  const response = await http.get<ApiResponse<ArtifactVersion[]>>(
    `/api/v1/projects/${projectId}/artifact-versions`,
  );
  return response.data.data;
}

export async function finalizeArtifactVersion(
  projectId: number | string,
  versionId: number | string,
) {
  const response = await http.put<ApiResponse<ArtifactVersion>>(
    `/api/v1/projects/${projectId}/artifact-versions/${versionId}/finalize`,
  );
  return response.data.data;
}
